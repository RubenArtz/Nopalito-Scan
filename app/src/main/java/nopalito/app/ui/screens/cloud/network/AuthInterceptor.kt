/*
 *
 * Copyright 2025-2026 The FairScan authors
 * Copyright 2026 Ruben Matias
 *
 * Modified by Ruben Matias in 2026.
 * This file is part of the Nopalito Scan fork.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package nopalito.app.ui.screens.cloud.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.LocaleNormalizer
import nopalito.app.ui.screens.cloud.model.ApiResponse
import nopalito.app.ui.screens.cloud.model.RefreshRequest
import nopalito.app.ui.screens.cloud.model.TokenData
import nopalito.app.ui.screens.cloud.security.BiometricSessionManager
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

private const val ACCEPT_LANGUAGE = "Accept-Language"
private const val X_APP_LANGUAGE = "x-app-language"

/**
 * Single source of truth for the locale headers of every cloud request.
 *
 * Contract: `Accept-Language` and `x-app-language` carry EXACTLY the same
 * normalized tag, derived from the app's CURRENT locale (see
 * [AppLocaleOverride], which is updated when the user changes the language):
 *
 * ```
 * Accept-Language: LocaleNormalizer.normalizeForBackend(currentLocale)
 * x-app-language:  LocaleNormalizer.normalizeForBackend(currentLocale)
 * ```
 *
 * [LocaleNormalizer] never throws and always returns a supported value
 * (`pt-BR` stays `pt-BR`, `es-419` stays `es-419`, `fr-CA` reduces to `fr`,
 * `zh`/empty/invalid reduce to `es`), so this helper can never fail.
 *
 * Existing values are removed FIRST (removeHeader drops every occurrence of
 * the name, i.e. removeAll) and exactly one value is added, so a preexisting
 * or duplicated header is always replaced — never duplicated.
 *
 * The value is re-evaluated on every call, so a language change is picked up
 * by the next request without any cache to invalidate.
 *
 * Note: the backend resolves `user.language` before these headers; if a
 * stored user preference exists server-side it wins — the client never
 * overrides it.
 */
internal fun Request.Builder.withLocaleHeaders(): Request.Builder {
    val value = LocaleNormalizer.normalizeForBackend(AppLocaleOverride.locale.toLanguageTag())
    return removeHeader(ACCEPT_LANGUAGE).addHeader(ACCEPT_LANGUAGE, value)
        .removeHeader(X_APP_LANGUAGE).addHeader(X_APP_LANGUAGE, value)
}

/**
 * OkHttp Interceptor that:
 * 1. Adds the locale headers (Accept-Language + x-app-language, same tag) to
 *    every request
 * 2. Adds Authorization Bearer header when access token is available
 * 3. Automatically handles 401 responses by attempting token refresh
 * 4. If refresh fails, clears tokens and signals logout via LogoutException
 *
 * Locale handling: see [withLocaleHeaders]. The refresh endpoint itself runs
 * on the dedicated non-intercepted client (CloudApiClient), which applies the
 * same helper so every request type — normal, retry after refresh, multipart,
 * download and unauthenticated — carries both headers.
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
class AuthInterceptor(
    private val tokenProvider: TokenProvider,
    private val refreshApi: Lazy<AuthApi>,
    private val biometricSessionManager: BiometricSessionManager? = null,
    /**
     * Called ONLY on definitive server-driven logout (403 suspended/deleted,
     * or 401 refresh rejected). The caller (CloudApiClient) wipes the biometric
     * blob here so a stale biometric key never survives a forced login.
     * Transient network errors (UnknownHost, timeout, 5xx) NEVER trigger it —
     * they surface as Error state and keep the blob intact.
     */
    private val onForceLogout: (() -> Unit)? = null
) : Interceptor {

    private fun forceLogoutAndClear(msg: String): Nothing {
        try {
            onForceLogout?.invoke()
        } catch (_: Exception) {
            // Best-effort: wiping the biometric blob must never block logout.
        }
        tokenProvider.clearTokens()
        throw LogoutException(msg)
    }

    // Gate so only ONE thread performs the network refresh at a time.
    private val isRefreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Monitor where threads that lost the [isRefreshing] race WAIT (bounded)
     * until the winner publishes the refreshed tokens — instead of sleeping a
     * blind fixed 2s that may be too short or needlessly long.
     */
    private val refreshMonitor = Object()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Build request with the locale headers
        val requestWithLanguage = originalRequest.newBuilder()
            .withLocaleHeaders()
            .build()

        // Add Authorization if we have an access token
        val accessToken = tokenProvider.getAccessToken()
        android.util.Log.d(
            "AuthInterceptor",
            "Token present: ${accessToken != null}, path: ${originalRequest.url.encodedPath}"
        )
        val authenticatedRequest = if (accessToken != null) {
            requestWithLanguage.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            android.util.Log.w(
                "AuthInterceptor",
                "NO TOKEN - request without Authorization: ${originalRequest.url.encodedPath}"
            )
            requestWithLanguage
        }

        val response = chain.proceed(authenticatedRequest)

        // Handle 403 for suspended/deleted accounts - force logout immediately
        // and invalidate the biometric blob (server says the account is gone).
        // For unauthenticated requests (login 403 suspended) do NOT force logout;
        // return the 403 response so the UI can show the suspended dialog.
        // Use peekBody to avoid consuming/closing the original response body.
        if (response.code == 403) {
            val errorCode = extractErrorCode(response)
            if (errorCode != null && shouldForceLogout(errorCode)) {
                // Only force logout when we have an authenticated session
                if (tokenProvider.getAccessToken() != null || tokenProvider.getRefreshToken() != null) {
                    response.close()
                    forceLogoutAndClear("Account $errorCode")
                }
            }
            // Semantic entitlement invalidation: only exact codes, never message.
            // Excluded: billing/status, billing/google/verify, storage/usage, auth refresh, admin
            // Debounce + single-flight handled by BillingEntitlementManager.
            try {
                if (errorCode != null && errorCode in setOf(
                        "PLAN_REQUIRED",
                        "SUBSCRIPTION_REQUIRED",
                        "STORAGE_LIMIT_REACHED",
                        "ENTITLEMENT_OUTDATED"
                    )
                ) {
                    // Do not emit for TOKEN_ALREADY_LINKED or SUBSCRIPTION_NOT_ACTIVE (handled explicitly by ViewModel)
                    nopalito.app.billing.EntitlementInvalidationBus.notifyIfRelevant(
                        errorCode,
                        originalRequest.url.encodedPath
                    )
                }
            } catch (_: Exception) {
            }
        }

        // Handle 401 — P0 FIX: supports normal and biometric mode
        // In biometric mode the refreshToken lives in the blob (not in prefs), so
        // the original check `getRefreshToken()!=null` failed after 15m → 401 not recovered → "Not authorized"
        val isBiometricMode = biometricSessionManager?.isEnabled == true
        val hasNormalRefresh = tokenProvider.getRefreshToken() != null
        val hasBiometricRefresh = isBiometricMode && (biometricSessionManager.hasActiveSession)
        val canRefresh = hasNormalRefresh || hasBiometricRefresh

        // Only refresh on 401 for TOKEN_EXPIRED (not SESSION_EXPIRED already invalidated)
        // To avoid depending on body, try refresh on any 401 with available refresh;
        // if it's SESSION_EXPIRED the refresh will fail and logout anyway.
        if (response.code == 401 && canRefresh) {
            response.close()

            // Avoid infinite loop on refresh endpoint
            val path = originalRequest.url.encodedPath
            if (path.contains("/api/auth/refresh")) {
                forceLogoutAndClear("Refresh token invalid or expired")
            }

            // If another thread is already refreshing, wait bounded and retry with current token
            if (!isRefreshing.compareAndSet(false, true)) {
                try {
                    synchronized(refreshMonitor) {
                        if (isRefreshing.get()) {
                            refreshMonitor.wait(CONCURRENT_REFRESH_WAIT_MS)
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                val newAccessToken = tokenProvider.getAccessToken()
                if (newAccessToken != null) {
                    val retryRequest = originalRequest.newBuilder()
                        .withLocaleHeaders()
                        .addHeader("Authorization", "Bearer $newAccessToken")
                        .build()
                    return chain.proceed(retryRequest)
                }
                forceLogoutAndClear("Refresh already failed in another thread")
            }

            return try {
                if (isBiometricMode && hasBiometricRefresh) {
                    // Biometric path: refreshToken in Tier-2 blob (not in prefs)
                    val biometricRefreshToken =
                        biometricSessionManager.unlockSession.decryptRefreshToken()
                            ?: run { forceLogoutAndClear("No biometric refresh token") }
                    performBiometricTokenRefresh(chain, originalRequest, biometricRefreshToken)
                } else {
                    // Normal path: refreshToken in EncryptedSharedPreferences
                    val refreshTokenValue = tokenProvider.getRefreshToken()
                        ?: run { forceLogoutAndClear("No refresh token available") }
                    performTokenRefresh(chain, originalRequest, refreshTokenValue)
                }
            } catch (e: LogoutException) {
                throw e
            } catch (_: Exception) {
                forceLogoutAndClear("Refresh failed")
            } finally {
                isRefreshing.set(false)
                synchronized(refreshMonitor) {
                    refreshMonitor.notifyAll()
                }
            }
        }

        return response
    }

    private fun performTokenRefresh(
        chain: Interceptor.Chain,
        originalRequest: Request,
        refreshTokenValue: String
    ): Response {
        val api: AuthApi = refreshApi.value
        val response: retrofit2.Response<ApiResponse<TokenData>> = runBlocking {
            api.refreshToken(RefreshRequest(refreshTokenValue))
        }

        if (response.isSuccessful) {
            val body: ApiResponse<TokenData>? = response.body()
            val tokenData: TokenData? = body?.data
            if (tokenData != null) {
                tokenProvider.saveTokens(
                    accessToken = tokenData.accessToken,
                    refreshToken = tokenData.refreshToken
                )
                Log.i(
                    "AuthInterceptor",
                    "Token refresh (normal) succeeded for path: ${originalRequest.url.encodedPath}"
                )
                val retryRequest = originalRequest.newBuilder()
                    .withLocaleHeaders()
                    .addHeader("Authorization", "Bearer ${tokenData.accessToken}")
                    .build()
                return chain.proceed(retryRequest)
            }
        }

        Log.w(
            "AuthInterceptor",
            "Token refresh failed: HTTP ${response.code()}"
        )
        forceLogoutAndClear("Refresh failed: code ${response.code()}")
    }

    /** Biometric path: rotated refreshToken is re-encrypted with in-memory Tier-2 (no prompt). */
    private fun performBiometricTokenRefresh(
        chain: Interceptor.Chain,
        originalRequest: Request,
        biometricRefreshToken: String
    ): Response {
        val api: AuthApi = refreshApi.value
        val response: retrofit2.Response<ApiResponse<TokenData>> = runBlocking {
            api.refreshToken(RefreshRequest(biometricRefreshToken))
        }

        if (response.isSuccessful) {
            val body: ApiResponse<TokenData>? = response.body()
            val tokenData: TokenData? = body?.data
            if (tokenData != null) {
                // Save accessToken and its exp (for isExpiredOrExpiringSoon)
                tokenProvider.saveAccessToken(tokenData.accessToken)
                // Rotated refreshToken -> re-encrypt in biometric blob (no prompt)
                val rotated =
                    biometricSessionManager!!.unlockSession.rotateRefreshToken(tokenData.refreshToken)
                if (!rotated) {
                    Log.w(
                        "AuthInterceptor",
                        "Biometric rotate failed — session remains in memory, will retry on next refresh"
                    )
                } else {
                    // Don't leave a copy in normal prefs when biometric mode is active
                    tokenProvider.removeRefreshToken()
                }
                Log.i(
                    "AuthInterceptor",
                    "Token refresh (biometric) succeeded for path: ${originalRequest.url.encodedPath}"
                )
                val retryRequest = originalRequest.newBuilder()
                    .withLocaleHeaders()
                    .addHeader("Authorization", "Bearer ${tokenData.accessToken}")
                    .build()
                return chain.proceed(retryRequest)
            }
        }

        Log.w(
            "AuthInterceptor",
            "Biometric token refresh failed: HTTP ${response.code()}"
        )
        // 401 on biometric refresh -> revoked/expired session -> wipe Tier-2
        if (response.code() == 401) {
            try {
                biometricSessionManager?.unlockSession?.wipe()
            } catch (_: Exception) {
            }
        }
        forceLogoutAndClear("Biometric refresh failed: code ${response.code()}")
    }
}

/**
 * Exception thrown when logout is required due to authentication failure.
 */
class LogoutException(message: String) : IOException(message)

/**
 * Error codes that should force an immediate logout (account suspended/deleted).
 * These are returned by the backend with HTTP 403 when the account state
 * prevents any authenticated access.
 */
private val FORCE_LOGOUT_ERROR_CODES = setOf(
    "AUTH_ACCOUNT_SUSPENDED",
    "AUTH_LOGIN_BLOCKED_SUSPENDED",
    "AUTH_PASSWORD_RESET_BLOCKED_SUSPENDED",
    "AUTH_FORBIDDEN_ACCOUNT_DELETED",
    "AUTH_FORBIDDEN_ACCOUNT_INACTIVE_DELETED",
)

/**
 * Extracts the error code from a 403 response body without consuming it.
 * Uses peekBody so the original response remains readable for Retrofit's errorBody.
 */
private fun extractErrorCode(response: Response): String? {
    try {
        val peek = response.peekBody(Long.MAX_VALUE)
        val body = peek.string()
        if (body.isBlank()) return null
        val json = Gson().fromJson(body, Map::class.java)
        val errorObj = json["error"] as? Map<*, *>
        return errorObj?.get("code") as? String
    } catch (_: Exception) {
        return null
    }
}

/**
 * Determines if the given error code should force an immediate logout.
 */
private fun shouldForceLogout(errorCode: String): Boolean {
    return errorCode in FORCE_LOGOUT_ERROR_CODES
}

private const val CONCURRENT_REFRESH_WAIT_MS = 10_000L
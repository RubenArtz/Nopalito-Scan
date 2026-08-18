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

import kotlinx.coroutines.runBlocking
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.LocaleNormalizer
import nopalito.app.ui.screens.cloud.model.ApiResponse
import nopalito.app.ui.screens.cloud.model.RefreshRequest
import nopalito.app.ui.screens.cloud.model.TokenData
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
class AuthInterceptor(
    private val tokenProvider: TokenProvider,
    private val refreshApi: Lazy<AuthApi>
) : Interceptor {

    // Thread-safe flag to prevent concurrent refresh attempts
    private val isRefreshing = java.util.concurrent.atomic.AtomicBoolean(false)

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

        // Handle 401 with token refresh attempt
        if (response.code == 401 && tokenProvider.getRefreshToken() != null) {
            response.close()

            // Prevent infinite loop on the refresh endpoint itself
            val path = originalRequest.url.encodedPath
            if (path.contains("/api/auth/refresh")) {
                tokenProvider.clearTokens()
                throw LogoutException("Refresh token invalid or expired")
            }

            // If another thread is already refreshing, wait briefly and retry with new token
            if (!isRefreshing.compareAndSet(false, true)) {
                // Another thread is refreshing; wait a bit then retry original request
                try {
                    Thread.sleep(2000)
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
                tokenProvider.clearTokens()
                throw LogoutException("Refresh already failed in another thread")
            }

            return try {
                // Attempt token refresh
                val refreshTokenValue = tokenProvider.getRefreshToken() ?: run {
                    tokenProvider.clearTokens()
                    throw LogoutException("No refresh token available")
                }
                performTokenRefresh(chain, originalRequest, refreshTokenValue)
            } catch (_: Exception) {
                tokenProvider.clearTokens()
                throw LogoutException("Refresh failed")
            } finally {
                isRefreshing.set(false)
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
                android.util.Log.i(
                    "AuthInterceptor",
                    "Token refresh succeeded for path: ${originalRequest.url.encodedPath}"
                )

                // Retry original request with new token
                val retryRequest = originalRequest.newBuilder()
                    .withLocaleHeaders()
                    .addHeader("Authorization", "Bearer ${tokenData.accessToken}")
                    .build()
                return chain.proceed(retryRequest)
            }
        }

        tokenProvider.clearTokens()
        android.util.Log.w(
            "AuthInterceptor",
            "Token refresh failed: HTTP ${response.code()}"
        )
        throw LogoutException("Refresh failed: code ${response.code()}")
    }
}

/**
 * Exception thrown when logout is required due to authentication failure.
 */
class LogoutException(message: String) : IOException(message)
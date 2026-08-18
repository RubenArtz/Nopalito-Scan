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

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.gson.GsonBuilder
import nopalito.app.BuildConfig
import nopalito.app.ui.screens.cloud.security.BiometricLifecycleObserver
import nopalito.app.ui.screens.cloud.security.BiometricPromptHost
import nopalito.app.ui.screens.cloud.security.BiometricSessionManager
import nopalito.app.ui.screens.cloud.security.BiometricTokenStore
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * CloudApiClient provides a singleton Retrofit instance configured to communicate with the
 * backend URL supplied at build time via BuildConfig.API_BASE_URL (local.properties or
 * the API_BASE_URL environment variable; never hardcoded in the repository).
 *
 * Features:
 * - OkHttpClient with connection pooling and timeouts
 * - AuthInterceptor for Bearer token, and automatic token refresh on 401
 * - Dedicated non-intercepting OkHttpClient for token refresh to avoid deadlock (recursive interceptor + runBlocking)
 * - Logging interceptor (debug only, secrets filtered)
 * - Gson converter for JSON serialization (with flexible boolean parsing)
 * - All API interfaces accessible via lazy properties
 */
class CloudApiClient private constructor(context: Context) {

    private val tokenProvider: TokenProvider by lazy {
        TokenProvider(context.applicationContext) { biometricSessionManager.isEnabled }
    }

    /**
     * App-wide biometric session manager. Its prompt controller is only
     * resolved on demand from [BiometricPromptHost] (registered by whichever
     * screen hosts the OS prompt), so reads of `isEnabled` are safe anywhere.
     *
     * The [BiometricLifecycleObserver] is wired to the process lifecycle: only
     * a REAL background (process `ON_STOP`) wipes Tier-2; rotation,
     * recomposition, the biometric dialog and internal navigation never do.
     */
    private val biometricSessionManager: BiometricSessionManager by lazy {
        BiometricSessionManager(BiometricTokenStore.open(context.applicationContext)) {
            BiometricPromptHost.requireController()
        }.also { manager ->
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                BiometricLifecycleObserver(manager.unlockSession)
            )
        }
    }

    // ====== Gson instance with flexible boolean parsing ======
    // Tolerates is_deleted=0/1, success=1, is_verified="true"/"false", etc.
    // Registers adapter for both java.lang.Boolean (nullable Kotlin Boolean?)
    // and boolean primitive (non-nullable Kotlin Boolean).
    // Uses a TypeAdapterFactory so Gson applies the adapter to ALL boolean-like fields,
    // regardless of nullability. This avoids IllegalStateException when the backend
    // sends 0/1 for boolean fields (common with MySQL TINYINT(1) columns).
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(FlexibleBooleanTypeAdapterFactory())
        .create()

    /**
     * Main OkHttpClient with full interceptor chain (auth + logging).
     * Used by all public API interfaces (files, sync, health, auth/me, etc.).
     */
    private val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
        // IMPORTANT: This interceptor calls refreshApi internally via performTokenRefresh.
        // To avoid deadlock, the refresh call goes through refreshOkHttpClient (NO AuthInterceptor).
        builder.addInterceptor(
            AuthInterceptor(tokenProvider, lazy { refreshAuthApi })
        )

        // Logging interceptor - only in debug builds, filter sensitive headers
        if (isDebugBuild(context)) {
            val logging = HttpLoggingInterceptor { message ->
                val sanitized = message
                    .replace(Regex("(x-app-secret|Authorization|Bearer\\s)[^\\s]+"), "$1 ***")
                android.util.Log.d("CloudOkHttp", sanitized)
            }
            logging.level = HttpLoggingInterceptor.Level.HEADERS
            builder.addInterceptor(logging)
        }

        builder.build()
    }

    /**
     * Dedicated OkHttpClient for token refresh ONLY.
     * Does NOT include AuthInterceptor to prevent:
     * 1. Recursive interceptor calls (handling a 401 by making another request that also gets intercepted)
     * 2. Thread starvation / deadlock from runBlocking inside the interceptor
     *
     * Still includes the logging interceptor for debugging. The locale headers
     * ARE applied via a dedicated interceptor reusing [withLocaleHeaders], so
     * the refresh request carries the same Accept-Language / x-app-language
     * pair as every other request.
     */
    private val refreshOkHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(2, 30, TimeUnit.SECONDS))

        builder.addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().withLocaleHeaders().build())
        })

        // Logging interceptor for debugging
        if (isDebugBuild(context)) {
            val logging = HttpLoggingInterceptor { message ->
                val sanitized = message
                    .replace(Regex("(x-app-secret|Authorization|Bearer\\s)\\S+"), "$1 ***")
                android.util.Log.d("CloudOkHttp", sanitized)
            }
            logging.level = HttpLoggingInterceptor.Level.HEADERS
            builder.addInterceptor(logging)
        }

        builder.build()
    }

    private val authApi: AuthApi by lazy {
        Retrofit.Builder()
            .baseUrl(validateBaseUrl(BuildConfig.API_BASE_URL))
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(AuthApi::class.java)
    }

    /**
     * Separate Retrofit/AuthApi for refresh calls only.
     * Uses refreshOkHttpClient (without AuthInterceptor) to avoid deadlock.
     */
    private val refreshAuthApi: AuthApi by lazy {
        Retrofit.Builder()
            .baseUrl(validateBaseUrl(BuildConfig.API_BASE_URL))
            .client(refreshOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(AuthApi::class.java)
    }

    // ====== Public API accessors ======

    val auth: AuthApi get() = authApi

    val storage: StorageApi by lazy {
        createService(StorageApi::class.java)
    }

    val files: FileApi by lazy {
        createService(FileApi::class.java)
    }

    val libreOffice: LibreOfficeApi by lazy {
        createService(LibreOfficeApi::class.java)
    }

    val qr: QrApi by lazy {
        createService(QrApi::class.java)
    }

    val scans: ScanApi by lazy {
        createService(ScanApi::class.java)
    }

    /** FCM device registration (push notifications). */
    val devices: DeviceApi by lazy {
        createService(DeviceApi::class.java)
    }

    /** Cloud maintenance status (public, no auth). */
    val maintenance: MaintenanceApi by lazy {
        createService(MaintenanceApi::class.java)
    }

    val tokenProviderInstance: TokenProvider get() = tokenProvider

    val biometricSessionManagerInstance: BiometricSessionManager get() = biometricSessionManager

    // ====== Private helpers ======

    private fun <T> createService(serviceClass: Class<T>): T {
        val baseUrl = validateBaseUrl(BuildConfig.API_BASE_URL)
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(serviceClass)
    }

    private fun validateBaseUrl(url: String): String {
        val trimmed = url.trim()
        require(trimmed.isNotEmpty() && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            "Invalid API_BASE_URL: '$url'. Configure it in local.properties or the API_BASE_URL environment variable."
        }
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun isDebugBuild(context: Context): Boolean {
        return context.applicationContext.applicationInfo != null &&
                (context.applicationContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    companion object {
        private const val CONNECT_TIMEOUT_SEC = 30L
        private const val READ_TIMEOUT_SEC = 60L
        private const val WRITE_TIMEOUT_SEC = 120L

        @Volatile
        private var instance: CloudApiClient? = null

        fun getInstance(context: Context): CloudApiClient {
            return instance ?: synchronized(this) {
                instance ?: CloudApiClient(context.applicationContext).also { instance = it }
            }
        }
    }
}
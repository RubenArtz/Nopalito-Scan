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

import nopalito.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Publishes the app identity on every request:
 *
 * ```
 * User-Agent: NopalitoScan/1.0.22 (Android 15)
 * ```
 *
 * - `name/version` mirror [BuildConfig.VERSION_NAME] (the exact build installed).
 * - `(OS version)` comes from [android.os.Build.VERSION.RELEASE].
 *
 * The backend parses this format into structured log fields (app, appVersion,
 * platform, osVersion) and persists it as the session device signature, so it
 * MUST stay stable: `Name/Version (Platform OsVersion)`. Change it only in
 * lockstep with `parseUserAgent` in the Cloud repo (src/middleware/requestLogger.js).
 *
 * The header is always REPLACED (never appended), so a stale caller-supplied
 * value can never produce a duplicated header.
 */
class UserAgentInterceptor(
    private val value: () -> String = { DEFAULT_USER_AGENT },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .removeHeader(HEADER)
            .addHeader(HEADER, value())
            .build()
        return chain.proceed(request)
    }

    companion object {
        const val HEADER = "User-Agent"

        /** Kept in sync with the Cloud parser; see the class KDoc. */
        const val APP_NAME = "NopalitoScan"

        /** Evaluated per request so tests and tooling can stub it. */
        val DEFAULT_USER_AGENT: String
            get() = "$APP_NAME/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})"
    }
}

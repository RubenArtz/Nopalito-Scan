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

package nopalito.app.diagnostics

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException

/**
 * Decides which caught throwables deserve a Crashlytics non-fatal report.
 *
 * The app surfaces many *expected* failures through its normal UX (offline
 * banners, login errors, session expiry, biometric retry): sending those
 * would drown real bugs in noise and leak nothing useful. The outermost
 * throwable and its causes are evaluated; a wrapped session signal (e.g. a
 * `RuntimeException` carrying the original as its cause) is still routine.
 * Everything not explicitly listed below is reported.
 *
 * Deliberately decoupled from the cloud module: backend/session exception
 * types are matched by simple name so this diagnostics layer never depends
 * on feature code (dependency inversion). Pure JVM logic, unit tested.
 */
object CrashlyticsErrorFilter {

    data class Decision(val report: Boolean, val reason: String)

    private val sessionFlowNames = setOf(
        "LogoutException",
        "NeedsBiometricUnlockException"
    )
    private val backendErrorNames = setOf(
        "ApiException",
        "HttpException"
    )

    /**
     * Forced-logout signals thrown by the auth interceptor
     * (`AuthInterceptor.forceLogoutAndClear`). Matched by message prefix so the
     * filter keeps working when R8 obfuscates the exception class name (its
     * simpleName then becomes e.g. `p94` and the name check above misses).
     * Prefixes are fixed technical strings, never user data.
     */
    private val sessionFlowMessagePrefixes = listOf(
        "Refresh already failed",
        "Refresh failed",
        "Refresh token invalid",
        "Biometric refresh failed",
        "No refresh token",
        "No biometric refresh token",
        "Account "
    )

    private const val MAX_CAUSE_DEPTH = 2

    fun shouldReport(throwable: Throwable): Decision {
        if (throwable is kotlin.coroutines.cancellation.CancellationException
        ) {
            return Decision(false, "coroutine_cancelled")
        }
        if (throwable is InterruptedIOException ||
            throwable is UnknownHostException ||
            throwable is ConnectException ||
            throwable is NoRouteToHostException ||
            throwable is SocketException
        ) {
            return Decision(false, "network_transient")
        }
        var cause: Throwable? = throwable
        var depth = 0
        while (cause != null && depth <= MAX_CAUSE_DEPTH) {
            val decision = shouldReportSingle(cause)
            if (!decision.report) return decision
            cause = cause.cause
            depth++
        }
        return Decision(true, "unexpected:${throwable.javaClass.simpleName}")
    }

    /**
     * True for expected sign-out signals (expired/revoked session). Best-effort
     * callers such as the startup language sync use this to log at warning
     * level instead of filing a Crashlytics non-fatal for a routine re-login.
     */
    fun isSessionFlowSignal(throwable: Throwable): Boolean {
        val decision = shouldReport(throwable)
        return !decision.report && decision.reason == "session_flow"
    }

    private fun shouldReportSingle(throwable: Throwable): Decision {
        val name = throwable.javaClass.simpleName
        if (name in sessionFlowNames) {
            return Decision(false, "session_flow")
        }
        if (name in backendErrorNames) {
            return Decision(false, "backend_business_error")
        }
        if (throwable is java.io.IOException &&
            sessionFlowMessagePrefixes.any { prefix ->
                throwable.message?.startsWith(prefix) == true
            }
        ) {
            return Decision(false, "session_flow")
        }
        return Decision(true, "unexpected:$name")
    }
}
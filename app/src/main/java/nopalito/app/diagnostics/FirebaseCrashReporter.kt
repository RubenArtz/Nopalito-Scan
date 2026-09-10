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

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import nopalito.app.BuildConfig

/**
 * [CrashReporter] backed by Firebase Crashlytics.
 *
 * Privacy contract: the original throwable (whose message may embed file
 * paths or backend echoes) is never sent as-is. A sanitized copy carrying
 * the original stack frames is recorded instead, so issues still group by
 * code location while no PII can leave the device. The original exception
 * kind is preserved in the message and in the `error_kind` custom key.
 *
 * Uses the plain `FirebaseCrashlytics` singleton (no `-ktx` artifact needed).
 * Every path is guarded: reporting never throws and never blocks the caller.
 */
object FirebaseCrashReporter : CrashReporter {

    private const val TAG = "CrashlyticsReport"
    private const val MAX_CAUSE_DEPTH = 2

    private val allowedContextKeys = setOf(
        "screen",
        "operation",
        "resource_type",
        "environment",
        "error_kind"
    )

    override fun report(
        tag: String,
        message: String,
        throwable: Throwable,
        context: Map<String, String>
    ) {
        try {
            val decision = CrashlyticsErrorFilter.shouldReport(throwable)
            val kind = throwable.javaClass.simpleName
            if (!decision.report) {
                Log.d(TAG, "filtered tag=$tag kind=$kind reason=${decision.reason}")
                return
            }
            val crashlytics = try {
                FirebaseCrashlytics.getInstance()
            } catch (e: Exception) {
                Log.d(TAG, "unavailable tag=$tag reason=${e.javaClass.simpleName}")
                return
            }
            if (!crashlytics.isCrashlyticsCollectionEnabled) {
                Log.d(TAG, "collection disabled, skip tag=$tag kind=$kind")
                return
            }
            val safeMessage = PiiSanitizer.sanitize(message)
            try {
                crashlytics.setCustomKey("operation", PiiSanitizer.sanitize(tag, 64))
                crashlytics.setCustomKey(
                    "environment",
                    if (BuildConfig.DEBUG) "debug" else "release"
                )
                crashlytics.setCustomKey("error_kind", kind.take(64))
                for ((key, value) in context) {
                    if (key in allowedContextKeys) {
                        runCatching {
                            crashlytics.setCustomKey(key, PiiSanitizer.sanitize(value, 64))
                        }
                    }
                }
            } catch (_: Exception) {
            }
            try {
                crashlytics.recordException(sanitizedCopy(tag, safeMessage, throwable))
            } catch (_: Exception) {
                return
            }
            Log.d(TAG, "non-fatal reported tag=$tag kind=$kind reason=${decision.reason}")
        } catch (e: Exception) {
            try {
                Log.d(TAG, "report failed: ${e.javaClass.simpleName}")
            } catch (_: Exception) {
            }
        }
    }

    override fun report(
        tag: String,
        message: String,
        context: Map<String, String>
    ) {
        report(tag, message, TechnicalException(message), context)
    }

    private fun sanitizedCopy(
        tag: String,
        safeMessage: String,
        original: Throwable,
        depth: Int = 0
    ): Throwable {
        val safeCause = if (depth < MAX_CAUSE_DEPTH) {
            original.cause?.let { cause ->
                runCatching {
                    sanitizedCopy(
                        tag,
                        PiiSanitizer.sanitize(cause.message ?: cause.javaClass.simpleName),
                        cause,
                        depth + 1
                    )
                }.getOrNull()
            }
        } else null
        return RuntimeException(
            "[$tag] $safeMessage (${original.javaClass.simpleName})",
            safeCause
        ).apply { setStackTrace(original.stackTrace) }
    }
}
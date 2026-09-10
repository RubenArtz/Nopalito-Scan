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

package nopalito.app.data

import android.util.Log
import nopalito.app.diagnostics.CrashReporter
import nopalito.app.diagnostics.FirebaseCrashReporter
import nopalito.app.diagnostics.TechnicalException

fun interface Logger {
    fun e(tag: String, message: String, throwable: Throwable)
}

/**
 * Reports a failure described only by text. Builds a technical exception so
 * the issue still shows up grouped in Crashlytics.
 */
fun Logger.e(tag: String, message: String) {
    e(tag, message, TechnicalException(message))
}

class FileLogger(
    private val logRepository: LogRepository,
    private val crashReporter: CrashReporter = FirebaseCrashReporter
) : Logger {
    override fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
        logRepository.log(tag, message, throwable)
        // Best-effort non-fatal report: a Firebase failure must never break
        // the operation that is already handling its own error.
        try {
            crashReporter.report(tag, message, throwable)
        } catch (_: Exception) {
        }
    }

    fun e(tag: String, message: String) {
        e(tag, message, TechnicalException(message))
    }
}

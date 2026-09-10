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

/**
 * Abstraction over the crash-reporting backend so domain code and the central
 * [nopalito.app.data.FileLogger] depend on this interface instead of the
 * Firebase SDK directly. Implementations must never throw.
 */
interface CrashReporter {

    /**
     * Reports a caught error as a non-fatal issue. [context] accepts only
     * safe technical keys (`screen`, `operation`, `resource_type`,
     * `environment`, `error_kind`); anything else is ignored.
     */
    fun report(
        tag: String,
        message: String,
        throwable: Throwable,
        context: Map<String, String> = emptyMap()
    )

    /**
     * Reports a failure described only by text. The implementation builds a
     * technical exception from [message] so it still shows up grouped in
     * Crashlytics.
     */
    fun report(
        tag: String,
        message: String,
        context: Map<String, String> = emptyMap()
    )
}

/** Technical placeholder used when a failure carries text but no throwable. */
class TechnicalException(message: String) : RuntimeException(message)
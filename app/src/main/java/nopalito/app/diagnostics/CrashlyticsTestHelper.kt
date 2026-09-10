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
import nopalito.app.BuildConfig

/**
 * Debug-only entry points to verify the Firebase consoles end to end.
 *
 * - [testNonFatalCrashlytics] sends a grouped non-fatal issue (visible in
 *   Crashlytics without killing the app).
 * - [testCrashlytics] throws an uncaught exception (kills the process; the
 *   report uploads on the next cold start).
 *
 * Both hard-require `BuildConfig.DEBUG` and are never called from production
 * code paths. Invoke manually from a debugger breakpoint, a debug-only UI
 * action, or `adb` while running a debug build, then check Logcat tags
 * `CrashlyticsTest` / `CrashlyticsReport`.
 */
object CrashlyticsTestHelper {

    private const val TAG = "CrashlyticsTest"

    fun testNonFatalCrashlytics(reporter: CrashReporter = FirebaseCrashReporter) {
        check(BuildConfig.DEBUG) { "CrashlyticsTestHelper is debug-only" }
        Log.w(TAG, "Sending test non-fatal report")
        reporter.report(
            "DebugTest",
            "Test non-fatal event",
            RuntimeException("Test non-fatal crash report")
        )
    }

    fun testCrashlytics(): Nothing {
        check(BuildConfig.DEBUG) { "CrashlyticsTestHelper is debug-only" }
        Log.w(TAG, "Throwing test fatal crash")
        throw RuntimeException("Test crash")
    }
}
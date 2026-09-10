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

import nopalito.app.data.FileLogger
import nopalito.app.data.LogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Proves the central-logger contract: one `logger.e()` call fans out to the
 * local log file and to the non-fatal reporter, and a reporter failure never
 * propagates back to the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FileLoggerCrashlyticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingReporter : CrashReporter {
        val calls = mutableListOf<Triple<String, String, Throwable>>()
        override fun report(
            tag: String,
            message: String,
            throwable: Throwable,
            context: Map<String, String>
        ) {
            calls += Triple(tag, message, throwable)
        }

        override fun report(tag: String, message: String, context: Map<String, String>) {
            calls += Triple(tag, message, TechnicalException(message))
        }
    }

    private class ThrowingReporter : CrashReporter {
        override fun report(
            tag: String,
            message: String,
            throwable: Throwable,
            context: Map<String, String>
        ): Nothing = throw RuntimeException("Firebase down")

        override fun report(tag: String, message: String, context: Map<String, String>): Nothing =
            throw RuntimeException("Firebase down")
    }

    @Test
    fun `error reaches local log and non-fatal reporter`() {
        val file = File(tmp.root, "logs.txt")
        val reporter = RecordingReporter()
        val logger = FileLogger(LogRepository(file), reporter)

        val error = IllegalStateException("unexpected state")
        logger.e("Export", "Failed to save PDF", error)

        assertTrue(file.readText().contains("Failed to save PDF"))
        assertEquals(1, reporter.calls.size)
        assertEquals("Export", reporter.calls[0].first)
        assertEquals("Failed to save PDF", reporter.calls[0].second)
        assertEquals(error, reporter.calls[0].third)
    }

    @Test
    fun `text-only error builds a technical exception for the reporter`() {
        val file = File(tmp.root, "logs.txt")
        val reporter = RecordingReporter()
        val logger = FileLogger(LogRepository(file), reporter)

        logger.e("OcrInit", "Engine missing")

        assertEquals(1, reporter.calls.size)
        assertTrue(reporter.calls[0].third is TechnicalException)
    }

    @Test
    fun `reporter failure never breaks the logging call`() {
        val file = File(tmp.root, "logs.txt")
        val logger = FileLogger(LogRepository(file), ThrowingReporter())

        logger.e("Export", "Failed to save PDF", IllegalStateException("boom"))

        assertTrue(file.readText().contains("Failed to save PDF"))
    }
}
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsEventValidatorTest {

    @Test
    fun `accepts business event names`() {
        listOf(
            AnalyticsEvents.DOCUMENT_CREATED,
            AnalyticsEvents.SCAN_COMPLETED,
            AnalyticsEvents.OCR_COMPLETED,
            AnalyticsEvents.DOCUMENT_EXPORTED,
            AnalyticsEvents.SYNC_FAILED,
            AnalyticsEvents.SUBSCRIPTION_STARTED
        ).forEach { name ->
            assertTrue("expected valid: $name", AnalyticsEventValidator.isValidName(name))
        }
    }

    @Test
    fun `rejects uppercase event names`() {
        assertFalse(AnalyticsEventValidator.isValidName("Document_Created"))
    }

    @Test
    fun `rejects names with spaces or dashes`() {
        assertFalse(AnalyticsEventValidator.isValidName("document created"))
        assertFalse(AnalyticsEventValidator.isValidName("document-created"))
    }

    @Test
    fun `rejects empty names`() {
        assertFalse(AnalyticsEventValidator.isValidName(""))
    }

    @Test
    fun `sanitizes personal data out of string params`() {
        val out = AnalyticsEventValidator.sanitizeParam("user@example.com")
        assertFalse(out.contains("user@example.com"))
        assertEquals("[email]", out)
    }
}
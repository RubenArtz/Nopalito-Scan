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

package nopalito.app.ui.screens.cloud.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM parsing of the web QR payload (no Android, no camera).
 */
class QrLinkPayloadTest {

    private fun payload(exp: Long = 9_999_999_999L): String =
        """{"v":1,"intent":"11111111-1111-4111-8111-111111111111","nonce":"22222222-2222-4222-8222-222222222222","exp":$exp,"sig":"abc"}"""

    @Test
    fun `valid payload parses`() {
        val parsed = QrLinkPayload.parse(payload())
        assertEquals("11111111-1111-4111-8111-111111111111", parsed?.intentId)
        assertEquals("22222222-2222-4222-8222-222222222222", parsed?.nonce)
        assertFalse(parsed!!.isExpired())
    }

    @Test
    fun `expired payload is detected locally`() {
        val parsed = QrLinkPayload.parse(payload(exp = 1L))
        assertTrue(parsed!!.isExpired(nowMs = 2_000L))
    }

    @Test
    fun `wrong version is rejected`() {
        assertNull(QrLinkPayload.parse(payload().replace("\"v\":1", "\"v\":2")))
    }

    @Test
    fun `non-link QR content is rejected`() {
        assertNull(QrLinkPayload.parse("https://example.com"))
        assertNull(QrLinkPayload.parse(""))
        assertNull(QrLinkPayload.parse(null))
        assertNull(QrLinkPayload.parse("{not json"))
    }

    @Test
    fun `missing fields are rejected`() {
        assertNull(QrLinkPayload.parse("""{"v":1,"intent":"x"}"""))
        assertNull(QrLinkPayload.parse("""{"v":1,"intent":"x","nonce":"y","exp":0}"""))
    }
}

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

class PiiSanitizerTest {

    @Test
    fun `redacts email addresses`() {
        val out = PiiSanitizer.sanitize("login failed for user@example.com retry")
        assertFalse(out.contains("user@example.com"))
        assertTrue(out.contains("[email]"))
    }

    @Test
    fun `redacts content uris`() {
        val out = PiiSanitizer.sanitize("Failed to import file: content://media/external/42")
        assertFalse(out.contains("content://"))
        assertTrue(out.contains("[uri]"))
    }

    @Test
    fun `redacts absolute storage paths`() {
        val out = PiiSanitizer.sanitize("Cannot open /storage/emulated/0/Download/scan.pdf")
        assertFalse(out.contains("/storage/emulated"))
        assertTrue(out.contains("[file]"))
    }

    @Test
    fun `redacts urls`() {
        val out = PiiSanitizer.sanitize("sync failed https://api.example.com/users/me boom")
        assertFalse(out.contains("api.example.com"))
        assertTrue(out.contains("[url]"))
    }

    @Test
    fun `redacts labeled secrets`() {
        val out = PiiSanitizer.sanitize("auth failed token: eyJhbGciOiJIUzI1NiJ9 payload")
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `redacts jwt-like tokens`() {
        val out = PiiSanitizer.sanitize(
            "bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig"
        )
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `keeps plain technical messages readable`() {
        val out = PiiSanitizer.sanitize("Failed to load LiteRT model")
        assertEquals("Failed to load LiteRT model", out)
    }

    @Test
    fun `truncates long messages`() {
        val out = PiiSanitizer.sanitize("x".repeat(500), maxLength = 200)
        assertEquals(200, out.length)
    }
}
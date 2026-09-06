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

package nopalito.app.push

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the AUTHENTICATED push-channel sanitizer contract:
 *
 *  - [sanitizePushText] preserves URLs and absolute paths (legitimate content
 *    from the x-admin-key panel) while still stripping scripts, HTML tags,
 *    control characters, credentials/bearer tokens, stack frames and SQL;
 *  - GOLDEN RULE: no sanitizer mode ever turns non-empty text into empty text
 *    (hostile-only input — a bare SQL statement or stack trace — is the only
 *    legitimate "" result);
 *  - [localizePushText] feeds link-preserving sanitized text to the translator
 *    and sanitizes its output again.
 *
 * Pure JVM tests; the translator is injected.
 */
class PushTextSanitizerTest {

    // ---- push channel keeps links and paths ----

    @Test
    fun urlsArePreservedForPush() {
        val text = "Descarga la app: https://nopalitoscan.org/app hoy"
        assertEquals(text, sanitizePushText(text))
        assertEquals("mas info www.ejemplo.com", sanitizePushText("mas info www.ejemplo.com"))
    }

    @Test
    fun absolutePathsArePreservedForPush() {
        assertEquals(
            "Ruta C:\\Users\\admin\\file.txt ahora",
            sanitizePushText("Ruta C:\\Users\\admin\\file.txt ahora"),
        )
        assertEquals(
            "revise /home/admin/app/config ahora",
            sanitizePushText("revise /home/admin/app/config ahora"),
        )
    }

    // ---- push channel still strips everything hostile ----

    @Test
    fun credentialsAreStillRemovedForPush() {
        val out = sanitizePushText("password=abc123 por favor reinicie")
        assertEquals("por favor reinicie", out)
        assertFalse(out.contains("abc123"))

        val bearer = sanitizePushText("Token Bearer eyJhbGciOiJIUzI1NiJ9 aqui")
        assertFalse(bearer.contains("eyJhbGci"))
    }

    @Test
    fun htmlAndScriptsAreStillStrippedForPush() {
        assertEquals("Hola Mundo", sanitizePushText("<b>Hola</b> <i>Mundo</i>"))
        assertEquals("Hola Mundo", sanitizePushText("Hola <script>alert('x')</script>Mundo"))
    }

    @Test
    fun sqlAndStackTracesAreStillRemovedForPush() {
        val sql = sanitizePushText("La consulta: SELECT * FROM users WHERE id=1; fin")
        assertFalse(sql.contains("SELECT"))
        assertTrue(sql.contains("fin"))

        val stack = sanitizePushText("Error\n    at com.app.Main.run(Main.kt:12)\nCaused by: boom")
        assertEquals("Error", stack)
    }

    @Test
    fun controlCharactersRemovedNewlinesKept() {
        assertEquals("ab\ncd", sanitizePushText("a\u0000b\nc\u0007d\u0001"))
    }

    // ---- golden rule: never turn non-empty into empty ----

    @Test
    fun urlOnlyTextIsNeverEmptiedStrictMode() {
        val out = sanitizeNotificationText("https://example.com")
        assertTrue("strict sanitizer must fall back instead of returning blank", out.isNotBlank())
    }

    @Test
    fun urlOnlyTextIsNeverEmptiedPushMode() {
        assertEquals(
            "https://example.com/download",
            sanitizePushText("https://example.com/download")
        )
    }

    @Test
    fun pathOnlyTextIsNeverEmptiedStrictMode() {
        assertTrue(sanitizeNotificationText("/etc/passwd").isNotBlank())
    }

    @Test
    fun hostileOnlyInputCanLegitimatelyBeEmpty() {
        // Credentials / SQL / scripts are stripped again by the minimal-clean
        // fallback: there is no legitimate content to preserve.
        assertEquals("", sanitizeNotificationText("Bearer eyJhbGciOiJIUzI1NiJ9.token"))
        assertEquals("", sanitizeNotificationText("SELECT * FROM users;"))
        assertEquals("", sanitizeNotificationText("<script>alert(1)</script>"))
    }

    @Test
    fun normalProseIsNeverRewrittenByTheGoldenRule() {
        val text = "Mantenimiento programado — visite https://status.example.com"
        assertEquals(text, sanitizePushText(text))
    }

    // ---- length cap ----

    @Test
    fun longPushTextIsCappedAtTwoHundred() {
        assertEquals(200, sanitizePushText("b".repeat(500)).length)
    }

    // ---- pipeline ----

    @Test
    fun pushPipelineFeedsLinksToTranslatorAndKeepsThem() = runBlocking {
        var translatedInput: String? = null
        val out = localizePushText("Visita https://example.com/promo hoy") {
            translatedInput = it
            it
        }
        assertTrue(translatedInput!!.contains("https://example.com/promo"))
        assertEquals("Visita https://example.com/promo hoy", out)
    }

    @Test
    fun pushPipelineBlankInputSkipsTranslator() = runBlocking {
        var called = false
        val out = localizePushText("   ") {
            called = true
            "should not be called"
        }
        assertEquals("", out)
        assertFalse(called)
    }

    @Test
    fun pushPipelineFailureFallsBackToSanitizedTextWithLinks() = runBlocking {
        // Identity translator == ML Kit failure/same-language path.
        val raw = "<b>Hola</b> https://example.com/repo"
        val out = localizePushText(raw) { it }
        assertEquals("Hola https://example.com/repo", out)
        assertFalse(out.contains("<b>"))
    }

    @Test
    fun pushPipelineSanitizesCompromisedOutputButKeepsLinks() = runBlocking {
        val out = localizePushText("texto") {
            "<b>Nuevo</b> password=123 mira https://ok.example/x"
        }
        assertFalse(out.contains("<b>"))
        assertFalse(out.contains("password=123"))
        assertTrue(out.contains("https://ok.example/x"))
    }
}
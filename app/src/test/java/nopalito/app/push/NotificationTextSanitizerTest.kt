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
import org.junit.Assert.*
import org.junit.Test

/**
 * Block: 8D — notification payload sanitization.
 *
 * Pure JVM tests for [sanitizeNotificationText] and the
 * `raw → sanitize → translate → sanitize final` pipeline
 * ([localizeNotificationText]) used by [NopalitoScanMessagingService]: null/blank
 * handling, HTML, URLs, credentials, paths, stack traces, SQL, control
 * characters, length cap, Unicode preservation, no-throw guarantee, and the
 * never-raw fallback (the translator is injected).
 */
class NotificationTextSanitizerTest {

    // ---- null / blank ----

    @Test
    fun nullYieldsEmpty() = runBlocking {
        assertEquals("", sanitizeNotificationText(null))
        assertEquals("", localizeNotificationText(null) { it })
    }

    @Test
    fun blankYieldsEmpty() {
        assertEquals("", sanitizeNotificationText(""))
        assertEquals("", sanitizeNotificationText("   \t\n  "))
    }

    @Test
    fun blankPipelineYieldsBlankWithoutCallingTranslator() = runBlocking {
        var called = false
        val out = localizeNotificationText("  ") {
            called = true
            "should not be called"
        }
        assertEquals("", out)
        assertFalse(called)
    }

    // ---- normal text ----

    @Test
    fun normalTextAndUnicodeArePreserved() {
        val text = "Servicio disponible — próximamente: á é í ó ú ñ ü ö"
        assertEquals(text, sanitizeNotificationText(text))
    }

    @Test
    fun proseContainingSelectAndErrorIsNotDestroyed() {
        val text = "Si hay un error, seleccione la opción correcta"
        assertEquals(text, sanitizeNotificationText(text))
    }

    // ---- HTML / script / control ----

    @Test
    fun htmlTagsAreStripped() {
        assertEquals("Hola Mundo", sanitizeNotificationText("<b>Hola</b> <i>Mundo</i>"))
    }

    @Test
    fun scriptAndStyleBlocksAreRemoved() {
        assertEquals("Hola Mundo", sanitizeNotificationText("Hola <script>alert('x')</script>Mundo"))
        assertEquals("Bien", sanitizeNotificationText("Bien <style>body{display:none}</style>"))
    }

    @Test
    fun controlCharactersAreRemovedKeepingNewlines() {
        assertEquals("ab\ncd", sanitizeNotificationText("a\u0000b\nc\u0007d\u0001"))
    }

    // ---- URLs / credentials / paths / stack / SQL ----

    @Test
    fun urlsAreRemoved() {
        assertEquals("Visite hoy", sanitizeNotificationText("Visite https://example.com/x?t=1 hoy"))
        assertEquals("mas info", sanitizeNotificationText("www.ejemplo.com mas info"))
    }

    @Test
    fun bearerTokenIsRemoved() {
        val out = sanitizeNotificationText("Token Bearer eyJhbGciOiJIUzI1NiJ9 aqui")
        assertEquals("Token aqui", out)
        assertFalse(out.contains("eyJhbGci"))
    }

    @Test
    fun credentialsAreRemoved() {
        assertEquals("por favor reinicie", sanitizeNotificationText("password=abc123 por favor reinicie"))
        assertEquals("intente luego", sanitizeNotificationText("apikey: k7X9 secret=zzz intente luego"))
    }

    @Test
    fun absolutePathsAreRemoved() {
        val win = sanitizeNotificationText("Ruta C:\\Users\\admin\\file.txt ahora")
        assertEquals("Ruta ahora", win)
        val posix = sanitizeNotificationText("revise /home/admin/app/config ahora")
        assertEquals("revise ahora", posix)
    }

    @Test
    fun stackTraceFramesAreRemoved() {
        val out = sanitizeNotificationText("Error\n    at com.app.Main.run(Main.kt:12)\nCaused by: boom")
        assertEquals("Error", out)
        assertFalse(out.contains("Main.kt"))
        assertFalse(out.contains("Caused by"))
    }

    @Test
    fun obviousSqlIsRemovedButProseKept() {
        val out = sanitizeNotificationText("La consulta: SELECT * FROM users WHERE id=1; fin")
        assertEquals("La consulta: fin", out)
        assertFalse(out.contains("SELECT"))
        assertEquals(
            "select the red button to continue",
            sanitizeNotificationText("select the red button to continue")
        )
    }

    // ---- length cap ----

    @Test
    fun lengthIsCappedAt200Characters() {
        val long = "a".repeat(300)
        val out = sanitizeNotificationText(long)
        assertEquals(200, out.length)
        val withUrl = "visita https://example.com/path " + "b".repeat(300)
        assertTrue(sanitizeNotificationText(withUrl).length <= 200)
    }

    // ---- never throws ----

    @Test
    fun hostileInputNeverThrows() {
        val hostile = listOf(
            null, "", "   ",
            "<b>Bold</b><script>alert(1)</script><style>x{}</style>",
            "https://evil.example/x?q=1 www.evil.net",
            "Bearer eyJhbGciOiJIUzI1NiJ9.token",
            "password=supersecret secret=abc123 apikey: k",
            "C:\\Windows\\System32\\config\\sam /usr/local/bin/backup.sh /etc/passwd",
            "at com.evil.Crash.main(Crash.java:42)\nCaused by: java.lang.RuntimeException: boom",
            "SELECT * FROM users; DROP TABLE users;",
            "a\u0000b\u0007c\u001bd",
            "x".repeat(1000),
        )
        for (text in hostile) {
            sanitizeNotificationText(text)
        }
    }

    // ---- pipeline: sanitize before and after translation ----

    @Test
    fun pipelineSanitizesBeforeTranslation() = runBlocking {
        var translatedInput: String? = null
        val out = localizeNotificationText("Mira https://evil.example/x Token Bearer abc123") {
            translatedInput = it
            "Mira"
        }
        assertEquals("Mira", out)
        // The translator only ever receives sanitized text.
        assertFalse(translatedInput!!.contains("evil"))
        assertFalse(translatedInput.contains("Bearer"))
    }

    @Test
    fun pipelineSanitizesTranslationOutput() = runBlocking {
        // The translator itself is compromised: its output must be sanitized.
        val out = localizeNotificationText("texto normal") {
            "<b>evil</b> password=123 https://x.com/leak\n    at com.bad.Main.main(Main.kt:1)"
        }
        assertFalse(out.contains("<b>"))
        assertFalse(out.contains("password"))
        assertFalse(out.contains("x.com"))
        assertFalse(out.contains("Main.kt"))
    }

    @Test
    fun translationFailureFallsBackToSanitizedNotRaw() = runBlocking {
        // ML Kit failure returns the input unchanged; the result must still be
        // the sanitized text, never the raw original.
        val raw = "<b>Hola</b> password=abc123 visita https://evil.example"
        val out = localizeNotificationText(raw) { it }
        assertNotEqualsSafe(raw, out)
        assertFalse(out.contains("<b>"))
        assertFalse(out.contains("evil"))
        assertFalse(out.contains("password=abc123"))
        assertTrue(out.isNotBlank())
    }

    @Test
    fun translationSuccessReturnsCleanText() = runBlocking {
        val out = localizeNotificationText("Mantenimiento programado") { "Scheduled maintenance" }
        assertEquals("Scheduled maintenance", out)
    }

    private fun assertNotEqualsSafe(a: String, b: String) {
        assertTrue("expected different strings: '$a' vs '$b'", a != b)
    }
}
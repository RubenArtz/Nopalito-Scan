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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Block: 8A — defensive sanitizer + localization pipeline for maintenance
 * banner text (`status.title` / `status.message` / `status.reason`).
 *
 * Pure JVM tests: [sanitizeMaintenanceText] / [localizeMaintenanceText] /
 * [renderMaintenanceText] need no Android Context. The translator is injected,
 * so the "translation fails -> sanitized fallback" path is exercised with a
 * fake translator that returns its input unchanged (what ML Kit does on
 * failure / same-language).
 */
class MaintenanceTextSanitizerTest {

    // ---- null / blank ----

    @Test
    fun nullYieldsEmpty() = runBlocking {
        assertEquals("", sanitizeMaintenanceText(null))
        assertEquals("", localizeMaintenanceText(null) { it })
        assertEquals("", renderMaintenanceText("", null))
    }

    @Test
    fun blankYieldsEmpty() {
        assertEquals("", sanitizeMaintenanceText(""))
        assertEquals("", sanitizeMaintenanceText("   \t\n  "))
    }

    // ---- normal text is preserved ----

    @Test
    fun normalTextIsUnchanged() {
        val text = "Mantenimiento programado para mejorar el servicio"
        assertEquals(text, sanitizeMaintenanceText(text))
    }

    @Test
    fun proseContainingSelectAndErrorIsNotDestroyed() {
        // A sentence with "select"/"error" and no SQL semicolon / no stack frame.
        val text = "Si hay un error, seleccione la opción correcta"
        assertEquals(text, sanitizeMaintenanceText(text))
    }

    @Test
    fun unicodeAndAccentsArePreserved() {
        val text = "Mantenimiento — próximo: á é í ó ú ñ ¿Qué? Prüfung ü ö"
        assertEquals(text, sanitizeMaintenanceText(text))
    }

    @Test
    fun newlinesArePreserved() {
        val text = "primera línea\nsegunda línea\ntercera"
        assertEquals(text, sanitizeMaintenanceText(text))
    }

    // ---- HTML ----

    @Test
    fun htmlTagsAreStripped() {
        assertEquals(
            "Hola Mundo",
            sanitizeMaintenanceText("<b>Hola</b> <i>Mundo</i>")
        )
    }

    @Test
    fun scriptAndStyleBlocksAreRemoved() {
        val text = "Hola <script>alert('x')</script>Mundo"
        assertEquals("Hola Mundo", sanitizeMaintenanceText(text))
    }

    // ---- control characters ----

    @Test
    fun controlCharactersAreRemovedKeepingNewlines() {
        assertEquals(
            "ab\ncd",
            sanitizeMaintenanceText("a\u0000b\nc\u0007d\u0001")
        )
    }

    // ---- URLs ----

    @Test
    fun urlsAreRemoved() {
        assertEquals(
            "Visite hoy",
            sanitizeMaintenanceText("Visite https://example.com/x?t=1 hoy")
        )
        assertEquals("mas info", sanitizeMaintenanceText("www.ejemplo.com mas info"))
    }

    // ---- credentials ----

    @Test
    fun bearerTokenIsRemoved() {
        val out = sanitizeMaintenanceText("Token Bearer eyJhbGciOiJIUzI1NiJ9 aqui")
        assertEquals("Token aqui", out)
        assertTrue(!out.contains("eyJhbGci"))
    }

    @Test
    fun secretAssignmentIsRemoved() {
        assertEquals(
            "por favor reinicie",
            sanitizeMaintenanceText("password=abc123 por favor reinicie")
        )
    }

    // ---- paths ----

    @Test
    fun windowsAbsolutePathIsRemoved() {
        val out = sanitizeMaintenanceText("Ruta C:\\Users\\admin\\file.txt ahora")
        assertEquals("Ruta ahora", out)
        assertTrue(!out.contains("Users"))
    }

    @Test
    fun posixAbsolutePathIsRemoved() {
        val out = sanitizeMaintenanceText("revise /home/admin/app/config ahora")
        assertEquals("revise ahora", out)
        assertTrue(!out.contains("/home/admin"))
    }

    // ---- stack traces ----

    @Test
    fun stackTraceFramesAndCausedByAreRemoved() {
        val text = "Error\n    at com.app.Main.run(Main.kt:12)\nCaused by: boom interno"
        val out = sanitizeMaintenanceText(text)
        assertEquals("Error", out)
        assertTrue(!out.contains("Main.kt"))
        assertTrue(!out.contains("Caused by"))
    }

    // ---- SQL ----

    @Test
    fun obviousSqlStatementIsRemoved() {
        val out = sanitizeMaintenanceText("La consulta: SELECT * FROM users WHERE id=1; fin")
        assertEquals("La consulta: fin", out)
        assertTrue(!out.contains("SELECT"))
    }

    @Test
    fun proseWithSelectButNoSemicolonIsKept() {
        assertEquals(
            "select the red button to continue",
            sanitizeMaintenanceText("select the red button to continue")
        )
    }

    // ---- length cap ----

    @Test
    fun longTextIsCappedAtTwoHundred() {
        val out = sanitizeMaintenanceText("a".repeat(500))
        assertTrue(out.length <= 200)
        assertEquals(200, out.length)
    }

    // ---- combination ----

    @Test
    fun combinedThreatsAreNeutralized() {
        val out = sanitizeMaintenanceText(
            "Hola <b>https://evil.com/x</b> Bearer abc123 SELECT 1; " +
                    "path /home/user/secret SQL: DROP TABLE t; fin"
        )
        assertTrue(!out.contains("https://evil.com"))
        assertTrue(!out.contains("Bearer"))
        assertTrue(!out.contains("abc123"))
        assertTrue(!out.contains("SELECT"))
        assertTrue(!out.contains("DROP"))
        assertTrue(!out.contains("/home/user"))
        assertTrue(!out.contains("</b>"))
        assertTrue(!out.contains("<"))
    }

    // ---- pipeline: translation failure -> sanitized fallback ----

    @Test
    fun translationFailureReturnsSanitizedTextNotRaw() = runBlocking {
        // Fake translator returns its input unchanged (ML Kit failure path).
        val out = localizeMaintenanceText("raw <b>text</b> https://x.com") { it }
        assertEquals("raw text", out)
        assertTrue(!out.contains("https://x.com"))
        assertTrue(!out.contains("<b>"))
    }

    @Test
    fun translationSanitizesInputBeforeAndAfterTranslate() = runBlocking {
        // The translator receives only the sanitized "Mensaje" (URL/token stripped
        // before the call) and its output is sanitized again before return.
        val out = localizeMaintenanceText("Mensaje Bearer tok https://x.com") { t -> "PRE" + t + "POST" }
        assertEquals("PREMensajePOST", out)
        assertTrue(!out.contains("Bearer"))
        assertTrue(!out.contains("https://x.com"))
    }

    @Test
    fun blankInputSkipsTranslation() = runBlocking {
        var called = false
        val out = localizeMaintenanceText("   ") { called = true; "x" }
        assertEquals("", out)
        assertTrue(!called)
    }

    @Test
    fun renderFallsBackToSanitizedRaw() {
        assertEquals("raw text", renderMaintenanceText("", "<b>raw</b> text"))
        assertEquals("kept", renderMaintenanceText("kept", "<b>raw</b>"))
        assertEquals("raw", renderMaintenanceText("", "<b>raw</b> https://x.com"))
    }

    // ---- independence of fields / incomplete status ----

    @Test
    fun titleMessageReasonAreSanitizedIndependently() = runBlocking {
        val title = localizeMaintenanceText("Título <b>limpio</b>") { it }
        val message = localizeMaintenanceText("Mensaje con https://x.com") { it }
        val reason = localizeMaintenanceText("Motivo sin cambios") { it }
        assertEquals("Título limpio", title)
        assertEquals("Mensaje con", message)
        assertEquals("Motivo sin cambios", reason)
    }

    @Test
    fun incompleteStatusYieldsEmptySafely() = runBlocking {
        assertEquals("", localizeMaintenanceText(null) { it })
        assertEquals("", localizeMaintenanceText("") { it })
        assertEquals("", renderMaintenanceText("", null))
        assertEquals("", renderMaintenanceText("", ""))
    }
}
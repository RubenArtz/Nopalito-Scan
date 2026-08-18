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
import org.junit.Test
import java.util.*

/**
 * Block: 8C notification-regression audit — JVM tests for the shared
 * [TranslationHelper] used by the push flow.
 *
 * These tests run without Robolectric/ML Kit: on a plain JVM the ML Kit
 * client cannot be created, so [TranslationHelper.translate] exercises exactly
 * the failure path and must return the original text (the documented fallback
 * when translation is not possible). This is deterministic and documents the
 * CURRENT behavior of the notification flow (original server text as fallback,
 * not sanitized) without modifying production.
 *
 * Device-language resolution is tested with [Locale.setDefault] for the five
 * supported device locales (`es-419 -> es`, `pt-BR -> pt`, `de-DE -> de`,
 * `fr-FR -> fr`, `en-US -> en`).
 */
@Suppress("DEPRECATION")
class TranslationHelperTest {

    // ---- Device language normalization (the five supported locales) ----

    @Test
    fun es419DeviceLanguageIsEs() {
        Locale.setDefault(Locale("es", "419"))
        assertEquals("es", TranslationHelper.getDeviceLanguageCode())
    }

    @Test
    fun ptBRDeviceLanguageIsPt() {
        Locale.setDefault(Locale("pt", "BR"))
        assertEquals("pt", TranslationHelper.getDeviceLanguageCode())
    }

    @Test
    fun deDEDeviceLanguageIsDe() {
        Locale.setDefault(Locale("de", "DE"))
        assertEquals("de", TranslationHelper.getDeviceLanguageCode())
    }

    @Test
    fun frFRDeviceLanguageIsFr() {
        Locale.setDefault(Locale("fr", "FR"))
        assertEquals("fr", TranslationHelper.getDeviceLanguageCode())
    }

    @Test
    fun enUSDeviceLanguageIsEn() {
        Locale.setDefault(Locale("en", "US"))
        assertEquals("en", TranslationHelper.getDeviceLanguageCode())
    }

    // ---- Blank / null safety ----

    @Test
    fun blankTextComesBackBlank() = runBlocking {
        Locale.setDefault(Locale("en", "US"))
        assertEquals("", TranslationHelper.translate(""))
        assertEquals("   ", TranslationHelper.translate("   "))
    }

    // ---- Translation failure fallback (no ML Kit on JVM) ----

    @Test
    fun translationFailureFallsBackToOriginalEnglishText() = runBlocking {
        Locale.setDefault(Locale("en", "US"))
        val text = "Scheduled maintenance will start soon."
        assertEquals(text, TranslationHelper.translate(text))
    }

    @Test
    fun translationFailureFallsBackToOriginalSpanishText() = runBlocking {
        Locale.setDefault(Locale("en", "US"))
        val text = "Mantenimiento programado para mejorar el servicio"
        assertEquals(text, TranslationHelper.translate(text))
    }

    // ---- Hostile payload text: current behavior is "unchanged, no crash" ----

    @Test
    fun hostilePayloadsDoNotCrashAndFallBackUnchanged() = runBlocking {
        Locale.setDefault(Locale("en", "US"))
        val hostile = listOf(
            "<b>Bold</b><script>alert(1)</script>",
            "Check https://evil.example/x?q=1 now",
            "Bearer eyJhbGciOiJIUzI1NiJ9.token",
            "password=supersecret secret=abc123",
            "C:\\Windows\\System32\\config\\sam",
            "/usr/local/bin/backdoor.sh",
            "at com.evil.Crash.main(Crash.java:42)",
            "Caused by: java.lang.RuntimeException: boom",
            "SELECT * FROM users WHERE id=1; DROP TABLE users;",
        )
        for (text in hostile) {
            assertEquals(text, TranslationHelper.translate(text))
        }
    }

    // ---- Same-language fast path ----

    @Test
    fun englishTextWithEnglishDeviceIsReturnedVerbatim() = runBlocking {
        Locale.setDefault(Locale("en", "US"))
        val text = "The service will be back soon"
        // Device language is "en" and the keyword heuristic detects English,
        // so the fast path returns the text without invoking ML Kit.
        assertEquals(text, TranslationHelper.translate(text))
    }
}
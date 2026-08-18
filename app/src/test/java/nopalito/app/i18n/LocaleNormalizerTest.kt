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

package nopalito.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [LocaleNormalizer].
 *
 * Every case asserts BOTH catalogLanguage() and intlLanguageTag(), and the
 * three public methods are expected to stay coherent (see the coherence test).
 */
class LocaleNormalizerTest {

    private fun assertNormalizes(input: String?, catalog: String, intl: String) {
        assertEquals("catalogLanguage('$input')", catalog, LocaleNormalizer.catalogLanguage(input))
        assertEquals("intlLanguageTag('$input')", intl, LocaleNormalizer.intlLanguageTag(input))
        assertEquals(
            "normalizeForBackend('$input') must equal intlLanguageTag",
            intl,
            LocaleNormalizer.normalizeForBackend(input),
        )
    }

    @Test
    fun `approved matrix is honored`() {
        assertNormalizes("es-419", "es", "es-419")
        assertNormalizes("en-US", "en", "en-US")
        assertNormalizes("de-DE", "de", "de-DE")
        assertNormalizes("fr-FR", "fr", "fr-FR")
        assertNormalizes("pt-BR", "pt", "pt-BR")
        assertNormalizes("fr-CA", "fr", "fr")
        assertNormalizes("es-CO", "es", "es")
        assertNormalizes("zh", "es", "es")
        assertNormalizes("", "es", "es")
        assertNormalizes("und", "es", "es")
        assertNormalizes("invalid tag!", "es", "es")
    }

    @Test
    fun `underscores are converted to hyphens`() {
        assertNormalizes("pt_BR", "pt", "pt-BR")
        assertNormalizes("es_419", "es", "es-419")
        assertNormalizes("en_US", "en", "en-US")
    }

    @Test
    fun `whitelisted regional variants are preserved`() {
        assertNormalizes("es-MX", "es", "es-MX")
        assertNormalizes("es-ES", "es", "es-ES")
        assertNormalizes("en-GB", "en", "en-GB")
        assertNormalizes("pt-PT", "pt", "pt-PT")
    }

    @Test
    fun `unlisted variant with supported base reduces to the base`() {
        assertNormalizes("fr-CA", "fr", "fr")
        assertNormalizes("es-CO", "es", "es")
        assertNormalizes("de-AT", "de", "de")
        assertNormalizes("pt-AO", "pt", "pt")
    }

    @Test
    fun `unsupported base falls back to es`() {
        assertNormalizes("zh", "es", "es")
        assertNormalizes("sv-SE", "es", "es")
        assertNormalizes("ja", "es", "es")
        assertNormalizes("zh-Hans-CN", "es", "es")
    }

    @Test
    fun `und and empty values fall back to es`() {
        assertNormalizes("und", "es", "es")
        assertNormalizes("", "es", "es")
        assertNormalizes("   ", "es", "es")
        assertNormalizes("und-419", "es", "es")
    }

    @Test
    fun `null falls back to es`() {
        assertNormalizes(null, "es", "es")
    }

    @Test
    fun `normalization is case insensitive`() {
        assertNormalizes("PT-BR", "pt", "pt-BR")
        assertNormalizes("pt-br", "pt", "pt-BR")
        assertNormalizes("Pt-bR", "pt", "pt-BR")
        assertNormalizes("ES-419", "es", "es-419")
        assertNormalizes("EN-us", "en", "en-US")
        assertNormalizes("FR-fr", "fr", "fr-FR")
        assertNormalizes("DE-de", "de", "de-DE")
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertNormalizes(" pt-BR ", "pt", "pt-BR")
        assertNormalizes("\tfr-CA\n", "fr", "fr")
        assertNormalizes("  es  ", "es", "es")
        assertNormalizes("es 419", "es", "es")
    }

    @Test
    fun `malformed tags fall back to es`() {
        assertNormalizes("es--419", "es", "es")
        assertNormalizes("es-", "es", "es")
        assertNormalizes("-", "es", "es")
        assertNormalizes("es@419", "es", "es")
        assertNormalizes("es_", "es", "es")
        assertNormalizes("--", "es", "es")
        assertNormalizes("e", "es", "es")
        assertNormalizes("123", "es", "es")
        assertNormalizes("es 419", "es", "es")
    }

    @Test
    fun `never throws on arbitrary garbage`() {
        val garbage = listOf(
            null,
            "",
            " ",
            "!!!",
            "es\n",
            "éâ€”",
            "\uD83C\uDDFA\uD83C\uDDF8",
            "a".repeat(500),
            "es-" + "x".repeat(300),
            "es-419-extra-long-subtag-here",
            "pt--BR",
            "en_US_extra",
        )
        for (input in garbage) {
            try {
                LocaleNormalizer.normalizeForBackend(input)
                LocaleNormalizer.catalogLanguage(input)
                LocaleNormalizer.intlLanguageTag(input)
            } catch (t: Throwable) {
                throw AssertionError("must never throw for input '$input'", t)
            }
        }
        assertTrue("loop completed without exceptions", true)
    }

    @Test
    fun `base languages have no regression`() {
        assertNormalizes("es", "es", "es")
        assertNormalizes("en", "en", "en")
        assertNormalizes("de", "de", "de")
        assertNormalizes("fr", "fr", "fr")
        assertNormalizes("pt", "pt", "pt")
    }

    @Test
    fun `three methods stay coherent`() {
        val inputs = listOf(
            "es-419", "pt-BR", "en-US", "de-DE", "fr-FR",
            "fr-CA", "es-CO", "zh", "", "und", "PT_br", null,
        )
        for (input in inputs) {
            val backend = LocaleNormalizer.normalizeForBackend(input)
            val intl = LocaleNormalizer.intlLanguageTag(input)
            val catalog = LocaleNormalizer.catalogLanguage(input)
            assertEquals("backend tag equals intl tag for '$input'", intl, backend)
            assertEquals(
                "catalog language is the base of the intl tag for '$input'",
                intl.substringBefore('-'),
                catalog,
            )
            assertTrue(
                "catalog language is one of es/en/de/fr/pt for '$input'",
                catalog in setOf("es", "en", "de", "fr", "pt")
            )
        }
    }
}
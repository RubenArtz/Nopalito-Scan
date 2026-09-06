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

import nopalito.app.i18n.LocaleNormalizer.FALLBACK
import nopalito.app.i18n.LocaleNormalizer.canonicalTag
import nopalito.app.i18n.LocaleNormalizer.catalogLanguage
import nopalito.app.i18n.LocaleNormalizer.intlLanguageTag
import nopalito.app.i18n.LocaleNormalizer.normalizeForBackend
import java.util.Locale

/**
 * Normalizes a locale value for the Nopalito Scan Cloud backend.
 *
 * Contract (approved, mirrors the backend whitelist in `src/i18n/locale.js`):
 *
 * ```
 * Input      catalogLanguage()   intlLanguageTag()
 * es-419     es                  es-419
 * en-US      en                  en-US
 * de-DE      de                  de-DE
 * fr-FR      fr                  fr-FR
 * pt-BR      pt                  pt-BR
 * fr-CA      fr                  fr
 * es-CO      es                  es
 * zh         es                  es
 * (empty)    es                  es
 * und        es                  es
 * (invalid)  es                  es
 * ```
 *
 * Rules, in order:
 *  1. The value is trimmed, lowercased and `_` is converted to `-` (`pt_BR` -> `pt-BR`).
 *  2. It must match a well-formed BCP-47 shape (base + optional subtags).
 *  3. A whitelisted tag is preserved exactly in its canonical form.
 *  4. A non-whitelisted variant reduces to its base language when that base is
 *     supported (`fr-CA` -> `fr`, `es-CO` -> `es`) — the backend's explicit rule.
 *  5. Anything else (unsupported base, `zh`, `und`, empty, malformed input)
 *     falls back to `es` (the backend's default).
 *
 * The three public functions derive from ONE private resolution ([canonicalTag]),
 * so the value sent to the backend, the catalog language and the Intl tag can
 * never disagree:
 *  - [normalizeForBackend] and [intlLanguageTag] return the same canonical tag
 *    (the backend accepts every whitelisted tag and reduces the rest itself).
 *  - [catalogLanguage] returns the base of that tag (`es`, `en`, `de`, `fr`, `pt`).
 *
 * No caching is used, on purpose: the app language can change mid-session
 * (see [AppLocaleOverride], updated before the Activity is recreated), so the
 * functions are pure and stateless — the caller resolves the CURRENT value on
 * every request and the interceptor re-evaluates it per request.
 *
 * Never throws: every failure path (null, blank, malformed input, `Locale`
 * exceptions) resolves to `es`. Uses [Locale.forLanguageTag] (API 21+; the
 * project's minSdk is 26; the same JVM semantics apply in unit tests).
 */
object LocaleNormalizer {

    /** Global fallback, matching the backend default. */
    const val FALLBACK = "es"

    /** Base catalog languages supported by the backend. */
    private val SUPPORTED_BASES = setOf("es", "en", "de", "fr", "pt")

    /**
     * Backend whitelist (single source of truth). Keys are lowercased inputs,
     * values are the canonical tags actually sent to the backend.
     */
    private val WHITELISTED_TAGS = mapOf(
        "es" to "es",
        "es-419" to "es-419",
        "es-mx" to "es-MX",
        "es-es" to "es-ES",
        "en" to "en",
        "en-us" to "en-US",
        "en-gb" to "en-GB",
        "de" to "de",
        "de-de" to "de-DE",
        "fr" to "fr",
        "fr-fr" to "fr-FR",
        "pt" to "pt",
        "pt-pt" to "pt-PT",
        "pt-br" to "pt-BR",
    )

    /** Well-formed BCP-47 shape: 2-3 letter base + optional 2-8 char subtags. */
    private val TAG_SHAPE = Regex("^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$")

    /**
     * Canonical tag to send to the backend, or null when the value must fall
     * back to [FALLBACK].
     */
    private fun canonicalTag(value: String?): String? {
        if (value == null) return null
        val raw = value.trim().lowercase(Locale.ROOT).replace('_', '-')
        if (raw.isEmpty() || !TAG_SHAPE.matches(raw)) return null
        return try {
            val tag = Locale.forLanguageTag(raw).toLanguageTag().lowercase(Locale.ROOT)
            WHITELISTED_TAGS[tag]
                ?: tag.substringBefore('-').takeIf { it in SUPPORTED_BASES }
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Canonical tag for the headers; equals [intlLanguageTag]. */
    fun normalizeForBackend(value: String?): String = canonicalTag(value) ?: FALLBACK

    /** Base catalog language (`es`, `en`, `de`, `fr` or `pt`). */
    fun catalogLanguage(value: String?): String =
        canonicalTag(value)?.substringBefore('-') ?: FALLBACK

    /** Canonical tag preserved for whitelisted variants, base otherwise. */
    fun intlLanguageTag(value: String?): String = canonicalTag(value) ?: FALLBACK
}
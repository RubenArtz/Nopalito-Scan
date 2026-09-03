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

import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.push.TranslationHelper.ENGLISH_MARKERS
import java.util.Locale

/**
 * On-device translation using Google ML Kit.
 *
 * Translates text from any supported source language to the app's selected
 * language, so server text (written by the admin in one language) always
 * appears in the user's language:
 * - Free, no API key required.
 * - Works offline once the language models are downloaded.
 * - Detects the source language with ML Kit Language Identification (no
 *   "which language did the admin type?" guess), falling back to a keyword
 *   heuristic while the identification model is unavailable.
 * - Caches translations with a key that includes BOTH source and target
 *   languages, so changing the app/device language can never surface a stale
 *   translation for another language.
 * - Failures are NEVER cached: a transient error (model not yet downloaded,
 *   offline, blank model output) stays retryable for the next message.
 *
 * Usage (suspend, call from a coroutine — never blocks the main thread):
 *   val translated = TranslationHelper.translate("Scheduled maintenance")
 *   // Returns "Mantenimiento programado" if the app language is Spanish
 */
object TranslationHelper {

    private const val TAG = "TranslationHelper"
    private const val CACHE_MAX_SIZE = 100

    /** Global fallback when no language can be resolved (product base). */
    private const val DEFAULT_LANGUAGE_CODE = "en"

    /** LRU cache: "target>source:text" → translated text. */
    private val cache = LruCache<String, String>(CACHE_MAX_SIZE)

    /**
     * Returns the BCP-47 language code translations should target.
     *
     * Resolution order:
     *  1. The in-app language selection ([AppLocaleOverride.locale]) — the
     *     notifications must match what the user picked in the app, not the
     *     system-wide locale;
     *  2. [Locale.getDefault] when no override is resolvable;
     *  3. English (`en`) as the final product fallback.
     */
    fun getDeviceLanguageCode(): String {
        val overrideLanguage = AppLocaleOverride.locale.language.takeIf { it.isNotBlank() }
        val language = overrideLanguage
            ?: Locale.getDefault().language.takeIf { it.isNotBlank() }
        return language ?: DEFAULT_LANGUAGE_CODE
    }

    /**
     * Translates [text] from [sourceLangHint] (when the sender knows it) or
     * from a language detected on-device, to the app's selected language.
     *
     * Suspends while ML Kit works; safe to call from any background coroutine.
     * Returns the original text unchanged whenever translation is not possible
     * OR yields an empty result (unsupported pair, model unavailable, network
     * error, blank model output). Failure results are never cached, so the
     * next message retries instead of being stuck untranslated.
     */
    suspend fun translate(text: String, sourceLangHint: String? = null): String {
        if (text.isBlank()) return text

        val deviceLang = getDeviceLanguageCode()
        val sourceLang = sourceLangHint ?: identifySourceLanguage(text)

        // Key includes BOTH languages: a cached value is only valid for this
        // exact source→target pair, so switching languages never reuses it.
        val cacheKey = "$deviceLang>$sourceLang:$text"
        cache.get(cacheKey)?.let { return it }

        if (sourceLang == deviceLang) {
            return text
        }

        return try {
            val source = TranslateLanguage.fromLanguageTag(sourceLang)
                ?: return text.also {
                    Log.w(TAG, "Unsupported source language [$sourceLang]; returning original")
                }
            val target = TranslateLanguage.fromLanguageTag(deviceLang)
                ?: return text.also {
                    Log.w(TAG, "Unsupported target language [$deviceLang]; returning original")
                }

            if (source == target) {
                return text
            }

            val translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
            translator.use { translator ->
                // Default DownloadConditions: NO wifi requirement, so the
                // model can download on mobile data too (otherwise devices on
                // cellular data silently kept showing untranslated text).
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                val translated = translator.translate(text).await()
                if (translated.isBlank()) {
                    // Never cache or render a blank "successful" translation:
                    // the sanitized original is the visible fallback.
                    Log.w(TAG, "Translation returned blank; using source (${text.length} chars)")
                    return text
                }
                Log.i(
                    TAG,
                    "translated [$sourceLang->$deviceLang]: '${text.take(40)}' -> '${
                        translated.take(40)
                    }'"
                )
                cache.put(cacheKey, translated)
                translated
            }
        } catch (e: Exception) {
            Log.w(TAG, "Translation failed [$sourceLang->$deviceLang]: ${e.message}")
            text
        }
    }

    /**
     * Identifies the source language of [text] with ML Kit Language
     * Identification. Falls back to a keyword heuristic while the
     * identification model is not yet downloaded / offline.
     */
    private suspend fun identifySourceLanguage(text: String): String {
        return try {
            // The identification model (~300KB) is downloaded automatically on
            // the first call; without network it throws and we fall back to the
            // keyword heuristic below.
            val identifier = LanguageIdentification.getClient(
                LanguageIdentificationOptions.Builder()
                    .setConfidenceThreshold(0.5f)
                    .build()
            )
            val code = identifier.identifyLanguage(text).await()
            if (code == "und") detectSourceLanguageHeuristic(text) else code
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Language identification failed: ${e.message}")
            detectSourceLanguageHeuristic(text)
        }
    }

    /**
     * Keyword heuristic used only while the ML Kit identification model is
     * unavailable. Scores every language by matching markers (a real text
     * usually hits several) instead of checking one list in order, which
     * misdetected e.g. French text containing "service"/"maintenance" as
     * English because those were checked first.
     *
     * When no markers match at all, falls back to Spanish: this app's admin
     * panel is Spanish-first, and English text essentially always contains a
     * stop-word from [ENGLISH_MARKERS], so an unmatched text is far more
     * likely to be Spanish than anything else.
     */
    private fun detectSourceLanguageHeuristic(text: String): String {
        val lower = text.lowercase()
        val scored = listOf(
            "en" to ENGLISH_MARKERS.count { lower.contains(it) },
            "es" to SPANISH_MARKERS.count { lower.contains(it) },
            "pt" to PORTUGUESE_MARKERS.count { lower.contains(it) },
            "de" to GERMAN_MARKERS.count { lower.contains(it) },
            "fr" to FRENCH_MARKERS.count { lower.contains(it) },
        ).sortedWith(
            compareByDescending<Pair<String, Int>> { it.second }
                .thenBy { it.first }
        )
        val (lang, hits) = scored.first()
        return if (hits > 0) lang else "es"
    }

    private val ENGLISH_MARKERS = listOf(
        "scheduled", "maintenance", "service", "your", "the ", "you can", "will be", "again",
    )

    private val SPANISH_MARKERS = listOf(
        "mantenimiento", "servicio", "programado", "usted", "podrás", "nuevamente", "motivo",
    )

    private val PORTUGUESE_MARKERS = listOf(
        "manutenção", "serviço", "programado", "novamente", "motivo",
    )

    private val GERMAN_MARKERS = listOf(
        "wartung", "dienst", "geplant", "wieder", "grund",
    )

    private val FRENCH_MARKERS = listOf(
        "maintenance", "service", "prévu", "nouvelle", "à nouveau", "nouvellement",
    )
}
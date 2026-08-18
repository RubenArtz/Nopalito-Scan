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

import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import nopalito.app.push.TranslationHelper.ENGLISH_MARKERS
import java.util.*

/**
 * On-device translation using Google ML Kit.
 *
 * Translates text from any supported source language to the user's device
 * language, so server text (written by the admin in one language) always
 * appears in the phone's language:
 * - Free, no API key required.
 * - Works offline once the language models are downloaded.
 * - Detects the source language with ML Kit Language Identification (no
 *   "which language did the admin type?" guess), falling back to a keyword
 *   heuristic while the identification model is unavailable.
 * - Caches translations to avoid re-translating identical text.
 *
 * Usage (suspend, call from a coroutine — never blocks the main thread):
 *   val translated = TranslationHelper.translate("Scheduled maintenance")
 *   // Returns "Mantenimiento programado" if device language is Spanish
 */
object TranslationHelper {

    private const val TAG = "TranslationHelper"
    private const val CACHE_MAX_SIZE = 100

    /** LRU cache: source text → translated text. Avoids re-translating the same strings. */
    private val cache = LruCache<String, String>(CACHE_MAX_SIZE)

    /**
     * Returns the BCP-47 language code of the user's device.
     * Falls back to "en" if unavailable.
     */
    fun getDeviceLanguageCode(): String {
        val locale = Locale.getDefault()
        return locale.language // e.g. "es", "en", "pt", "fr", "de"
    }

    /**
     * Translates [text] from [sourceLangHint] (when the sender knows it) or
     * from a language detected on-device, to the device's language.
     *
     * Suspends while ML Kit works; safe to call from any background coroutine.
     * Returns the original text unchanged when translation is not possible
     * (unsupported pair, model unavailable, network error).
     */
    suspend fun translate(text: String, sourceLangHint: String? = null): String {
        if (text.isBlank()) return text
        cache.get(text)?.let { return it }

        val deviceLang = getDeviceLanguageCode()
        val sourceLang = sourceLangHint ?: identifySourceLanguage(text)

        if (sourceLang == deviceLang) {
            cache.put(text, text)
            return text
        }

        return try {
            val source = TranslateLanguage.fromLanguageTag(sourceLang)
                ?: return text.also { cache.put(text, text) }
            val target = TranslateLanguage.fromLanguageTag(deviceLang)
                ?: return text.also { cache.put(text, text) }

            if (source == target) {
                cache.put(text, text)
                return text
            }

            val translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
            try {
                // Default DownloadConditions: NO wifi requirement, so the
                // model can download on mobile data too (otherwise devices on
                // cellular data silently kept showing untranslated text).
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                val translated = translator.translate(text).await()
                android.util.Log.i(TAG, "translated [$source->$target]: '${text.take(40)}' -> '${translated.take(40)}'")
                cache.put(text, translated)
                translated
            } finally {
                translator.close()
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Translation failed [$sourceLang->$deviceLang]: ${e.message}")
            text.also { cache.put(text, text) }
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
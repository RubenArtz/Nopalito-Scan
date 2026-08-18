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

import java.util.*

/**
 * Central, single source of truth for every language the app can be displayed in.
 *
 * The app works internally with standard language codes and [Locale]s. The flag
 * is the native emoji of the representative country (rendered by the system with
 * the real flag design) and is used only as a visual hint — it is never parsed or
 * used to decide the effective locale.
 *
 * To add a new language:
 *  1. Add the entry below (flag emoji included, [enabled] = true when ready).
 *  2. Create the matching resources folder (e.g. `values-pt-rBR/strings.xml`)
 *     and translate every key there.
 *  3. The welcome screen and the Settings language picker pick the list up
 *     automatically from [supported] — no composable change is required.
 *
 * Visual note (es-419): the icon is the Mexican flag as a representative
 * reference of Latin America. It is deliberately NOT the Spanish (Spain) flag and
 * is completely decoupled from the real code `es-419` — it only labels the option.
 */
enum class AppLanguage(
    val code: String,
    val nativeName: String,
    val locale: Locale,
    /** Native emoji flag (e.g. 🇺🇸) rendered by the system with the real design. */
    val flag: String,
    val enabled: Boolean = true,
) {
    ENGLISH(
        code = "en",
        nativeName = "English",
        locale = Locale.forLanguageTag("en"),
        flag = "🇺🇸",
    ),
    SPANISH_LATAM(
        code = "es-419",
        nativeName = "Español latino",
        locale = Locale.forLanguageTag("es-419"),
        flag = "🇲🇽",
    ),
    PORTUGUESE_BRAZIL(
        code = "pt-BR",
        nativeName = "Português (Brasil)",
        locale = Locale.forLanguageTag("pt-BR"),
        flag = "🇧🇷",
    ),
    FRENCH(
        code = "fr",
        nativeName = "Français",
        locale = Locale.forLanguageTag("fr"),
        flag = "🇫🇷",
    ),
    GERMAN(
        code = "de",
        nativeName = "Deutsch",
        locale = Locale.forLanguageTag("de"),
        flag = "🇩🇪",
    ),
    ;

    /** Languages currently available to the user. */
    val supported: Boolean get() = enabled

    companion object {
        /** The list of languages shown in the pickers (enabled only). */
        val supported: List<AppLanguage> = entries.filter { it.enabled }

        /** Global fallback for the whole app. */
        val default: AppLanguage = ENGLISH

        /**
         * Maps a persisted language code to its [AppLanguage]. Unknown, disabled
         * or missing codes fall back to [default] (English).
         */
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code == code && it.enabled } ?: default

        /**
         * Auto-detects the language from the device locale.
         *  - Spanish / any Spanish variant → [SPANISH_LATAM]
         *  - English → [ENGLISH]
         *  - anything else → [default] (English)
         */
        fun detect(): AppLanguage {
            val language = Locale.getDefault().language.lowercase()
            return when (language) {
                "es" -> SPANISH_LATAM
                "en" -> ENGLISH
                else -> default
            }
        }
    }
}
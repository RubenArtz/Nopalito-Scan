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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Persists the user's language choice through DataStore Preferences.
 *
 * Keys stored:
 *  - `language_selected` → true once the user completed the welcome/onboarding step.
 *  - `selected_language` → the code of the selected language (e.g. "en", "es-419").
 */
class LanguageRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private val IS_CONFIGURED = booleanPreferencesKey("language_selected")
    private val SELECTED_LANGUAGE_CODE = stringPreferencesKey("selected_language")

    /** True only when the user explicitly completed the initial language selection. */
    val isConfigured: Flow<Boolean> = dataStore.data.map { prefs -> prefs[IS_CONFIGURED] ?: false }

    /**
     * The stored code once the initial selection is done, otherwise null
     * (the onboarding screen is shown and auto-detection applies).
     */
    val selectedLanguage: Flow<AppLanguage> = dataStore.data.map { prefs ->
        if (prefs[IS_CONFIGURED] == true) {
            AppLanguage.fromCode(prefs[SELECTED_LANGUAGE_CODE])
        } else {
            AppLanguage.default
        }
    }

    /** Completes the first-run selection: marks the app configured and stores the code. */
    suspend fun completeSelection(language: AppLanguage) {
        dataStore.edit { prefs ->
            prefs[IS_CONFIGURED] = true
            prefs[SELECTED_LANGUAGE_CODE] = language.code
        }
    }

    /** Changes the language from Settings without touching the "configured" flag. */
    suspend fun setSelectedLanguage(language: AppLanguage) {
        dataStore.edit { prefs ->
            prefs[SELECTED_LANGUAGE_CODE] = language.code
        }
    }

    /**
     * Synchronous first read used only during app startup to rebase the Activity
     * locale before any UI is shown. Fallbacks: a persisted-but-unsupported code
     * → English; no configured selection yet → auto-detection.
     */
    fun initialLanguage(): AppLanguage {
        val prefs = runBlocking { dataStore.data.first() }
        if (prefs[IS_CONFIGURED] != true) return AppLanguage.detect()
        return AppLanguage.fromCode(prefs[SELECTED_LANGUAGE_CODE])
    }
}
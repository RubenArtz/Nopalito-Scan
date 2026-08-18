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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LanguageUiState(
    /** True once the user has completed the first-run language selection. */
    val isConfigured: Boolean = false,
    /** The configured language when set, else the auto-detected one. */
    val selectedLanguage: AppLanguage = AppLanguage.default,
    /** Persisted legal acceptance (terms + privacy). */
    val legalConsent: LegalConsent = LegalConsent(),
) {
    /** True only when both legal documents were accepted for the current versions. */
    val isLegalComplete: Boolean get() = legalConsent.isComplete()
}

class LanguageViewModel(
    private val repo: LanguageRepository,
    legalRepo: LegalConsentRepository,
) : ViewModel() {

    val uiState: StateFlow<LanguageUiState> =
        combine(
            repo.isConfigured,
            repo.selectedLanguage,
            legalRepo.consent,
        ) { configured, language, legal ->
            LanguageUiState(
                isConfigured = configured,
                // Auto-detection only applies while no manual selection exists yet.
                selectedLanguage = if (configured) language else AppLanguage.detect(),
                legalConsent = legal,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LanguageUiState(selectedLanguage = AppLanguage.detect()),
        )

    /** Applies a language change from Settings. */
    fun selectLanguage(language: AppLanguage) {
        viewModelScope.launch { repo.setSelectedLanguage(language) }
    }
}
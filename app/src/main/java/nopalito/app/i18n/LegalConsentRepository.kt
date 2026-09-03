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

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The two legal documents that must both be accepted before using Nopalito Scan. */
enum class LegalDocument { TERMS, PRIVACY }

/**
 * Persisted legal consent, tracked separately per document and per version so
 * the app can tell whether both documents were accepted and for which version.
 */
data class LegalConsent(
    val termsAccepted: Boolean = false,
    val privacyAccepted: Boolean = false,
    val termsVersion: String = "",
    val privacyVersion: String = "",
    val acceptedAtUtc: String? = null,
)

/** True only when BOTH documents were accepted for the CURRENT legal versions. */
fun LegalConsent.isComplete(): Boolean =
    termsAccepted && privacyAccepted &&
            termsVersion == LegalConsentRepository.TERMS_VERSION &&
            privacyVersion == LegalConsentRepository.PRIVACY_VERSION

/** Asset file name (under `assets/`) for one legal document in a language. */
fun legalAssetName(language: AppLanguage, document: LegalDocument): String {
    val base = when (document) {
        LegalDocument.TERMS -> "terms-and-conditions"
        LegalDocument.PRIVACY -> "privacy-policy"
    }
    val suffix = when (language.code) {
        "es-419", "es" -> "es"
        "pt-BR", "pt" -> "pt"
        "fr" -> "fr"
        "de" -> "de"
        else -> "en"
    }
    return "legal/$base-$suffix.md"
}

/** Reads a legal document asset, or null when missing/unreadable. */
fun Context.readLegalDocument(language: AppLanguage, document: LegalDocument): String? =
    try {
        assets.open(legalAssetName(language, document)).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

/**
 * Persists legal acceptance through DataStore Preferences (same store as the
 * language choice, so the consent survives language changes).
 *
 * Keys stored:
 *  - `legal_terms_accepted` / `legal_privacy_accepted` → true only when BOTH
 *    documents were accepted.
 *  - `legal_terms_version` / `legal_privacy_version` → accepted versions.
 *  - `legal_accepted_at` → ISO-8601 UTC timestamp of the acceptance.
 */
class LegalConsentRepository(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        /** Current published version of the Terms and Conditions. */
        const val TERMS_VERSION = "1.0"

        /** Current published version of the Privacy Policy. */
        const val PRIVACY_VERSION = "1.0"
    }

    private val TERMS_ACCEPTED = booleanPreferencesKey("legal_terms_accepted")
    private val PRIVACY_ACCEPTED = booleanPreferencesKey("legal_privacy_accepted")
    private val TERMS_VERSION_KEY = stringPreferencesKey("legal_terms_version")
    private val PRIVACY_VERSION_KEY = stringPreferencesKey("legal_privacy_version")
    private val ACCEPTED_AT = stringPreferencesKey("legal_accepted_at")

    val consent: Flow<LegalConsent> = dataStore.data.map { prefs ->
        LegalConsent(
            termsAccepted = prefs[TERMS_ACCEPTED] ?: false,
            privacyAccepted = prefs[PRIVACY_ACCEPTED] ?: false,
            termsVersion = prefs[TERMS_VERSION_KEY].orEmpty(),
            privacyVersion = prefs[PRIVACY_VERSION_KEY].orEmpty(),
            acceptedAtUtc = prefs[ACCEPTED_AT],
        )
    }

    /** Records acceptance of BOTH documents for the current versions. */
    suspend fun accept(acceptedAtUtc: String) {
        dataStore.edit { prefs ->
            prefs[TERMS_ACCEPTED] = true
            prefs[PRIVACY_ACCEPTED] = true
            prefs[TERMS_VERSION_KEY] = TERMS_VERSION
            prefs[PRIVACY_VERSION_KEY] = PRIVACY_VERSION
            prefs[ACCEPTED_AT] = acceptedAtUtc
        }
    }
}

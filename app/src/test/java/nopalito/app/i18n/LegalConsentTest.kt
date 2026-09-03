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

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegalConsentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newRepository(name: String = "test.preferences_pb"): LegalConsentRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.root, name) }
        )
        return LegalConsentRepository(dataStore)
    }

    @Test
    fun `fresh install has no acceptance`() = runTest {
        val consent = newRepository().consent.first()
        assertFalse(consent.termsAccepted)
        assertFalse(consent.privacyAccepted)
        assertFalse(consent.isComplete())
    }

    @Test
    fun `accept records both documents for the current versions`() = runTest {
        val repo = newRepository()
        repo.accept("2026-08-09T00:00:00Z")
        val consent = repo.consent.first()
        assertTrue(consent.termsAccepted)
        assertTrue(consent.privacyAccepted)
        assertEquals(LegalConsentRepository.TERMS_VERSION, consent.termsVersion)
        assertEquals(LegalConsentRepository.PRIVACY_VERSION, consent.privacyVersion)
        assertEquals("2026-08-09T00:00:00Z", consent.acceptedAtUtc)
        assertTrue(consent.isComplete())
    }

    @Test
    fun `acceptance is not complete when only one document is accepted`() {
        val consent = LegalConsent(
            termsAccepted = true,
            privacyAccepted = false,
            termsVersion = LegalConsentRepository.TERMS_VERSION,
            privacyVersion = LegalConsentRepository.PRIVACY_VERSION,
        )
        assertFalse(consent.isComplete())
    }

    @Test
    fun `version change invalidates a previous acceptance`() {
        val consent = LegalConsent(
            termsAccepted = true,
            privacyAccepted = true,
            termsVersion = "0.9",
            privacyVersion = LegalConsentRepository.PRIVACY_VERSION,
        )
        assertFalse(consent.isComplete())
    }

    @Test
    fun `acceptance round-trips through the persisted store`() = runTest {
        val repo = newRepository("persist.preferences_pb")
        assertFalse(repo.consent.first().isComplete())
        repo.accept("2026-08-09T00:00:00Z")
        val consent = repo.consent.first()
        assertTrue(consent.isComplete())
        assertTrue(consent.termsAccepted)
        assertTrue(consent.privacyAccepted)
        assertEquals("2026-08-09T00:00:00Z", consent.acceptedAtUtc)
    }

    @Test
    fun `asset names map to per-language files`() {
        assertEquals(
            "legal/terms-and-conditions-en.md",
            legalAssetName(AppLanguage.ENGLISH, LegalDocument.TERMS),
        )
        assertEquals(
            "legal/privacy-policy-en.md",
            legalAssetName(AppLanguage.ENGLISH, LegalDocument.PRIVACY),
        )
        assertEquals(
            "legal/terms-and-conditions-es.md",
            legalAssetName(AppLanguage.SPANISH_LATAM, LegalDocument.TERMS),
        )
        assertEquals(
            "legal/privacy-policy-pt.md",
            legalAssetName(AppLanguage.PORTUGUESE_BRAZIL, LegalDocument.PRIVACY),
        )
        assertEquals(
            "legal/terms-and-conditions-fr.md",
            legalAssetName(AppLanguage.FRENCH, LegalDocument.TERMS),
        )
        assertEquals(
            "legal/privacy-policy-de.md",
            legalAssetName(AppLanguage.GERMAN, LegalDocument.PRIVACY),
        )
    }
}

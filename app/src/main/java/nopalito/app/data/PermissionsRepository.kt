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

package nopalito.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists whether the first-run permission onboarding has been completed.
 */
class PermissionsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private val ONBOARDING_DONE = booleanPreferencesKey("permissions_onboarding_done")

    /** True once the user finished the first-run permission screen. */
    val isOnboardingDone: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[ONBOARDING_DONE] ?: false }

    suspend fun completeOnboarding() {
        dataStore.edit { prefs -> prefs[ONBOARDING_DONE] = true }
    }
}
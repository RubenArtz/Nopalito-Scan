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

package nopalito.app.ui.screens.cloud.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists the user's explicit consent to use WEAK biometrics (Class 2, e.g.
 * 2D face) when STRONG is unavailable.
 *
 * - Default is STRONG for all existing users (no dialog).
 * - WEAK is only offered when `BiometricCapability.shouldOfferWeakFallback`
 *   is true (STRONG unavailable, WEAK available).
 * - If the user accepts WEAK, we store `weakAccepted = true` and never ask
 *   again. If they choose "Only PIN", we store nothing and keep biometric
 *   disabled — they will be asked again next time they toggle.
 *
 * Security is documented in [BiometricCapability] and the warning dialog
 * strings (`cloud_biometric_weak_*`).
 */
class BiometricWeakPreference private constructor(
    private val prefs: SharedPreferences,
) {
    companion object {
        private const val PREFS_NAME = "cloud_biometric_weak_prefs"
        private const val KEY_WEAK_ACCEPTED = "weak_accepted_v1"

        fun open(context: Context): BiometricWeakPreference {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return BiometricWeakPreference(prefs)
        }

        // For tests / dependency injection
        internal fun from(prefs: SharedPreferences) = BiometricWeakPreference(prefs)
    }

    /** True if the user has explicitly accepted WEAK fallback. */
    var isWeakAccepted: Boolean
        get() = prefs.getBoolean(KEY_WEAK_ACCEPTED, false)
        set(value) = prefs.edit { putBoolean(KEY_WEAK_ACCEPTED, value) }

    fun setAccepted() {
        isWeakAccepted = true
    }

    fun clear() {
        prefs.edit { remove(KEY_WEAK_ACCEPTED) }
    }
}
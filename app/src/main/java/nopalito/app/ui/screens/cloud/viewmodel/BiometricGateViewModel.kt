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

package nopalito.app.ui.screens.cloud.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.security.BiometricGateState
import nopalito.app.ui.screens.cloud.security.BiometricSessionManager
import nopalito.app.ui.screens.cloud.security.BiometricUnlockOutcome

/** UI state of the biometric gate screen. */
sealed interface BiometricGateUiState {
    /** Waiting for the user to tap "Unlock" (or retry after a dismissible message). */
    data object Idle : BiometricGateUiState

    /** The OS biometric prompt is on screen; no further taps should trigger it. */
    data object Prompting : BiometricGateUiState

    /** Tier-2 unlocked: the cloud session can proceed (navigate to Home). */
    data object Unlocked : BiometricGateUiState

    /** Keystore key invalidated: biometric mode was wiped; re-login required. */
    data object KeyInvalidated : BiometricGateUiState

    /** Transient failure (locked out / unavailable / failed); user can retry. */
    data class Message(val text: String) : BiometricGateUiState
}

/**
 * Drives the biometric gate for the cloud session.
 *
 * It forwards [BiometricSessionManager.requestUnlock] and maps the outcome to
 * a UI state. Navigation decisions (unlock → Home, key invalidated → login)
 * stay in CloudHost; this ViewModel only exposes the outcome.
 */
class BiometricGateViewModel(
    private val biometricSessionManager: BiometricSessionManager,
    private val application: Application,
) : ViewModel() {

    /** Mirror of the manager's gate (Hidden/Required/Prompting). */
    val gate: StateFlow<BiometricGateState> = biometricSessionManager.gate

    private val _uiState = MutableStateFlow<BiometricGateUiState>(BiometricGateUiState.Idle)
    val uiState: StateFlow<BiometricGateUiState> = _uiState.asStateFlow()

    /**
     * Ask the user to unlock. The manager shows the OS prompt and hands the
     * unwrapped Tier-2 to the unlock session (never to this ViewModel).
     */
    fun unlock() {
        if (_uiState.value == BiometricGateUiState.Prompting) return
        _uiState.value = BiometricGateUiState.Prompting
        biometricSessionManager.requestUnlock { outcome ->
            _uiState.value = when (outcome) {
                BiometricUnlockOutcome.Unlocked -> BiometricGateUiState.Unlocked
                BiometricUnlockOutcome.KeyInvalidated -> BiometricGateUiState.KeyInvalidated
                BiometricUnlockOutcome.Cancelled -> BiometricGateUiState.Idle
                BiometricUnlockOutcome.LockedOut ->
                    message(R.string.cloud_biometric_unlock_locked_out)

                BiometricUnlockOutcome.NotAvailable ->
                    message(R.string.cloud_biometric_unlock_unavailable)

                BiometricUnlockOutcome.NoSecureLockScreen ->
                    message(R.string.cloud_biometric_unlock_no_screen_lock)

                BiometricUnlockOutcome.Failed ->
                    message(R.string.cloud_biometric_unlock_failed)

                BiometricUnlockOutcome.Enabled -> BiometricGateUiState.Idle
                BiometricUnlockOutcome.Disabled -> BiometricGateUiState.Idle
            }
        }
    }

    /** Dismiss a transient [BiometricGateUiState.Message] and allow retry. */
    fun dismissMessage() {
        if (_uiState.value is BiometricGateUiState.Message) {
            _uiState.value = BiometricGateUiState.Idle
        }
    }

    private fun message(resId: Int) = BiometricGateUiState.Message(
        application.stringFor(resId, AppLocaleOverride.locale)
    )
}
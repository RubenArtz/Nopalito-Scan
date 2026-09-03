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

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

private const val TAG = "BiometricSessionManager"

/**
 * UI-visible state of the biometric gate. `Prompting` means the OS prompt is
 * on screen (the gate must not re-trigger), `Required` means the user must
 * authenticate before the cloud session can proceed.
 */
enum class BiometricGateState {
    Hidden,
    Required,
    Prompting,
}

/**
 * Outcome of an enable/unlock round. Nothing here carries a token: Tier-2
 * lives only inside [BiometricUnlockSession], and the refresh token only
 * inside the refresh call frame.
 */
sealed interface BiometricUnlockOutcome {
    /** Biometric mode is on; Tier-2 is active in memory. */
    data object Enabled : BiometricUnlockOutcome

    /** Biometric mode was turned off; the refresh token is back in normal prefs. */
    data object Disabled : BiometricUnlockOutcome

    /** Tier-2 was unwrapped and the session is active. */
    data object Unlocked : BiometricUnlockOutcome

    data object Cancelled : BiometricUnlockOutcome
    data object LockedOut : BiometricUnlockOutcome
    data object NotAvailable : BiometricUnlockOutcome
    data object Failed : BiometricUnlockOutcome

    /** Keystore key died: mode was wiped; re-enable after the next login. */
    data object KeyInvalidated : BiometricUnlockOutcome

    /**
     * The device has no secure lock screen (no PIN/pattern/password): the
     * Keystore cannot create an authentication-bound key.
     */
    data object NoSecureLockScreen : BiometricUnlockOutcome
}

/**
 * Orchestrates the biometric gate for the cloud session.
 *
 * - [gate] tells the UI whether the unlock screen must be shown.
 * - One OS prompt unwraps Tier-2 (Tier-1 key). While Tier-2 is active, every
 *   token operation runs without any further prompt.
 * - A permanently invalidated key (biometrics changed) wipes the mode so the
 *   user is not stuck behind an unlock that can never succeed.
 */
class BiometricSessionManager(
    private val store: BiometricTokenStore,
    private val controllerProvider: () -> BiometricPromptController,
) {
    private val authManager by lazy { BiometricAuthManager(store, controllerProvider) }

    internal val unlockSession = BiometricUnlockSession(store)

    private val _gate = MutableStateFlow(BiometricGateState.Hidden)
    val gate: StateFlow<BiometricGateState> = _gate.asStateFlow()

    val isEnabled: Boolean
        get() = authManager.isEnabled

    val hasActiveSession: Boolean
        get() = unlockSession.isActive

    /**
     * Called whenever the cloud session state changes. The gate shows only
     * when biometric mode is on, the cloud session is inactive AND Tier-2 is
     * gone (nothing would succeed without a fresh prompt).
     */
    fun setSessionActive(active: Boolean) {
        _gate.value = when {
            !authManager.isEnabled -> BiometricGateState.Hidden
            active || unlockSession.isActive -> BiometricGateState.Hidden
            else -> BiometricGateState.Required
        }
    }

    /**
     * Turn biometric mode on for [refreshToken]. One prompt wraps a fresh
     * Tier-2 with Tier-1; the token is then encrypted with Tier-2 and
     * persisted atomically — no second prompt.
     * @param allowWeak true only when STRONG unavailable but WEAK available and
     * user has accepted the weak warning. Existing STRONG users use false.
     */
    fun enable(
        refreshToken: String,
        allowWeak: Boolean = false,
        onResult: (BiometricUnlockOutcome) -> Unit,
    ) {
        val report: (BiometricUnlockOutcome) -> Unit = { outcome ->
            Log.d(TAG, "enable(allowWeak=$allowWeak) → $outcome")
            onResult(outcome)
        }
        if (authManager.isEnabled) {
            // Mode already on: re-encrypt the (possibly rotated) token with
            // the in-memory Tier-2, no prompt at all.
            Log.d(TAG, "enable: mode already on, rotating without prompt")
            if (unlockSession.rotateRefreshToken(refreshToken)) {
                report(BiometricUnlockOutcome.Enabled)
            } else {
                report(BiometricUnlockOutcome.Failed)
            }
            return
        }
        val tier2 = ByteArray(TIER2_SIZE).also { SecureRandom().nextBytes(it) }
        authManager.authenticate(BiometricRequest.Encrypt(tier2), allowWeak) { result ->
            when (result) {
                is BiometricAuthResult.Encrypted -> {
                    unlockSession.activate(tier2)
                    if (unlockSession.rotateRefreshToken(refreshToken)) {
                        report(BiometricUnlockOutcome.Enabled)
                    } else {
                        disable()
                        report(BiometricUnlockOutcome.Failed)
                    }
                }

                BiometricAuthResult.Cancelled -> report(BiometricUnlockOutcome.Cancelled)
                BiometricAuthResult.LockedOut -> report(BiometricUnlockOutcome.LockedOut)
                BiometricAuthResult.NotAvailable -> report(BiometricUnlockOutcome.NotAvailable)
                BiometricAuthResult.NoSecureLockScreen ->
                    report(BiometricUnlockOutcome.NoSecureLockScreen)

                BiometricAuthResult.KeyInvalidated -> {
                    disable()
                    report(BiometricUnlockOutcome.KeyInvalidated)
                }

                is BiometricAuthResult.Unlocked -> report(BiometricUnlockOutcome.Failed)
                BiometricAuthResult.NotEnabled,
                BiometricAuthResult.Failed,
                    -> report(BiometricUnlockOutcome.Failed)
            }
        }
    }

    /**
     * Ask the user to unlock. Tier-2, once unwrapped, is handed to
     * [BiometricUnlockSession] — nothing is returned to the caller.
     * @param allowWeak true if the key was created with WEAK fallback (see
     * [BiometricWeakPreference]).
     */
    fun requestUnlock(
        allowWeak: Boolean = false,
        onResult: (BiometricUnlockOutcome) -> Unit,
    ) {
        val report: (BiometricUnlockOutcome) -> Unit = { outcome ->
            Log.d(TAG, "requestUnlock(allowWeak=$allowWeak) → $outcome")
            onResult(outcome)
        }
        if (!authManager.isEnabled) {
            report(BiometricUnlockOutcome.Failed)
            return
        }
        if (unlockSession.isActive) {
            report(BiometricUnlockOutcome.Unlocked)
            return
        }
        _gate.value = BiometricGateState.Prompting
        authManager.authenticate(BiometricRequest.Decrypt, allowWeak) { result ->
            when (result) {
                is BiometricAuthResult.Unlocked -> {
                    unlockSession.activate(result.tier2)
                    _gate.value = BiometricGateState.Hidden
                    report(BiometricUnlockOutcome.Unlocked)
                }

                BiometricAuthResult.KeyInvalidated -> {
                    disable()
                    report(BiometricUnlockOutcome.KeyInvalidated)
                }

                else -> {
                    _gate.value = BiometricGateState.Required
                    report(result.toOutcome())
                }
            }
        }
    }

    /** Turn biometric mode off (key + blobs) and destroy Tier-2. */
    fun disable() {
        Log.d(TAG, "disable: wiping mode and tier-2")
        authManager.disable()
        unlockSession.wipe()
        _gate.value = BiometricGateState.Hidden
    }

    private fun BiometricAuthResult.toOutcome(): BiometricUnlockOutcome = when (this) {
        BiometricAuthResult.Encrypted -> BiometricUnlockOutcome.Enabled
        is BiometricAuthResult.Unlocked -> BiometricUnlockOutcome.Unlocked
        BiometricAuthResult.Cancelled -> BiometricUnlockOutcome.Cancelled
        BiometricAuthResult.LockedOut -> BiometricUnlockOutcome.LockedOut
        BiometricAuthResult.NotAvailable -> BiometricUnlockOutcome.NotAvailable
        BiometricAuthResult.NoSecureLockScreen -> BiometricUnlockOutcome.NoSecureLockScreen
        BiometricAuthResult.KeyInvalidated -> BiometricUnlockOutcome.KeyInvalidated
        BiometricAuthResult.NotEnabled,
        BiometricAuthResult.Failed,
            -> BiometricUnlockOutcome.Failed
    }

    private companion object {
        const val TIER2_SIZE = 32
    }
}
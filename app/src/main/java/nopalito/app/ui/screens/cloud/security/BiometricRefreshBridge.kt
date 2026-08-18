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

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Bridges the callback-based [BiometricSessionManager] prompt API into
 * suspend functions, so suspend callers (cloud refresh, login migration)
 * can wait for the OS prompt. Exactly ONE prompt happens per unlock or
 * enable; token rotation afterwards is synchronous via Tier-2 in memory.
 */
class BiometricRefreshBridge(
    private val manager: BiometricSessionManager,
) {

    /** Runs the unlock prompt. `true` = Tier-2 is active in memory. */
    suspend fun unlockSession(): Boolean = suspendCancellableCoroutine { cont ->
        if (!cont.isActive) return@suspendCancellableCoroutine
        manager.requestUnlock { outcome ->
            if (cont.isActive) {
                cont.resume(outcome is BiometricUnlockOutcome.Unlocked)
            }
        }
    }

    /** Runs the enable prompt and migrates [refreshToken] into the blob. */
    suspend fun enableSession(refreshToken: String): Boolean = suspendCancellableCoroutine { cont ->
        if (!cont.isActive) return@suspendCancellableCoroutine
        manager.enable(refreshToken) { outcome ->
            if (cont.isActive) {
                cont.resume(outcome is BiometricUnlockOutcome.Enabled)
            }
        }
    }
}
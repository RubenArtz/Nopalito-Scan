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

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Destroys Tier-2 when the app goes to the background (`ON_STOP`), so the
 * decrypted session material never survives while the UI is not visible.
 * Returning to the app therefore requires a fresh OS prompt.
 *
 * ## Foreground/background policy (approved)
 *
 * The observer reacts ONLY to lifecycle events; it is lifecycle-owner agnostic.
 * What each real-world scenario does depends on which [LifecycleOwner] it is
 * registered against — decided when the gate UI is wired (Etapa 7):
 *
 * | Scenario                                    | Host activity lifecycle | Process lifecycle     |
 * |---------------------------------------------|-------------------------|-----------------------|
 * | Recomposition (Compose)                     | no event                | no event              |
 * | `BiometricPrompt` dialog open               | no `ON_STOP`            | no `ON_STOP`          |
 * | Rotation / configuration change             | `ON_STOP` + recreate    | no `ON_STOP`          |
 * | Internal navigation to another activity     | `ON_STOP`               | no `ON_STOP`          |
 * | Real background (prolonged or not)          | `ON_STOP`               | `ON_STOP`             |
 * | Process death                               | n/a (process gone)      | n/a (process gone)    |
 *
 * Current policy (strict): the observer wipes on ANY `ON_STOP`. Under this
 * policy a rotation or an internal activity hop also wipes Tier-2, so
 * returning to Cloud requires a new prompt — documented and tested.
 *
 * Etapa 7 alternative (recommended, no extra Tier-2 lifetime): register the
 * observer against `ProcessLifecycleOwner` instead of the cloud activity.
 * Then only a REAL background triggers the wipe; rotation, recomposition,
 * the biometric dialog and internal navigation never prompt again. Tier-2
 * still dies on real background and with the process.
 *
 * Note: the system cancels any in-flight `BiometricPrompt` when its host
 * activity stops, so a prompt cannot complete after this observer fired.
 */
class BiometricLifecycleObserver(
    private val session: BiometricUnlockSession,
) : LifecycleEventObserver {

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_STOP) {
            session.wipe()
        }
    }
}
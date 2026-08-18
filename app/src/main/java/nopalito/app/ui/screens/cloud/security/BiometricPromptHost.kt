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

/**
 * Process-wide registry of the single active [BiometricPromptController].
 *
 * The OS prompt is bound to one `FragmentActivity` at a time, but the
 * biometric machinery (TokenProvider flag, repository refresh, gate) is
 * app-wide. Screens hosting a prompt register their controller on
 * resume/attach and unregister on pause/detach; anyone needing a prompt
 * resolves it through [requireController].
 */
object BiometricPromptHost {

    @Volatile
    private var activeController: BiometricPromptController? = null

    @Volatile
    private var errorSink: (code: Int, message: String) -> Unit = { _, _ -> }

    fun register(controller: BiometricPromptController) {
        activeController = controller
    }

    fun unregister(controller: BiometricPromptController) {
        if (activeController === controller) {
            activeController = null
        }
    }

    fun requireController(): BiometricPromptController =
        activeController ?: throw IllegalStateException(
            "BiometricPromptHost: no active controller registered (host screen not attached)"
        )

    /**
     * Destination for raw OS prompt errors (code + vendor message), so device
     * failures reach the app log instead of being lost in the outcome mapping.
     */
    fun setErrorSink(sink: (code: Int, message: String) -> Unit) {
        errorSink = sink
    }

    fun reportPromptError(code: Int, message: String) {
        errorSink(code, message)
    }
}
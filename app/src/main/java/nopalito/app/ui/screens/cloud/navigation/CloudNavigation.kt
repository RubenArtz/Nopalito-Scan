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

package nopalito.app.ui.screens.cloud.navigation

/** Which password-recovery flow a [Cloudscreen.Recover] screen runs. */
enum class CloudRecoverMode {
    /** Forgot password (reset) — code sent to a registered email. */
    FORGOT,
}

sealed class CloudScreen {
    object Splash : CloudScreen()
    object EmailLogin : CloudScreen()

    /**
     * Biometric gate: shown when the cloud session needs an OS biometric
     * unlock before any API call can run (biometric mode active).
     */
    object Gate : CloudScreen()

    /** Registration form (email + password + confirmation) before the OTP. */
    object Register : CloudScreen()

    /** Forgot / set-password flow (email → code → new password). */
    data class Recover(val mode: CloudRecoverMode) : CloudScreen()

    data class OtpVerify(
        val email: String,
        val isLogin: Boolean,
        val resendAvailableInSeconds: Int = 60
    ) : CloudScreen()

    object Home : CloudScreen()

    /** Server-authoritative storage usage (plan + quota + progress bar). */
    object Storage : CloudScreen()

    object UploadFile : CloudScreen()
    object Trash : CloudScreen()
    object QrHistory : CloudScreen()
    object QrTrash : CloudScreen()
}

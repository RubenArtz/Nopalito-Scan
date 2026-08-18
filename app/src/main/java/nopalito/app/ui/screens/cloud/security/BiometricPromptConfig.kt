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

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import nopalito.app.R

/**
 * Builds the OS prompt config, trying every candidate authenticator set in
 * order (most permissive first) and falling back to the next if the platform
 * rejects the combination. The negative button is required on API 26-29
 * (BIOMETRIC_STRONG only) but must NOT be set on API 30+ when the authenticator
 * set includes DEVICE_CREDENTIAL, otherwise `BiometricPrompt` throws.
 */
fun buildBiometricPromptInfo(context: android.content.Context): BiometricPrompt.PromptInfo {
    for (authenticators in BiometricCapability.authenticatorSetsFor(Build.VERSION.SDK_INT)) {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.cloud_biometric_prompt_title))
            .setSubtitle(context.getString(R.string.cloud_biometric_prompt_subtitle))
            .setAllowedAuthenticators(authenticators)
        if (authenticators and BiometricCapability.DEVICE_CREDENTIAL == 0) {
            builder.setNegativeButtonText(context.getString(R.string.cloud_biometric_prompt_negative))
        }
        try {
            return builder.build()
        } catch (_: IllegalArgumentException) {
            // Combination rejected at this API level (some OEMs / library
            // versions); try the next candidate.
        }
    }
    // Last resort: STRONG alone, which is valid on every supported API.
    // The real androidx constant is passed to setAllowedAuthenticators (the
    // value mirrors BiometricCapability.BIOMETRIC_STRONG = 0x0F exactly).
    return BiometricPrompt.PromptInfo.Builder()
        .setTitle(context.getString(R.string.cloud_biometric_prompt_title))
        .setSubtitle(context.getString(R.string.cloud_biometric_prompt_subtitle))
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText(context.getString(R.string.cloud_biometric_prompt_negative))
        .build()
}
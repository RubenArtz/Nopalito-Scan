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
 *
 * Default: STRONG only (existing users, secure). Uses
 * [BiometricManager.Authenticators.BIOMETRIC_STRONG] (0x0F), which delegates
 * modality choice to the OS: if the device has strong (Class 3) fingerprint
 * and face enrolled, the system prompt shows both. The app never filters by
 * type; the system decides via [BiometricManager.canAuthenticate].
 *
 * WEAK fallback (Class 2, e.g. 2D face): only when STRONG unavailable but
 * WEAK available and user has accepted the warning (see
 * [BiometricWeakPreference]). In that case the Keystore key is created with
 * `allowWeak=true` (see [AndroidCryptoKeyStore]) and the prompt is built
 * with `BIOMETRIC_WEAK` / `WEAK_OR_DEVICE_CREDENTIAL`.
 *
 * On API 30+ tries `STRONG or DEVICE_CREDENTIAL` first to allow fallback to
 * system PIN/pattern/password; if unavailable falls back to `STRONG` alone.
 * With `allowWeak=true`, additionally tries `WEAK or DEVICE_CREDENTIAL` and
 * `WEAK`. On API 26-29 only `STRONG` (and `WEAK` if allowed).
 * `allowedAuthenticators` is checked with `BiometricManager.canAuthenticate()`
 * before launching the prompt (see [BiometricCapability.resolve]).
 *
 * Known limitation:
 * Some devices enroll face but expose it only as Class 2 / WEAK (e.g. 2D face).
 * On those devices the default STRONG prompt shows only fingerprint — expected
 * Keystore behavior. With WEAK fallback the prompt will show face after user
 * consent. Devices with Class 3 face (Pixel 4/7/8) show both with STRONG.
 * [source.android](https://source.android.com/docs/security/features/biometric)
 */
fun buildBiometricPromptInfo(
    context: android.content.Context,
    allowWeak: Boolean = false,
): BiometricPrompt.PromptInfo {
    val candidates = if (allowWeak) {
        BiometricCapability.authenticatorSetsForWithWeakFallback(Build.VERSION.SDK_INT)
    } else {
        BiometricCapability.authenticatorSetsFor(Build.VERSION.SDK_INT)
    }
    for (authenticators in candidates) {
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
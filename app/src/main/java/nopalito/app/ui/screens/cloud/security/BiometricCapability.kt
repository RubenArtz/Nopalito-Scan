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
import androidx.biometric.BiometricManager

/**
 * Availability of the device for optional biometric Cloud unlock.
 *
 * The modality is never chosen by hand (fingerprint vs face): the system
 * decides via [BiometricManager.canAuthenticate]. This layer only decides
 * WHICH authenticator sets the app is allowed to advertise for the current
 * API level and maps the platform result to a stable, testable state.
 *
 * Rules enforced here:
 * - `BIOMETRIC_WEAK` is never used.
 * - `DEVICE_CREDENTIAL` (PIN/pattern/passcode) is only advertised where it can
 *   actually unlock an Android Keystore crypto key (API 30+). On API 26-29
 *   `KeyProperties.AUTH_DEVICE_CREDENTIAL` does not exist, so a device
 *   credential can never release a crypto-bound key; advertising "use PIN"
 *   there would be a lie.
 * - The whole class is a pure decision engine: no Android imports, so it is
 *   fully JVM-testable with a fake [BiometricCapabilityChecker].
 */
sealed interface BiometricAvailability {

    /**
     * The device can satisfy the given authenticator bitmask (values mirror
     * `BiometricManager.Authenticators`). The bitmask is exactly what the
     * caller must pass to `BiometricManager.canAuthenticate`, to
     * `BiometricPrompt.PromptInfo.Builder.setAllowedAuthenticators` and, for
     * API 30+, to `KeyGenParameterSpec.Builder.setUserAuthenticationParameters`.
     */
    data class Available(val authenticators: Int) : BiometricAvailability

    /** No biometric hardware and no usable device-credential fallback. */
    data object NoHardware : BiometricAvailability

    /** Hardware exists but no biometric is enrolled (and no PIN fallback usable). */
    data object NotEnrolled : BiometricAvailability

    /** Hardware temporarily unavailable / security update required / unsupported. */
    data object Unavailable : BiometricAvailability

    /** [BiometricManager.canAuthenticate] returned an unexpected value. */
    data object Unknown : BiometricAvailability
}

/** Source of `BiometricManager.canAuthenticate` results (abstract for tests). */
fun interface BiometricCapabilityChecker {
    /** Mirrors `BiometricManager.canAuthenticate(authenticators)`. */
    fun canAuthenticate(authenticators: Int): Int
}

/**
 * Queries the real platform `BiometricManager` from an Android [Context].
 * Only used in production; never in JVM tests.
 */
class AndroidBiometricCapabilityChecker(context: Context) : BiometricCapabilityChecker {
    private val biometricManager = BiometricManager.from(context.applicationContext)

    override fun canAuthenticate(authenticators: Int): Int =
        biometricManager.canAuthenticate(authenticators)
}

object BiometricCapability {

    // ── Authenticator bits (mirror BiometricManager.Authenticators) ──
    // BIOMETRIC_STRONG is the only biometric type the app ever advertises.
    // Values match androidx.biometric.BiometricManager.Authenticators exactly:
    // BIOMETRIC_STRONG = 0x0000000F, DEVICE_CREDENTIAL = 0x00008000. Any other
    // combination is rejected by PromptInfo.Builder.build() with an
    // IllegalArgumentException ("Authenticator combination is unsupported").
    const val BIOMETRIC_STRONG = 0x0000000F
    const val DEVICE_CREDENTIAL = 0x00008000
    const val STRONG_OR_DEVICE_CREDENTIAL = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    // ── Result codes (mirror BiometricManager) ──
    const val RESULT_SUCCESS = 0
    const val RESULT_ERROR_HW_UNAVAILABLE = 1
    const val RESULT_ERROR_NONE_ENROLLED = 11
    const val RESULT_ERROR_NO_HARDWARE = 12
    const val RESULT_ERROR_UNSUPPORTED = 13
    const val RESULT_ERROR_SECURITY_UPDATE_REQUIRED = 15
    const val RESULT_STATUS_UNKNOWN = -1

    /**
     * Candidate authenticator sets for an API level, most permissive first.
     *
     * The constraint that really matters is the Keystore: a device credential
     * only satisfies a crypto-bound key from API 30 onwards
     * (`KeyProperties.AUTH_DEVICE_CREDENTIAL` + `setUserAuthenticationParameters`).
     *
     * | API level  | Candidates offered                       |
     * |------------|------------------------------------------|
     * | 30+        | STRONG or DEVICE_CREDENTIAL, then STRONG |
     * | 26–29      | STRONG only                              |
     *
     * Android 10 (API 29) intentionally offers STRONG only: although the prompt
     * could show a PIN, the Keystore key could never be released by it.
     */
    fun authenticatorSetsFor(apiLevel: Int): List<Int> = when {
        apiLevel >= 30 -> listOf(STRONG_OR_DEVICE_CREDENTIAL, BIOMETRIC_STRONG)
        else -> listOf(BIOMETRIC_STRONG)
    }

    /**
     * Resolves the best authenticator the device can satisfy by trying every
     * candidate set in order, returning [BiometricAvailability.Available] with
     * the exact bitmask that matched. If none matches, maps the last
     * [BiometricManager.canAuthenticate] result to a stable state.
     *
     * A combination rejected with [IllegalArgumentException] (some OEMs /
     * library versions) simply falls through to the next candidate.
     */
    fun resolve(apiLevel: Int, checker: BiometricCapabilityChecker): BiometricAvailability {
        val candidates = authenticatorSetsFor(apiLevel)
        var lastResult = RESULT_STATUS_UNKNOWN
        for (candidate in candidates) {
            val result = try {
                checker.canAuthenticate(candidate)
            } catch (_: IllegalArgumentException) {
                // Combination not accepted at this API level; try the next one.
                RESULT_ERROR_UNSUPPORTED
            }
            if (result == RESULT_SUCCESS) {
                return BiometricAvailability.Available(candidate)
            }
            lastResult = result
        }
        return mapResult(lastResult)
    }

    /**
     * Pure mapping of a single [BiometricManager.canAuthenticate] result to a
     * [BiometricAvailability]. Only meaningful for failure codes: success is
     * decided by [resolve], which carries the matched authenticator set.
     */
    fun mapResult(result: Int): BiometricAvailability = when (result) {
        RESULT_SUCCESS -> BiometricAvailability.Available(BIOMETRIC_STRONG)
        RESULT_ERROR_NONE_ENROLLED -> BiometricAvailability.NotEnrolled
        RESULT_ERROR_NO_HARDWARE -> BiometricAvailability.NoHardware
        RESULT_ERROR_HW_UNAVAILABLE,
        RESULT_ERROR_SECURITY_UPDATE_REQUIRED,
        RESULT_ERROR_UNSUPPORTED -> BiometricAvailability.Unavailable

        else -> BiometricAvailability.Unknown
    }
}
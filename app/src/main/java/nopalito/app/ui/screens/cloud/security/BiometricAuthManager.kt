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

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

private const val TAG = "BiometricAuthManager"

/**
 * Result of a biometric authentication round. `Unlocked` deliberately
 * carries the freshly unwrapped Tier-2 key, which must be handed to
 * [BiometricUnlockSession] (the only owner) — never stored, logged or cached.
 */
sealed interface BiometricAuthResult {
    /** Tier-2 was wrapped with Tier-1 and persisted for biometric unlock. */
    data object Encrypted : BiometricAuthResult

    /** Tier-2 was unwrapped; hand it to [BiometricUnlockSession] and drop it. */
    data class Unlocked(val tier2: ByteArray) : BiometricAuthResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Unlocked

            return tier2.contentEquals(other.tier2)
        }

        override fun hashCode(): Int {
            return tier2.contentHashCode()
        }
    }

    data object Cancelled : BiometricAuthResult
    data object LockedOut : BiometricAuthResult
    data object NotAvailable : BiometricAuthResult

    /** The Keystore key was invalidated: biometric mode must be reset. */
    data object KeyInvalidated : BiometricAuthResult

    /**
     * The device has no secure lock screen (no PIN/pattern/password): keys
     * requiring user authentication cannot be created.
     */
    data object NoSecureLockScreen : BiometricAuthResult

    /** No biometric mode is enabled, or any unexpected failure. */
    data object NotEnabled : BiometricAuthResult
    data object Failed : BiometricAuthResult
}

sealed interface BiometricRequest {
    /** Wrap and persist [tier2] under the Tier-1 Keystore key. */
    data class Encrypt(val tier2: ByteArray) : BiometricRequest {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Encrypt

            if (!tier2.contentEquals(other.tier2)) return false

            return true
        }

        override fun hashCode(): Int {
            return tier2.contentHashCode()
        }
    }

    /** Unwrap Tier-2 from the persisted key blob. */
    data object Decrypt : BiometricRequest
}

/**
 * Stable, testable interpretation of a `BiometricPrompt` authentication error.
 * The raw code arrives from the OS; the manager translates it.
 */
enum class BiometricPromptFailure {
    LOCKED_OUT,
    NOT_AVAILABLE,
    CANCELLED,
    UNKNOWN,
}

internal fun mapPromptError(code: Int): BiometricPromptFailure = when (code) {
    BiometricPrompt.ERROR_LOCKOUT,
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        -> BiometricPromptFailure.LOCKED_OUT

    BiometricPrompt.ERROR_HW_UNAVAILABLE,
    BiometricPrompt.ERROR_NO_BIOMETRICS,
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
        -> BiometricPromptFailure.NOT_AVAILABLE

    // ERROR_NEGATIVE_BUTTON and ERROR_UNSUPPORTED share value 13. Inside a
    // running prompt the capability gate has already ruled out "unsupported",
    // so 13 means the user pressed the negative button -> cancel.
    BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        -> BiometricPromptFailure.CANCELLED

    else -> BiometricPromptFailure.UNKNOWN
}

/**
 * Thin adapter over the OS biometric prompt so [BiometricAuthManager]'s
 * control flow is testable in a JVM test. [AndroidBiometricPromptController]
 * is the production implementation.
 *
 * [crypto] is nullable: when the Keystore refuses to prepare a cipher before
 * the user authenticates (e.g. PIN-only device without enrolled biometrics),
 * the prompt runs WITHOUT a [BiometricPrompt.CryptoObject] and [onSuccess]
 * receives `null`; the cipher is then created inside the success callback,
 * where the key has been authorized by the authentication.
 */
interface BiometricPromptController {
    fun authenticate(
        crypto: BiometricPrompt.CryptoObject?,
        onSuccess: (Cipher?) -> Unit,
        onError: (promptErrorCode: Int) -> Unit,
        onFailed: () -> Unit,
    )
}

/**
 * Production [BiometricPromptController]. The [BiometricPrompt.PromptInfo]
 * (title/subtitle/negative button) is supplied by the caller so all strings
 * come from resources with the app's locale logic.
 */
class AndroidBiometricPromptController(
    private val activity: FragmentActivity,
    private val promptInfo: BiometricPrompt.PromptInfo,
) : BiometricPromptController {

    private val prompt by lazy { BiometricPrompt(activity, executor, callbacks) }

    override fun authenticate(
        crypto: BiometricPrompt.CryptoObject?,
        onSuccess: (Cipher?) -> Unit,
        onError: (promptErrorCode: Int) -> Unit,
        onFailed: () -> Unit,
    ) {
        pendingSuccess = onSuccess
        pendingError = onError
        pendingFailed = onFailed
        if (crypto != null) {
            prompt.authenticate(promptInfo, crypto)
        } else {
            prompt.authenticate(promptInfo)
        }
    }

    private val executor = ContextCompat.getMainExecutor(activity)

    private var pendingSuccess: ((Cipher?) -> Unit)? = null
    private var pendingError: ((Int) -> Unit)? = null
    private var pendingFailed: (() -> Unit)? = null

    private val callbacks = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            // Null when the prompt ran without a CryptoObject: the cipher must
            // be created in the success callback (key now authorized).
            pendingSuccess?.invoke(result.cryptoObject?.cipher)
            clearPending()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            BiometricPromptHost.reportPromptError(errorCode, errString.toString())
            pendingError?.invoke(errorCode)
            clearPending()
        }

        override fun onAuthenticationFailed() {
            pendingFailed?.invoke()
        }
    }

    private fun clearPending() {
        pendingSuccess = null
        pendingError = null
        pendingFailed = null
    }
}

/**
 * Orchestrates a biometric unlock cycle on top of [BiometricTokenStore].
 *
 * Security model:
 * - Default is `BIOMETRIC_STRONG` (Class 3) for all existing users. The prompt
 *   and Keystore key require Class 3 (fingerprint, Pixel face, iris) via
 *   `AUTH_BIOMETRIC_STRONG`. Existing users never see WEAK.
 * - `BIOMETRIC_WEAK` (Class 2, e.g. 2D face) is only used as a fallback when
 *   STRONG is unavailable but WEAK is, after the user explicitly accepts the
 *   weaker security via the warning dialog (see [BiometricWeakPreference]).
 *   In that case the Keystore key is created with `allowWeak=true` (legacy
 *   `setUserAuthenticationValidityDurationSeconds` path) which can be
 *   released by Class 2. See [BiometricCapability] and
 *   [AndroidCryptoKeyStore.createKey].
 *
 * Never writes the plaintext token, and re-creates the Keystore key if
 * [KeyPermanentlyInvalidatedException] shows up while enabling (biometrics
 * changed since last time).
 */
class BiometricAuthManager(
    private val store: BiometricTokenStore,
    private val controllerProvider: () -> BiometricPromptController,
) {
    private val controller by lazy { controllerProvider() }

    val isEnabled: Boolean
        get() = store.isEnabled

    /** Default STRONG-only path (existing users, backward compatible). */
    fun authenticate(request: BiometricRequest, listener: (BiometricAuthResult) -> Unit) =
        authenticate(request, allowWeak = false, listener = listener)

    /**
     * Authenticates with optional WEAK fallback.
     * @param allowWeak true only when STRONG unavailable but WEAK available and
     * user has accepted the WEAK warning (see [BiometricWeakPreference]).
     * Existing STRONG users always call with false.
     */
    fun authenticate(
        request: BiometricRequest,
        allowWeak: Boolean,
        listener: (BiometricAuthResult) -> Unit,
    ) {
        Log.d(TAG, "authenticate: allowWeak=$allowWeak request=${request::class.simpleName}")
        when (val prepared = prepareCipher(request, allowWeak)) {
            is PreparedCipher.Ready -> controller.authenticate(
                crypto = BiometricPrompt.CryptoObject(prepared.cipher),
                onSuccess = { cipher -> onPromptSuccess(request, cipher, allowWeak, listener) },
                onError = { code -> onPromptError(code, listener) },
                // A scan mismatch (wrong finger/face) is retryable: the OS
                // prompt stays open and a success or error always follows, so
                // there is nothing to report here.
                onFailed = { },
            )

            // Keystore could not authorize a cipher before authentication:
            // let the OS prompt authenticate first, then create the cipher.
            PreparedCipher.NoCryptoPrompt -> {
                Log.d(
                    TAG,
                    "prompt: crypto-less fallback (cipher could not be prepared before auth) allowWeak=$allowWeak"
                )
                controller.authenticate(
                    crypto = null,
                    onSuccess = { cipher -> onPromptSuccess(request, cipher, allowWeak, listener) },
                    onError = { code -> onPromptError(code, listener) },
                    onFailed = { },
                )
            }

            is PreparedCipher.Aborted -> {
                Log.d(TAG, "prompt: aborted before launch → ${prepared.result::class.simpleName}")
                listener(prepared.result)
            }
        }
    }

    fun disable() {
        store.clearBlob()
        store.deleteKey()
    }

    /**
     * Creates an ENCRYPT cipher, retrying once with a fresh Keystore key when
     * the existing one is permanently invalidated (biometrics changed).
     */
    private fun createEncryptCipherWithRetry(allowWeak: Boolean = false): Result<Cipher> {
        var result = store.createEncryptCipher()
        if (result.exceptionOrNull() is BiometricTokenError.KeyInvalidated) {
            Log.d(TAG, "cipher create: KeyInvalidated → fresh key and retry (allowWeak=$allowWeak)")
            store.deleteKey()
            try {
                store.createKeyIfNeeded(allowWeak)
            } catch (e: BiometricTokenError.NoSecureLockScreen) {
                return Result.failure(e)
            }
            result = store.createEncryptCipher()
        }
        return result
    }

    private fun prepareCipher(
        request: BiometricRequest,
        allowWeak: Boolean = false
    ): PreparedCipher = when (request) {
        is BiometricRequest.Encrypt -> {
            try {
                store.createKeyIfNeeded(allowWeak)
            } catch (e: BiometricTokenError.NoSecureLockScreen) {
                Log.w(TAG, "cipher create: fail, exception=${e::class.simpleName}")
                return PreparedCipher.Aborted(BiometricAuthResult.NoSecureLockScreen)
            }
            val result = createEncryptCipherWithRetry(allowWeak)
            if (result.isSuccess) {
                Log.d(TAG, "cipher create: success")
                PreparedCipher.Ready(result.getOrThrow())
            } else {
                val error = result.exceptionOrNull() as? BiometricTokenError
                Log.w(
                    TAG,
                    "cipher create: fail, exception=${error?.javaClass?.simpleName ?: "unknown"}"
                )
                when (error) {
                    BiometricTokenError.NoSecureLockScreen ->
                        PreparedCipher.Aborted(BiometricAuthResult.NoSecureLockScreen)

                    // The Keystore cannot provide the cipher on this device
                    // (e.g. emulator Keymint without AES/GCM): never workable.
                    BiometricTokenError.NotAvailable ->
                        PreparedCipher.Aborted(BiometricAuthResult.NotAvailable)

                    // KeyLocked (user not authenticated) / StorageUnavailable:
                    // the prompt is the legitimate authorizing path.
                    else -> PreparedCipher.NoCryptoPrompt
                }
            }
        }

        BiometricRequest.Decrypt -> {
            val result = store.createDecryptCipher()
            when (val error = result.exceptionOrNull()) {
                null -> {
                    Log.d(TAG, "cipher create: success (decrypt)")
                    PreparedCipher.Ready(result.getOrThrow())
                }

                BiometricTokenError.NotEnabled -> PreparedCipher.Aborted(BiometricAuthResult.NotEnabled)
                BiometricTokenError.KeyInvalidated -> PreparedCipher.Aborted(BiometricAuthResult.KeyInvalidated)
                BiometricTokenError.NoSecureLockScreen ->
                    PreparedCipher.Aborted(BiometricAuthResult.NoSecureLockScreen)

                BiometricTokenError.NotAvailable ->
                    PreparedCipher.Aborted(BiometricAuthResult.NotAvailable)

                BiometricTokenError.KeyLocked,
                BiometricTokenError.StorageUnavailable,
                    -> {
                    Log.w(
                        TAG,
                        "cipher create: fail (decrypt), exception=${error::class.simpleName}"
                    )
                    PreparedCipher.NoCryptoPrompt
                }

                else -> {
                    Log.w(
                        TAG,
                        "cipher create: fail (decrypt), exception=${error::class.simpleName}"
                    )
                    PreparedCipher.NoCryptoPrompt
                }
            }
        }
    }

    private fun onPromptSuccess(
        request: BiometricRequest,
        cipher: Cipher?,
        allowWeak: Boolean = false,
        listener: (BiometricAuthResult) -> Unit,
    ) {
        when (request) {
            is BiometricRequest.Encrypt -> {
                // Crypto-less fallback: the cipher is created only now, when the
                // Keystore key has been authorized by the user authentication.
                val active = cipher ?: createEncryptCipherWithRetry(allowWeak).getOrElse { e ->
                    Log.w(TAG, "cipher create: fail after auth, exception=${e::class.simpleName}")
                    listener(
                        when (e) {
                            BiometricTokenError.NoSecureLockScreen ->
                                BiometricAuthResult.NoSecureLockScreen

                            BiometricTokenError.NotAvailable ->
                                BiometricAuthResult.NotAvailable

                            else -> BiometricAuthResult.Failed
                        }
                    )
                    return
                }
                val ciphertext = active.doFinal(request.tier2)
                store.saveKeyBlob(active, ciphertext)
                Log.d(TAG, "auth result: Encrypted")
                listener(BiometricAuthResult.Encrypted)
            }

            BiometricRequest.Decrypt -> {
                val ciphertext = store.loadKeyCiphertext()
                if (ciphertext == null) {
                    listener(BiometricAuthResult.NotEnabled)
                    return
                }
                val active = cipher ?: store.createDecryptCipher().getOrElse { e ->
                    Log.w(
                        TAG,
                        "cipher create: fail after auth (decrypt), exception=${e::class.simpleName}"
                    )
                    listener(
                        when (e) {
                            BiometricTokenError.KeyInvalidated ->
                                BiometricAuthResult.KeyInvalidated

                            BiometricTokenError.NotAvailable ->
                                BiometricAuthResult.NotAvailable

                            else -> BiometricAuthResult.Failed
                        }
                    )
                    return
                }
                val tier2 = active.doFinal(ciphertext)
                Log.d(TAG, "auth result: Unlocked")
                listener(BiometricAuthResult.Unlocked(tier2))
            }
        }
    }

    private fun onPromptError(code: Int, listener: (BiometricAuthResult) -> Unit) {
        val failure = mapPromptError(code)
        when (failure) {
            BiometricPromptFailure.LOCKED_OUT -> listener(BiometricAuthResult.LockedOut)
            BiometricPromptFailure.NOT_AVAILABLE -> listener(BiometricAuthResult.NotAvailable)
            BiometricPromptFailure.CANCELLED -> listener(BiometricAuthResult.Cancelled)
            BiometricPromptFailure.UNKNOWN -> listener(BiometricAuthResult.Failed)
        }
        Log.d(TAG, "prompt error: $failure (code=$code)")
    }

    private sealed interface PreparedCipher {
        data class Ready(val cipher: Cipher) : PreparedCipher

        /** Prompt runs without a CryptoObject; cipher created after auth. */
        data object NoCryptoPrompt : PreparedCipher

        data class Aborted(val result: BiometricAuthResult) : PreparedCipher
    }
}
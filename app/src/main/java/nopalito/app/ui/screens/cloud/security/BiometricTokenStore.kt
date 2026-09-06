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
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Two-level blob for the biometric cloud unlock.
 *
 * - Tier-1: AES-256-GCM key inside the Android Keystore, protected by
 *   `setUserAuthenticationRequired`. Only `BiometricPrompt` (CryptoObject)
 *   releases it. Persisted: nothing — the key lives in the Keystore itself.
 * - Tier-2: random 256-bit session key that exists ONLY in memory
 *   ([BiometricUnlockSession]). It is wrapped at rest as
 *   `E(Tier-1, Tier-2)` (the "key blob") and it wraps the refresh token as
 *   `E(Tier-2, refresh)` (the "refresh blob").
 *
 * Consequences:
 * - One prompt unwraps Tier-2; every later token operation (rotation,
 *   re-encryption after login) runs with Tier-2 in memory and shows NO
 *   second prompt.
 * - The refresh token is never persisted in plaintext and never touched by
 *   `BiometricPrompt` at all.
 * - The refresh blob is rewritten atomically in a single `commit()`; a failed
 *   commit leaves the previous blob on disk untouched.
 *
 * The store never sees plaintext tokens. Callers obtain a [Cipher] bound to
 * the Tier-1 key, run it through `BiometricPrompt` (CryptoObject) so the OS
 * releases the key on success, and only then call [saveKeyBlob]/[loadKeyBlob].
 *
 * Authenticators: default is `BIOMETRIC_STRONG` (Class 3) for all existing
 * users. `BIOMETRIC_WEAK` (Class 2, e.g. 2D face) is only used as a fallback
 * when STRONG is unavailable and the user explicitly accepts the weaker
 * security after a warning (see [BiometricCapability] and
 * [BiometricWeakPreference]). `DEVICE_CREDENTIAL` is only armed on API 30+
 * where it can actually release a crypto key.
 *
 * AndroidKeyStore itself cannot run inside a JVM test (Robolectric provides
 * no `AndroidKeyStore` provider), so the Keystore-touching operations live
 * behind [CryptoKeyStore]; tests plug a real-AES implementation in and the
 * only code they cannot drive is the thin [AndroidCryptoKeyStore] wrapper.
 */

/**
 * Stable reason why a Keystore-bound [Cipher] could not be produced.
 *
 * These are typed error VALUES, not real exceptions: they are never thrown or
 * caught, they only travel through [kotlin.Result.failure] and are matched
 * with exhaustive `when`. Singleton identity is intentional (no state, no
 * stack traces needed), so the "exception should not be an object" inspection
 * does not apply here.
 */
@Suppress("ExceptionObjectExceptionShouldNotBeObject")
sealed class BiometricTokenError : Throwable() {
    /**
     * Throwable is Serializable, so deserializing a data object would create a
     * fresh instance and break singleton identity (code matches these with
     * `when` and passes them through `Result`). They are never serialized in
     * practice, but readResolve keeps the serialization contract sound.
     */
    // Invoked reflectively by ObjectInputStream during deserialization, so
    // static analysis cannot see a call site. Deliberately kept.
    @Suppress("unused")
    private fun readResolve(): Any = when (this) {
        NotEnabled -> NotEnabled
        KeyInvalidated -> KeyInvalidated
        KeyLocked -> KeyLocked
        NoSecureLockScreen -> NoSecureLockScreen
        NotAvailable -> NotAvailable
        StorageUnavailable -> StorageUnavailable
    }

    /** No key or no stored blob exists yet. */
    data object NotEnabled : BiometricTokenError()

    /**
     * The key was permanently invalidated (fingerprint/face enrolled changed).
     * The existing encrypted blob can never be decrypted again: the biometric
     * mode must be reset and the token re-encrypted from a fresh login.
     */
    data object KeyInvalidated : BiometricTokenError()

    /** The key exists but the OS would not release it without fresh auth. */
    data object KeyLocked : BiometricTokenError()

    /**
     * The device has no secure lock screen (no PIN/pattern/password): the
     * Keystore refuses to create a key that requires user authentication.
     */
    data object NoSecureLockScreen : BiometricTokenError()

    /**
     * The Keystore cannot provide the required cipher at all (e.g. emulators
     * whose Keymint does not implement AES/GCM). Biometric unlock can never
     * work on this device: report it as unavailable instead of failing.
     */
    data object NotAvailable : BiometricTokenError()

    /** Unexpected Keystore/provider failure (IO, algorithm, ...). */
    data object StorageUnavailable : BiometricTokenError()
}

private const val SECURE_LOCK_SCREEN_MESSAGE =
    "Secure lock screen must be enabled to create keys requiring user authentication"

internal fun mapCipherInitFailure(e: Throwable): BiometricTokenError = when (e) {
    is KeyPermanentlyInvalidatedException -> BiometricTokenError.KeyInvalidated
    is UserNotAuthenticatedException -> BiometricTokenError.KeyLocked
    is java.security.NoSuchAlgorithmException -> BiometricTokenError.NotAvailable
    else -> when {
        e.message?.contains(SECURE_LOCK_SCREEN_MESSAGE) == true ->
            BiometricTokenError.NoSecureLockScreen

        e.cause?.message?.contains(SECURE_LOCK_SCREEN_MESSAGE) == true ->
            BiometricTokenError.NoSecureLockScreen

        else -> BiometricTokenError.StorageUnavailable
    }
}

/**
 * All operations that touch the actual (Android) key store. Production uses
 * [AndroidCryptoKeyStore]; JVM tests substitute a real-AES in-memory stand-in.
 */
internal interface CryptoKeyStore {
    fun hasKey(alias: String): Boolean
    fun createKey(alias: String)
    fun createKey(alias: String, allowWeak: Boolean)
    fun deleteKey(alias: String)
    fun createCipher(mode: Int, alias: String, iv: ByteArray?): Result<Cipher>
}

private const val TAG = "BiometricTokenStore"

internal class AndroidCryptoKeyStore(
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : CryptoKeyStore {
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEY_STORE_PROVIDER).apply { load(null) }
    }

    override fun hasKey(alias: String): Boolean =
        runCatching { keyStore.containsAlias(alias) }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.R)
    override fun createKey(alias: String) = createKey(alias, allowWeak = false)

    @RequiresApi(Build.VERSION_CODES.R)
    override fun createKey(alias: String, allowWeak: Boolean) {
        if (hasKey(alias)) return
        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, KEY_STORE_PROVIDER)
        val specBuilder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (sdkInt >= Build.VERSION_CODES.R) {
            if (allowWeak) {
                // WEAK fallback (Class 2, e.g. 2D face): allow any biometric
                // including weak. AUTH_BIOMETRIC_WEAK is not exposed in
                // KeyProperties (only STRONG and DEVICE_CREDENTIAL are public
                // on API 36), so we use the legacy validity-duration path
                // which does not restrict to STRONG. The key will be released
                // by WEAK or STRONG. Device credential fallback is handled via
                // BiometricPrompt WEAK_OR_DEVICE_CREDENTIAL; after PIN auth the
                // legacy key is also considered authenticated.
                // Documented security difference: WEAK has higher FAR.
                @Suppress("DEPRECATION")
                specBuilder.setUserAuthenticationValidityDurationSeconds(0)
            } else {
                // Default STRONG path (existing users): requires Class 3.
                // AUTH_BIOMETRIC_STRONG covers strong (Class 3) fingerprint + face + iris;
                // the Keystore is only released if the OS validates a strong biometric
                // or the device PIN/pattern/password. Never AUTH_BIOMETRIC_WEAK: a weak
                // face must not release a strong key (see BiometricCapability).
                specBuilder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            }
        } else {
            // API 26-29: no AUTH_DEVICE_CREDENTIAL, use legacy validity for both
            @Suppress("DEPRECATION")
            specBuilder.setUserAuthenticationValidityDurationSeconds(0)
        }
        try {
            generator.init(specBuilder.build())
            generator.generateKey()
        } catch (e: InvalidAlgorithmParameterException) {
            // No secure lock screen on the device: the Keystore refuses to
            // create a key that requires user authentication.
            throw mapCipherInitFailure(e)
        }
    }

    override fun deleteKey(alias: String) {
        if (hasKey(alias)) keyStore.deleteEntry(alias)
    }

    override fun createCipher(mode: Int, alias: String, iv: ByteArray?): Result<Cipher> {
        if (!hasKey(alias)) return Result.failure(BiometricTokenError.NotEnabled)
        return try {
            val cipher = newCipher()
            val key = keyStore.getKey(alias, null) as SecretKey
            if (iv != null) {
                cipher.init(mode, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            } else {
                cipher.init(mode, key)
            }
            Result.success(cipher)
        } catch (e: Throwable) {
            Log.w(TAG, "createCipher($mode): ${e::class.simpleName}: ${e.message}")
            Result.failure(mapCipherInitFailure(e))
        }
    }

    /**
     * Some Keystore implementations (Samsung One UI) do not register
     * `AES/GCM/NoPadding` under the `AndroidKeyStore` provider name and throw
     * `NoSuchAlgorithmException`; falling back to provider resolution returns
     * a cipher that still binds to the Keystore key.
     */
    private fun newCipher(): Cipher = try {
        Cipher.getInstance(CIPHER_TRANSFORMATION, KEY_STORE_PROVIDER)
    } catch (_: java.security.NoSuchAlgorithmException) {
        Log.w(
            TAG,
            "AndroidKeyStore does not provide $CIPHER_TRANSFORMATION; using provider resolution"
        )
        Cipher.getInstance(CIPHER_TRANSFORMATION)
    }

    private companion object {
        const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        const val KEY_STORE_PROVIDER = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}

class BiometricTokenStore internal constructor(
    private val preferences: SharedPreferences,
    private val crypto: CryptoKeyStore = AndroidCryptoKeyStore(),
) {
    companion object {
        const val PREFERENCES_NAME = "cloud_biometric_store"
        const val KEY_ALIAS = "cloud_biometric_refresh_v1"
        const val KEY_IV_KEY = "tier1_iv_v1"
        const val KEY_CT_KEY = "tier1_ciphertext_v1"
        const val REFRESH_IV_KEY = "tier2_iv_v1"
        const val REFRESH_CT_KEY = "tier2_ciphertext_v1"

        fun open(context: Context): BiometricTokenStore {
            val preferences =
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            return BiometricTokenStore(preferences)
        }
    }

    val isEnabled: Boolean
        get() = hasKey() && hasKeyBlob() && hasRefreshBlob()

    fun hasKey(): Boolean = crypto.hasKey(KEY_ALIAS)

    fun hasKeyBlob(): Boolean =
        preferences.contains(KEY_IV_KEY) && preferences.contains(KEY_CT_KEY)

    fun hasRefreshBlob(): Boolean =
        preferences.contains(REFRESH_IV_KEY) && preferences.contains(REFRESH_CT_KEY)

    fun hasBlob(): Boolean = hasKeyBlob() && hasRefreshBlob()

    /** Idempotent: creates the Keystore key if it does not exist yet (STRONG default). */
    fun createKeyIfNeeded() = crypto.createKey(KEY_ALIAS)

    /** Idempotent: creates key allowing WEAK fallback (Class 2) when STRONG unavailable. */
    fun createKeyIfNeeded(allowWeak: Boolean) = crypto.createKey(KEY_ALIAS, allowWeak)

    fun deleteKey() = crypto.deleteKey(KEY_ALIAS)

    fun clearBlob() {
        preferences.edit {
            remove(KEY_IV_KEY).remove(KEY_CT_KEY)
                .remove(REFRESH_IV_KEY).remove(REFRESH_CT_KEY)
        }
    }

    /**
     * Cipher to hand to `BiometricPrompt` (ENCRYPT_MODE) to wrap Tier-2 with
     * Tier-1. Once the user is authenticated the caller encrypts the Tier-2
     * bytes and persists them with [saveKeyBlob]. The IV is generated by the
     * OS and read from `cipher.iv`.
     */
    fun createEncryptCipher(): Result<Cipher> =
        crypto.createCipher(Cipher.ENCRYPT_MODE, KEY_ALIAS, null)

    /**
     * Cipher to hand to `BiometricPrompt` (DECRYPT_MODE), pre-seeded with the
     * IV of the stored key blob. Fails with [BiometricTokenError.NotEnabled]
     * when there is nothing to unwrap.
     */
    fun createDecryptCipher(): Result<Cipher> {
        val iv = preferences.getString(KEY_IV_KEY, null)
            ?.let(::base64Decode)
            ?: return Result.failure(BiometricTokenError.NotEnabled)
        return crypto.createCipher(Cipher.DECRYPT_MODE, KEY_ALIAS, iv)
    }

    /** Persists `E(Tier-1, Tier-2)` using the IV the prompt-bound cipher produced. */
    fun saveKeyBlob(cipher: Cipher, tier2Ciphertext: ByteArray) {
        val iv = cipher.iv ?: throw IllegalStateException("Cipher did not generate an IV")
        preferences.edit {
            putString(KEY_IV_KEY, base64Encode(iv))
                .putString(KEY_CT_KEY, base64Encode(tier2Ciphertext))
        }
    }

    fun loadKeyCiphertext(): ByteArray? =
        preferences.getString(KEY_CT_KEY, null)?.let(::base64Decode)

    /**
     * Atomically rewrites `E(Tier-2, refresh)` with a caller-supplied IV.
     * A single [SharedPreferences.Editor.commit] writes both fields in one
     * transaction; a failed commit leaves the previous blob untouched.
     * Returns whether the commit succeeded.
     *
     * The platform API is used on purpose: the KTX `edit` extension returns
     * Unit, but the refresh-token rotation (BiometricUnlockSession
     * .rotateRefreshToken) must detect a failed commit to keep the previous
     * blob intact.
     */
    @Suppress("UseKtx")
    fun saveRefreshBlob(iv: ByteArray, ciphertext: ByteArray): Boolean =
        preferences.edit()
            .putString(REFRESH_IV_KEY, base64Encode(iv))
            .putString(REFRESH_CT_KEY, base64Encode(ciphertext))
            .commit()

    fun loadRefreshIv(): ByteArray? =
        preferences.getString(REFRESH_IV_KEY, null)?.let(::base64Decode)

    fun loadRefreshCiphertext(): ByteArray? =
        preferences.getString(REFRESH_CT_KEY, null)?.let(::base64Decode)

    private fun base64Encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun base64Decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)
}
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

import android.app.Application
import android.content.Context
import androidx.biometric.BiometricPrompt
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BiometricAuthManagerTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun freshPrefs(): android.content.SharedPreferences =
        context.getSharedPreferences(
            BiometricTokenStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).apply { edit().clear().commit() }

    private fun newStore(crypto: CryptoKeyStore = JvmCryptoKeyStore()): BiometricTokenStore =
        BiometricTokenStore(freshPrefs(), crypto)

    // --- prompt error mapping (pure) ---

    @Test
    fun `lockout codes map to LOCKED_OUT`() {
        assertThat(mapPromptError(BiometricPrompt.ERROR_LOCKOUT))
            .isEqualTo(BiometricPromptFailure.LOCKED_OUT)
        assertThat(mapPromptError(BiometricPrompt.ERROR_LOCKOUT_PERMANENT))
            .isEqualTo(BiometricPromptFailure.LOCKED_OUT)
    }

    @Test
    fun `hardware and enrollment codes map to NOT_AVAILABLE`() {
        assertThat(mapPromptError(BiometricPrompt.ERROR_HW_UNAVAILABLE))
            .isEqualTo(BiometricPromptFailure.NOT_AVAILABLE)
        assertThat(mapPromptError(BiometricPrompt.ERROR_NO_BIOMETRICS))
            .isEqualTo(BiometricPromptFailure.NOT_AVAILABLE)
        assertThat(mapPromptError(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL))
            .isEqualTo(BiometricPromptFailure.NOT_AVAILABLE)
    }

    @Test
    fun `user dismissal codes map to CANCELLED`() {
        assertThat(mapPromptError(BiometricPrompt.ERROR_CANCELED))
            .isEqualTo(BiometricPromptFailure.CANCELLED)
        assertThat(mapPromptError(BiometricPrompt.ERROR_USER_CANCELED))
            .isEqualTo(BiometricPromptFailure.CANCELLED)
        assertThat(mapPromptError(BiometricPrompt.ERROR_NEGATIVE_BUTTON))
            .isEqualTo(BiometricPromptFailure.CANCELLED)
    }

    @Test
    fun `anything else maps to UNKNOWN`() {
        assertThat(mapPromptError(12345)).isEqualTo(BiometricPromptFailure.UNKNOWN)
    }

    // --- enable / unlock flows ---

    @Test
    fun `enable wraps tier two, persists and reports Encrypted`() {
        val store = newStore()
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }
        val tier2 = ByteArray(32) { it.toByte() }

        var result: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Encrypt(tier2)) { result = it }

        assertThat(controller.launched).isTrue()
        controller.succeed(controller.lastCrypto!!.cipher!!)

        assertThat(result).isEqualTo(BiometricAuthResult.Encrypted)
        assertThat(store.hasKeyBlob()).isTrue()
        val dec = store.createDecryptCipher().getOrThrow()
        assertThat(dec.doFinal(store.loadKeyCiphertext()!!)).isEqualTo(tier2)
    }

    @Test
    fun `unlock unwraps tier two and hands it over the callback only`() {
        val store = newStore()
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }
        val tier2 = ByteArray(32) { (it * 2).toByte() }

        var enableResult: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Encrypt(tier2)) { enableResult = it }
        controller.succeed(controller.lastCrypto!!.cipher!!)
        assertThat(enableResult).isEqualTo(BiometricAuthResult.Encrypted)

        var unlockResult: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Decrypt) { unlockResult = it }
        controller.succeed(controller.lastCrypto!!.cipher!!)

        val unlocked = unlockResult as BiometricAuthResult.Unlocked
        assertThat(unlocked.tier2).isEqualTo(tier2)
    }

    @Test
    fun `unlock with no biometric mode reports NotEnabled and never launches the prompt`() {
        val store = newStore()
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }

        var result: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Decrypt) { result = it }

        assertThat(result).isEqualTo(BiometricAuthResult.NotEnabled)
        assertThat(controller.launched).isFalse()
    }

    @Test
    fun `enable retries with a fresh key after invalidation`() {
        val store = newStore(FailingOnceCryptoKeyStore(JvmCryptoKeyStore()))
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }

        var result: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Encrypt(ByteArray(32))) { result = it }
        controller.succeed(controller.lastCrypto!!.cipher!!)

        assertThat(result).isEqualTo(BiometricAuthResult.Encrypted)
        assertThat(store.hasKeyBlob()).isTrue()
    }

    @Test
    fun `unlock after key invalidation reports KeyInvalidated`() {
        val prefs = freshPrefs()

        val store1 = BiometricTokenStore(prefs, JvmCryptoKeyStore())
        val controller1 = FakeController()
        val manager1 = BiometricAuthManager(store1) { controller1 }
        var enableResult: BiometricAuthResult? = null
        manager1.authenticate(BiometricRequest.Encrypt(ByteArray(32))) { enableResult = it }
        controller1.succeed(controller1.lastCrypto!!.cipher!!)
        assertThat(enableResult).isEqualTo(BiometricAuthResult.Encrypted)

        // Same blob on disk, but now the key is reported as permanently
        // invalidated (biometrics changed on the device).
        val store2 = BiometricTokenStore(prefs, FailingOnceCryptoKeyStore(JvmCryptoKeyStore()))
        val controller2 = FakeController()
        val manager2 = BiometricAuthManager(store2) { controller2 }

        var unlockResult: BiometricAuthResult? = null
        manager2.authenticate(BiometricRequest.Decrypt) { unlockResult = it }

        assertThat(unlockResult).isEqualTo(BiometricAuthResult.KeyInvalidated)
        assertThat(controller2.launched).isFalse()
    }

    @Test
    fun `prompt error maps to LockedOut`() {
        val store = newStore()
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }

        var result: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Encrypt(ByteArray(32))) { result = it }
        controller.fail(BiometricPrompt.ERROR_LOCKOUT)

        assertThat(result).isEqualTo(BiometricAuthResult.LockedOut)
        assertThat(store.hasKeyBlob()).isFalse()
    }

    @Test
    fun `negative button maps to Cancelled and nothing is persisted`() {
        val store = newStore()
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }

        var result: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Encrypt(ByteArray(32))) { result = it }
        controller.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON)

        assertThat(result).isEqualTo(BiometricAuthResult.Cancelled)
        assertThat(store.hasBlob()).isFalse()
    }

    @Test
    fun `scan mismatch keeps the prompt open and success still completes`() {
        val store = newStore()
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }

        var result: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Encrypt(ByteArray(32))) { result = it }
        controller.failedAttempt()
        controller.failedAttempt()

        assertThat(result).isNull()
        assertThat(store.hasKeyBlob()).isFalse()

        controller.succeed(controller.lastCrypto!!.cipher!!)

        assertThat(result).isEqualTo(BiometricAuthResult.Encrypted)
        assertThat(store.hasKeyBlob()).isTrue()
    }

    @Test
    fun `encrypt without a secure lock screen aborts without launching the prompt`() {
        val store = newStore(NoSecureLockScreenCryptoKeyStore())
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }

        var result: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Encrypt(ByteArray(32))) { result = it }

        assertThat(result).isEqualTo(BiometricAuthResult.NoSecureLockScreen)
        assertThat(controller.launched).isFalse()
        assertThat(store.hasKey()).isFalse()
    }

    @Test
    fun `enable falls back to a crypto-less prompt when cipher creation fails and completes after auth`() {
        val store = newStore(
            FailingEncryptOnceCryptoKeyStore(
                JvmCryptoKeyStore(),
                BiometricTokenError.KeyLocked
            )
        )
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }
        val tier2 = ByteArray(32) { it.toByte() }

        var result: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Encrypt(tier2)) { result = it }

        // The Keystore refused the cipher before auth (KeyLocked): the prompt
        // must still launch, WITHOUT a CryptoObject.
        assertThat(controller.launched).isTrue()
        assertThat(controller.lastCrypto).isNull()

        controller.succeed(null)

        assertThat(result).isEqualTo(BiometricAuthResult.Encrypted)
        assertThat(store.hasKeyBlob()).isTrue()
        val dec = store.createDecryptCipher().getOrThrow()
        assertThat(dec.doFinal(store.loadKeyCiphertext()!!)).isEqualTo(tier2)
    }

    @Test
    fun `enable crypto-less fallback reports Failed when the post-auth cipher creation also fails`() {
        val store = newStore(
            AlwaysFailingEncryptCryptoKeyStore(
                JvmCryptoKeyStore(),
                BiometricTokenError.KeyLocked
            )
        )
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }

        var result: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Encrypt(ByteArray(32))) { result = it }

        assertThat(controller.launched).isTrue()
        assertThat(controller.lastCrypto).isNull()
        controller.succeed(null)

        assertThat(result).isEqualTo(BiometricAuthResult.Failed)
        assertThat(store.hasKeyBlob()).isFalse()
    }

    @Test
    fun `enable falls back to a crypto-less prompt when the key stays invalidated after the retry`() {
        val store = newStore(
            AlwaysFailingEncryptCryptoKeyStore(
                JvmCryptoKeyStore(),
                BiometricTokenError.KeyInvalidated
            )
        )
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }

        var result: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Encrypt(ByteArray(32))) { result = it }

        // The fresh-key retry also fails: fall back to the crypto-less prompt
        // instead of aborting, so the user can still authenticate.
        assertThat(controller.launched).isTrue()
        assertThat(controller.lastCrypto).isNull()
        controller.succeed(null)

        assertThat(result).isEqualTo(BiometricAuthResult.Failed)
        assertThat(store.hasKeyBlob()).isFalse()
    }

    @Test
    fun `unlock falls back to a crypto-less prompt when the decrypt cipher cannot be prepared`() {
        val prefs = freshPrefs()
        val shared = JvmCryptoKeyStore()

        val store1 = BiometricTokenStore(prefs, shared)
        val controller1 = FakeController()
        val manager1 = BiometricAuthManager(store1) { controller1 }
        val tier2 = ByteArray(32) { (it * 2).toByte() }
        var enableResult: BiometricAuthResult? = null
        manager1.authenticate(BiometricRequest.Encrypt(tier2)) { enableResult = it }
        controller1.succeed(controller1.lastCrypto!!.cipher!!)
        assertThat(enableResult).isEqualTo(BiometricAuthResult.Encrypted)

        // Same blobs, but now the Keystore refuses the decrypt cipher until
        // the user authenticates (PIN-only device, no enrolled biometrics).
        val store2 = BiometricTokenStore(
            prefs,
            FailingDecryptOnceCryptoKeyStore(shared, BiometricTokenError.KeyLocked)
        )
        val controller2 = FakeController()
        val manager2 = BiometricAuthManager(store2) { controller2 }

        var unlockResult: BiometricAuthResult? = null
        manager2.authenticate(BiometricRequest.Decrypt) { unlockResult = it }

        assertThat(controller2.launched).isTrue()
        assertThat(controller2.lastCrypto).isNull()
        controller2.succeed(null)

        val unlocked = unlockResult as BiometricAuthResult.Unlocked
        assertThat(unlocked.tier2).isEqualTo(tier2)
    }

    @Test
    fun `disable removes key and blobs`() {
        val store = newStore()
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }

        var result: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Encrypt(ByteArray(32))) { result = it }
        controller.succeed(controller.lastCrypto!!.cipher!!)
        assertThat(result).isEqualTo(BiometricAuthResult.Encrypted)
        assertThat(store.hasKey()).isTrue()
        assertThat(store.hasKeyBlob()).isTrue()

        manager.disable()

        assertThat(manager.isEnabled).isFalse()
        assertThat(store.hasKey()).isFalse()
        assertThat(store.hasBlob()).isFalse()
    }

    @Test
    fun `keystore without AES-GCM reports NotAvailable without launching the prompt`() {
        val store = newStore(
            AlwaysFailingEncryptCryptoKeyStore(
                JvmCryptoKeyStore(),
                BiometricTokenError.NotAvailable
            )
        )
        val controller = FakeController()
        val manager = BiometricAuthManager(store) { controller }

        var result: BiometricAuthResult? = null
        manager.authenticate(BiometricRequest.Encrypt(ByteArray(32))) { result = it }

        // A device whose Keystore cannot provide the cipher at all (emulator
        // Keymint without AES/GCM): report unavailable, never prompt.
        assertThat(result).isEqualTo(BiometricAuthResult.NotAvailable)
        assertThat(controller.launched).isFalse()
        assertThat(store.hasKeyBlob()).isFalse()
    }

    // --- fakes ---

    private class FakeController : BiometricPromptController {
        var launched = false
        var lastCrypto: BiometricPrompt.CryptoObject? = null
        private var onSuccess: ((Cipher?) -> Unit)? = null
        private var onError: ((Int) -> Unit)? = null
        private var onFailed: (() -> Unit)? = null

        override fun authenticate(
            crypto: BiometricPrompt.CryptoObject?,
            onSuccess: (Cipher?) -> Unit,
            onError: (promptErrorCode: Int) -> Unit,
            onFailed: () -> Unit,
        ) {
            launched = true
            lastCrypto = crypto
            this.onSuccess = onSuccess
            this.onError = onError
            this.onFailed = onFailed
        }

        fun succeed(cipher: Cipher?) = onSuccess?.invoke(cipher)

        fun fail(code: Int) = onError?.invoke(code)

        fun failedAttempt() = onFailed?.invoke()
    }

    /** Fails key creation like a device without a secure lock screen. */
    private class NoSecureLockScreenCryptoKeyStore : CryptoKeyStore {
        override fun hasKey(alias: String): Boolean = false

        override fun createKey(alias: String) {
            throw BiometricTokenError.NoSecureLockScreen
        }

        override fun createKey(alias: String, allowWeak: Boolean) {
            throw BiometricTokenError.NoSecureLockScreen
        }

        override fun deleteKey(alias: String) = Unit

        override fun createCipher(mode: Int, alias: String, iv: ByteArray?): Result<Cipher> =
            Result.failure(BiometricTokenError.NotEnabled)
    }

    /** Fails the first cipher creation with KeyInvalidated, then delegates. */
    private class FailingOnceCryptoKeyStore(
        private val delegate: CryptoKeyStore,
    ) : CryptoKeyStore {
        private var shouldFail = true

        override fun hasKey(alias: String): Boolean = delegate.hasKey(alias)

        override fun createKey(alias: String) = delegate.createKey(alias)

        override fun createKey(alias: String, allowWeak: Boolean) =
            delegate.createKey(alias, allowWeak)

        override fun deleteKey(alias: String) = delegate.deleteKey(alias)

        override fun createCipher(mode: Int, alias: String, iv: ByteArray?): Result<Cipher> =
            if (shouldFail) {
                shouldFail = false
                Result.failure(BiometricTokenError.KeyInvalidated)
            } else {
                delegate.createCipher(mode, alias, iv)
            }
    }

    /** Fails the first ENCRYPT cipher creation with [error], then delegates. */
    private class FailingEncryptOnceCryptoKeyStore(
        private val delegate: CryptoKeyStore,
        private val error: BiometricTokenError,
    ) : CryptoKeyStore {
        private var shouldFail = true

        override fun hasKey(alias: String): Boolean = delegate.hasKey(alias)

        override fun createKey(alias: String) = delegate.createKey(alias)

        override fun createKey(alias: String, allowWeak: Boolean) =
            delegate.createKey(alias, allowWeak)

        override fun deleteKey(alias: String) = delegate.deleteKey(alias)

        override fun createCipher(mode: Int, alias: String, iv: ByteArray?): Result<Cipher> =
            if (shouldFail && mode == Cipher.ENCRYPT_MODE) {
                shouldFail = false
                Result.failure(error)
            } else {
                delegate.createCipher(mode, alias, iv)
            }
    }

    /** Fails the first DECRYPT cipher creation with [error], then delegates. */
    private class FailingDecryptOnceCryptoKeyStore(
        private val delegate: CryptoKeyStore,
        private val error: BiometricTokenError,
    ) : CryptoKeyStore {
        private var shouldFail = true

        override fun hasKey(alias: String): Boolean = delegate.hasKey(alias)

        override fun createKey(alias: String) = delegate.createKey(alias)

        override fun createKey(alias: String, allowWeak: Boolean) =
            delegate.createKey(alias, allowWeak)

        override fun deleteKey(alias: String) = delegate.deleteKey(alias)

        override fun createCipher(mode: Int, alias: String, iv: ByteArray?): Result<Cipher> =
            if (shouldFail && mode == Cipher.DECRYPT_MODE) {
                shouldFail = false
                Result.failure(error)
            } else {
                delegate.createCipher(mode, alias, iv)
            }
    }

    /** Fails every ENCRYPT cipher creation with [error], delegating the rest. */
    private class AlwaysFailingEncryptCryptoKeyStore(
        private val delegate: CryptoKeyStore,
        private val error: BiometricTokenError,
    ) : CryptoKeyStore {
        override fun hasKey(alias: String): Boolean = delegate.hasKey(alias)

        override fun createKey(alias: String) = delegate.createKey(alias)

        override fun createKey(alias: String, allowWeak: Boolean) =
            delegate.createKey(alias, allowWeak)

        override fun deleteKey(alias: String) = delegate.deleteKey(alias)

        override fun createCipher(mode: Int, alias: String, iv: ByteArray?): Result<Cipher> =
            if (mode == Cipher.ENCRYPT_MODE) {
                Result.failure(error)
            } else {
                delegate.createCipher(mode, alias, iv)
            }
    }
}
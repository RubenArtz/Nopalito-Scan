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
class BiometricSessionManagerTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun freshPrefs(): android.content.SharedPreferences =
        context.getSharedPreferences(
            BiometricTokenStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).apply { edit().clear().commit() }

    private fun newManager(
        crypto: CryptoKeyStore = JvmCryptoKeyStore(),
        controller: FakeController = FakeController(),
    ): Pair<BiometricSessionManager, FakeController> {
        val store = BiometricTokenStore(freshPrefs(), crypto)
        return BiometricSessionManager(store) { controller } to controller
    }

    @Test
    fun `starts hidden and disabled`() {
        val (manager, _) = newManager()

        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Hidden)
        assertThat(manager.isEnabled).isFalse()
        assertThat(manager.hasActiveSession).isFalse()
    }

    @Test
    fun `enable persists the token with one prompt and activates tier two`() {
        val (manager, controller) = newManager()

        var outcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-token") { outcome = it }
        controller.succeed(controller.lastCrypto!!.cipher!!)

        assertThat(outcome).isEqualTo(BiometricUnlockOutcome.Enabled)
        assertThat(manager.isEnabled).isTrue()
        assertThat(manager.hasActiveSession).isTrue()
        assertThat(manager.unlockSession.decryptRefreshToken()).isEqualTo("refresh-token")
        assertThat(controller.launched).isTrue()
    }

    @Test
    fun `enable cancelled by the user reports Cancelled and stays off`() {
        val (manager, controller) = newManager()

        var outcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-token") { outcome = it }
        controller.fail(BiometricPrompt.ERROR_NEGATIVE_BUTTON)

        assertThat(outcome).isEqualTo(BiometricUnlockOutcome.Cancelled)
        assertThat(manager.isEnabled).isFalse()
        assertThat(manager.hasActiveSession).isFalse()
    }

    @Test
    fun `gate becomes Required only when mode is on and session is inactive`() {
        val (manager, controller) = newManager()

        manager.setSessionActive(false)
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Hidden)

        var outcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-token") { outcome = it }
        controller.succeed(controller.lastCrypto!!.cipher!!)
        assertThat(outcome).isEqualTo(BiometricUnlockOutcome.Enabled)

        manager.setSessionActive(false)
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Hidden)

        manager.unlockSession.wipe()
        manager.setSessionActive(false)
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Required)

        manager.setSessionActive(true)
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Hidden)
    }

    @Test
    fun `successful unlock activates tier two and hides the gate`() {
        val (manager, controller) = newManager()

        var enableOutcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-token-1") { enableOutcome = it }
        controller.succeed(controller.lastCrypto!!.cipher!!)
        assertThat(enableOutcome).isEqualTo(BiometricUnlockOutcome.Enabled)

        // Simulate Tier-2 loss (process/background); the gate must ask again.
        manager.unlockSession.wipe()
        manager.setSessionActive(false)
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Required)

        var unlockOutcome: BiometricUnlockOutcome? = null
        manager.requestUnlock { unlockOutcome = it }
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Prompting)
        controller.succeed(controller.lastCrypto!!.cipher!!)

        assertThat(unlockOutcome).isEqualTo(BiometricUnlockOutcome.Unlocked)
        assertThat(manager.hasActiveSession).isTrue()
        assertThat(manager.unlockSession.decryptRefreshToken()).isEqualTo("refresh-token-1")
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Hidden)
    }

    @Test
    fun `requestUnlock while already unlocked never launches a second prompt`() {
        val (manager, controller) = newManager()

        var enableOutcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-token") { enableOutcome = it }
        controller.succeed(controller.lastCrypto!!.cipher!!)
        assertThat(enableOutcome).isEqualTo(BiometricUnlockOutcome.Enabled)
        controller.launched = false

        var unlockOutcome: BiometricUnlockOutcome? = null
        manager.requestUnlock { unlockOutcome = it }

        assertThat(unlockOutcome).isEqualTo(BiometricUnlockOutcome.Unlocked)
        assertThat(controller.launched).isFalse()
    }

    @Test
    fun `cancelled unlock reports Cancelled and returns the gate to Required`() {
        val (manager, controller) = newManager()
        manager.enable("refresh-token") { }
        controller.succeed(controller.lastCrypto!!.cipher!!)
        manager.unlockSession.wipe()
        manager.setSessionActive(false)

        var unlockOutcome: BiometricUnlockOutcome? = null
        manager.requestUnlock { unlockOutcome = it }
        controller.fail(BiometricPrompt.ERROR_USER_CANCELED)

        assertThat(unlockOutcome).isEqualTo(BiometricUnlockOutcome.Cancelled)
        assertThat(manager.hasActiveSession).isFalse()
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Required)
    }

    @Test
    fun `locked out unlock reports LockedOut and returns the gate to Required`() {
        val (manager, controller) = newManager()
        manager.enable("refresh-token") { }
        controller.succeed(controller.lastCrypto!!.cipher!!)
        manager.unlockSession.wipe()
        manager.setSessionActive(false)

        var unlockOutcome: BiometricUnlockOutcome? = null
        manager.requestUnlock { unlockOutcome = it }
        controller.fail(BiometricPrompt.ERROR_LOCKOUT)

        assertThat(unlockOutcome).isEqualTo(BiometricUnlockOutcome.LockedOut)
        assertThat(manager.hasActiveSession).isFalse()
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Required)
    }

    @Test
    fun `requestUnlock while disabled fails immediately without launching the prompt`() {
        val (manager, controller) = newManager()

        var unlockOutcome: BiometricUnlockOutcome? = null
        manager.requestUnlock { unlockOutcome = it }

        assertThat(unlockOutcome).isEqualTo(BiometricUnlockOutcome.Failed)
        assertThat(controller.launched).isFalse()
    }

    @Test
    fun `enable with mode already on rotates without any second prompt`() {
        val (manager, controller) = newManager()
        var enableOutcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-token-1") { enableOutcome = it }
        controller.succeed(controller.lastCrypto!!.cipher!!)
        assertThat(enableOutcome).isEqualTo(BiometricUnlockOutcome.Enabled)
        controller.launched = false

        var rotateOutcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-token-2") { rotateOutcome = it }

        assertThat(rotateOutcome).isEqualTo(BiometricUnlockOutcome.Enabled)
        assertThat(manager.unlockSession.decryptRefreshToken()).isEqualTo("refresh-token-2")
        assertThat(controller.launched).isFalse()
    }

    @Test
    fun `enable falls back to a crypto-less prompt when cipher creation fails and still enables`() {
        val store = BiometricTokenStore(
            freshPrefs(),
            FailingEncryptOnceCryptoKeyStore(JvmCryptoKeyStore(), BiometricTokenError.KeyLocked)
        )
        val controller = FakeController()
        val manager = BiometricSessionManager(store) { controller }

        var outcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-token") { outcome = it }

        // The Keystore refused the cipher before auth: the prompt still opens,
        // without a CryptoObject, and Tier-2 is wrapped right after auth.
        assertThat(controller.launched).isTrue()
        assertThat(controller.lastCrypto).isNull()
        controller.succeed(null)

        assertThat(outcome).isEqualTo(BiometricUnlockOutcome.Enabled)
        assertThat(manager.isEnabled).isTrue()
        assertThat(manager.hasActiveSession).isTrue()
        assertThat(manager.unlockSession.decryptRefreshToken()).isEqualTo("refresh-token")
    }

    @Test
    fun `enable while tier two is gone fails without wiping the mode`() {
        val (manager, controller) = newManager()
        manager.enable("refresh-token") { }
        controller.succeed(controller.lastCrypto!!.cipher!!)
        manager.unlockSession.wipe()

        var rotateOutcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-token-2") { rotateOutcome = it }

        assertThat(rotateOutcome).isEqualTo(BiometricUnlockOutcome.Failed)
        assertThat(manager.isEnabled).isTrue()
    }

    @Test
    fun `invalidated key wipes the mode and hides the gate`() {
        val prefs = freshPrefs()
        val sharedCrypto = JvmCryptoKeyStore()

        val store1 = BiometricTokenStore(prefs, sharedCrypto)
        val controller1 = FakeController()
        val manager1 = BiometricSessionManager(store1) { controller1 }
        var enableOutcome: BiometricUnlockOutcome? = null
        manager1.enable("refresh-token") { enableOutcome = it }
        controller1.succeed(controller1.lastCrypto!!.cipher!!)
        assertThat(enableOutcome).isEqualTo(BiometricUnlockOutcome.Enabled)

        // Same blobs and same key on disk, but now the key is permanently
        // invalidated (biometrics changed on the device).
        val store2 = BiometricTokenStore(prefs, FailingOnceCryptoKeyStore(sharedCrypto))
        val controller2 = FakeController()
        val manager2 = BiometricSessionManager(store2) { controller2 }
        manager2.setSessionActive(false)

        var unlockOutcome: BiometricUnlockOutcome? = null
        manager2.requestUnlock { unlockOutcome = it }

        assertThat(unlockOutcome).isEqualTo(BiometricUnlockOutcome.KeyInvalidated)
        assertThat(manager2.isEnabled).isFalse()
        assertThat(manager2.hasActiveSession).isFalse()
        assertThat(manager2.gate.value).isEqualTo(BiometricGateState.Hidden)
    }

    @Test
    fun `disable turns the mode off, wipes tier two and hides the gate`() {
        val (manager, controller) = newManager()
        manager.enable("refresh-token") { }
        controller.succeed(controller.lastCrypto!!.cipher!!)
        manager.setSessionActive(false)
        assertThat(manager.hasActiveSession).isTrue()

        manager.disable()

        assertThat(manager.isEnabled).isFalse()
        assertThat(manager.hasActiveSession).isFalse()
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Hidden)
    }

    @Test
    fun `isEnabled and gate never resolve the prompt controller`() {
        var providerCalls = 0
        val store = BiometricTokenStore(freshPrefs(), JvmCryptoKeyStore())
        val manager = BiometricSessionManager(store) {
            providerCalls++
            throw IllegalStateException("controller provider must not run without a prompt")
        }

        manager.setSessionActive(false)
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Hidden)
        assertThat(manager.isEnabled).isFalse()
        assertThat(manager.hasActiveSession).isFalse()
        assertThat(providerCalls).isZero()
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
    }

    /** Fails the first ENCRYPT cipher creation with [error], then delegates. */
    private class FailingEncryptOnceCryptoKeyStore(
        private val delegate: CryptoKeyStore,
        private val error: BiometricTokenError,
    ) : CryptoKeyStore {
        private var shouldFail = true

        override fun hasKey(alias: String): Boolean = delegate.hasKey(alias)

        override fun createKey(alias: String) = delegate.createKey(alias)

        override fun deleteKey(alias: String) = delegate.deleteKey(alias)

        override fun createCipher(mode: Int, alias: String, iv: ByteArray?): Result<Cipher> =
            if (shouldFail && mode == Cipher.ENCRYPT_MODE) {
                shouldFail = false
                Result.failure(error)
            } else {
                delegate.createCipher(mode, alias, iv)
            }
    }

    /** Fails the first cipher creation with KeyInvalidated, then delegates. */
    private class FailingOnceCryptoKeyStore(
        private val delegate: CryptoKeyStore,
    ) : CryptoKeyStore {
        private var shouldFail = true

        override fun hasKey(alias: String): Boolean = delegate.hasKey(alias)

        override fun createKey(alias: String) = delegate.createKey(alias)

        override fun deleteKey(alias: String) = delegate.deleteKey(alias)

        override fun createCipher(mode: Int, alias: String, iv: ByteArray?): Result<Cipher> =
            if (shouldFail) {
                shouldFail = false
                Result.failure(BiometricTokenError.KeyInvalidated)
            } else {
                delegate.createCipher(mode, alias, iv)
            }
    }
}
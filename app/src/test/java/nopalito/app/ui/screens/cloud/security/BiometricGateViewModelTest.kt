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
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.viewmodel.BiometricGateUiState
import nopalito.app.ui.screens.cloud.viewmodel.BiometricGateViewModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BiometricGateViewModelTest {

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

    /** Enables biometric mode with a valid refresh token and an active Tier-2. */
    private fun enabledManager(
        controller: FakeController = FakeController(),
        crypto: CryptoKeyStore = JvmCryptoKeyStore(),
    ): Pair<BiometricSessionManager, FakeController> {
        val (manager, ctl) = newManager(crypto, controller)
        var enableOutcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-token") { enableOutcome = it }
        ctl.succeed(ctl.lastCrypto!!.cipher!!)
        assertThat(enableOutcome).isEqualTo(BiometricUnlockOutcome.Enabled)
        return manager to ctl
    }

    @Test
    fun `starts idle when the gate is required`() {
        val (manager, _) = enabledManager()
        manager.unlockSession.wipe()
        manager.setSessionActive(false)
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Required)

        val vm = BiometricGateViewModel(manager, RuntimeEnvironment.getApplication())
        assertThat(vm.uiState.value).isEqualTo(BiometricGateUiState.Idle)
    }

    @Test
    fun `successful unlock drives the ui to Unlocked`() {
        val (manager, controller) = enabledManager()
        manager.unlockSession.wipe()
        manager.setSessionActive(false)
        val vm = BiometricGateViewModel(manager, RuntimeEnvironment.getApplication())

        vm.unlock()
        assertThat(vm.uiState.value).isEqualTo(BiometricGateUiState.Prompting)

        controller.succeed(controller.lastCrypto!!.cipher!!)

        assertThat(vm.uiState.value).isEqualTo(BiometricGateUiState.Unlocked)
        assertThat(manager.hasActiveSession).isTrue()
    }

    @Test
    fun `cancelled unlock returns to Idle for a retry`() {
        val (manager, controller) = enabledManager()
        manager.unlockSession.wipe()
        manager.setSessionActive(false)
        val vm = BiometricGateViewModel(manager, RuntimeEnvironment.getApplication())

        vm.unlock()
        controller.fail(BiometricPrompt.ERROR_USER_CANCELED)

        assertThat(vm.uiState.value).isEqualTo(BiometricGateUiState.Idle)
    }

    @Test
    fun `locked out shows the localized message`() {
        val (manager, controller) = enabledManager()
        manager.unlockSession.wipe()
        manager.setSessionActive(false)
        val vm = BiometricGateViewModel(manager, RuntimeEnvironment.getApplication())

        vm.unlock()
        controller.fail(BiometricPrompt.ERROR_LOCKOUT)

        val state = vm.uiState.value
        assertThat(state).isInstanceOf(BiometricGateUiState.Message::class.java)
        assertThat((state as BiometricGateUiState.Message).text).isEqualTo(
            context.stringFor(R.string.cloud_biometric_unlock_locked_out, AppLocaleOverride.locale)
        )
    }

    @Test
    fun `unavailable unlock shows the localized message`() {
        val (manager, controller) = enabledManager()
        manager.unlockSession.wipe()
        manager.setSessionActive(false)
        val vm = BiometricGateViewModel(manager, RuntimeEnvironment.getApplication())

        vm.unlock()
        controller.fail(BiometricPrompt.ERROR_NO_BIOMETRICS)

        val state = vm.uiState.value
        assertThat(state).isInstanceOf(BiometricGateUiState.Message::class.java)
        assertThat((state as BiometricGateUiState.Message).text).isEqualTo(
            context.stringFor(R.string.cloud_biometric_unlock_unavailable, AppLocaleOverride.locale)
        )
    }

    @Test
    fun `failed unlock shows the localized message`() {
        val (manager, controller) = enabledManager()
        manager.unlockSession.wipe()
        manager.setSessionActive(false)
        val vm = BiometricGateViewModel(manager, RuntimeEnvironment.getApplication())

        vm.unlock()
        // ERROR_UNKNOWN (8) is not exposed as a constant on BiometricPrompt,
        // but maps to BiometricPromptFailure.UNKNOWN -> Failed.
        controller.fail(8)

        val state = vm.uiState.value
        assertThat(state).isInstanceOf(BiometricGateUiState.Message::class.java)
        assertThat((state as BiometricGateUiState.Message).text).isEqualTo(
            context.stringFor(R.string.cloud_biometric_unlock_failed, AppLocaleOverride.locale)
        )
    }

    @Test
    fun `invalidated key reports KeyInvalidated and wipes the mode`() {
        val prefs = freshPrefs()
        val sharedCrypto = JvmCryptoKeyStore()
        val controller1 = FakeController()
        val store1 = BiometricTokenStore(prefs, sharedCrypto)
        val manager1 = BiometricSessionManager(store1) { controller1 }
        var enableOutcome: BiometricUnlockOutcome? = null
        manager1.enable("refresh-token") { enableOutcome = it }
        controller1.succeed(controller1.lastCrypto!!.cipher!!)
        assertThat(enableOutcome).isEqualTo(BiometricUnlockOutcome.Enabled)

        val store2 = BiometricTokenStore(prefs, FailingOnceCryptoKeyStore(sharedCrypto))
        val controller2 = FakeController()
        val manager2 = BiometricSessionManager(store2) { controller2 }
        manager2.setSessionActive(false)
        val vm = BiometricGateViewModel(manager2, RuntimeEnvironment.getApplication())

        vm.unlock()

        assertThat(vm.uiState.value).isEqualTo(BiometricGateUiState.KeyInvalidated)
        assertThat(manager2.isEnabled).isFalse()
    }

    @Test
    fun `dismissMessage clears the message and allows another unlock`() {
        val (manager, controller) = enabledManager()
        manager.unlockSession.wipe()
        manager.setSessionActive(false)
        val vm = BiometricGateViewModel(manager, RuntimeEnvironment.getApplication())

        vm.unlock()
        controller.fail(BiometricPrompt.ERROR_LOCKOUT)
        assertThat(vm.uiState.value).isInstanceOf(BiometricGateUiState.Message::class.java)

        vm.dismissMessage()
        assertThat(vm.uiState.value).isEqualTo(BiometricGateUiState.Idle)

        vm.unlock()
        assertThat(vm.uiState.value).isEqualTo(BiometricGateUiState.Prompting)
    }

    @Test
    fun `unlock while the prompt is already up is ignored`() {
        val (manager, controller) = enabledManager()
        manager.unlockSession.wipe()
        manager.setSessionActive(false)
        val vm = BiometricGateViewModel(manager, RuntimeEnvironment.getApplication())

        vm.unlock()
        assertThat(vm.uiState.value).isEqualTo(BiometricGateUiState.Prompting)
        controller.launched = false

        vm.unlock()

        assertThat(controller.launched).isFalse()
    }

    @Test
    fun `unlock while mode is disabled reports the failed message`() {
        val (manager, _) = newManager()
        assertThat(manager.isEnabled).isFalse()
        val vm = BiometricGateViewModel(manager, RuntimeEnvironment.getApplication())

        vm.unlock()

        val state = vm.uiState.value
        assertThat(state).isInstanceOf(BiometricGateUiState.Message::class.java)
        assertThat((state as BiometricGateUiState.Message).text).isEqualTo(
            context.stringFor(R.string.cloud_biometric_unlock_failed, AppLocaleOverride.locale)
        )
    }

    // --- fakes (local copies; originals are private to their own test files) ---

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
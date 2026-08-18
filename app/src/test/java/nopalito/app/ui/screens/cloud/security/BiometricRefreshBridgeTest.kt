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
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BiometricRefreshBridgeTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun freshPrefs(): android.content.SharedPreferences =
        context.getSharedPreferences(
            BiometricTokenStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).apply { edit().clear().commit() }

    private fun newManager(controller: BiometricPromptController): BiometricSessionManager =
        BiometricSessionManager(BiometricTokenStore(freshPrefs(), JvmCryptoKeyStore())) { controller }

    @Test
    fun `unlock before enable returns false without launching a prompt`() = runTest {
        val controller = AutoSuccessController()
        val bridge = BiometricRefreshBridge(newManager(controller))

        assertThat(bridge.unlockSession()).isFalse()
        assertThat(controller.launched).isFalse()
    }

    @Test
    fun `enable runs exactly one prompt and persists the token`() = runTest {
        val controller = AutoSuccessController()
        val manager = newManager(controller)
        val bridge = BiometricRefreshBridge(manager)

        assertThat(bridge.enableSession("refresh-1")).isTrue()

        assertThat(controller.launched).isTrue()
        assertThat(manager.unlockSession.decryptRefreshToken()).isEqualTo("refresh-1")
    }

    @Test
    fun `unlock after a fresh process runs exactly one prompt`() = runTest {
        val controller = AutoSuccessController()
        val manager = newManager(controller)
        val bridge = BiometricRefreshBridge(manager)
        assertThat(bridge.enableSession("refresh-1")).isTrue()

        // Simulate process death / background: Tier-2 is gone from memory.
        manager.unlockSession.wipe()

        assertThat(bridge.unlockSession()).isTrue()
        assertThat(manager.unlockSession.decryptRefreshToken()).isEqualTo("refresh-1")
    }

    @Test
    fun `enable then refresh rotation keeps the prompt count at one`() = runTest {
        val controller = AutoSuccessController()
        val manager = newManager(controller)
        val bridge = BiometricRefreshBridge(manager)
        assertThat(bridge.enableSession("refresh-1")).isTrue()
        val promptsAfterEnable = controller.launchCount

        val refresher = BiometricSessionRefresher(manager.unlockSession) { _ ->
            Result.success(fakeToken("refresh-2"))
        }
        assertThat(refresher.refresh().isSuccess).isTrue()

        // Rotation ran entirely on Tier-2 in memory: no second prompt.
        assertThat(controller.launchCount).isEqualTo(promptsAfterEnable)
        assertThat(manager.unlockSession.decryptRefreshToken()).isEqualTo("refresh-2")
    }

    @Test
    fun `cancelled prompts surface as false`() = runTest {
        val controller = CancelController()
        val bridge = BiometricRefreshBridge(newManager(controller))

        assertThat(bridge.enableSession("refresh-1")).isFalse()
        assertThat(bridge.unlockSession()).isFalse()
    }

    @Test
    fun `invalidated key wipes the mode and unlock returns false`() = runTest {
        val prefs = freshPrefs()
        val sharedCrypto = JvmCryptoKeyStore()

        val store1 = BiometricTokenStore(prefs, sharedCrypto)
        val manager1 = BiometricSessionManager(store1) { AutoSuccessController() }
        val bridge1 = BiometricRefreshBridge(manager1)
        assertThat(bridge1.enableSession("refresh-1")).isTrue()

        // Same blobs on disk, but the key is now permanently invalidated.
        val manager2 = BiometricSessionManager(
            BiometricTokenStore(prefs, FailingOnceCryptoKeyStore(sharedCrypto))
        ) { AutoSuccessController() }
        val bridge2 = BiometricRefreshBridge(manager2)

        assertThat(bridge2.unlockSession()).isFalse()
        assertThat(manager2.isEnabled).isFalse()
        assertThat(manager2.hasActiveSession).isFalse()
    }

    private fun fakeToken(refresh: String) = nopalito.app.ui.screens.cloud.model.TokenData(
        accessToken = "access",
        refreshToken = refresh,
        accessTokenExpiresIn = "900",
        refreshTokenExpiresIn = "2592000",
        user = null,
    )

    // --- fakes ---

    private class AutoSuccessController : BiometricPromptController {
        var launched = false
        var launchCount = 0
        var lastCrypto: BiometricPrompt.CryptoObject? = null

        override fun authenticate(
            crypto: BiometricPrompt.CryptoObject?,
            onSuccess: (Cipher?) -> Unit,
            onError: (promptErrorCode: Int) -> Unit,
            onFailed: () -> Unit,
        ) {
            launched = true
            launchCount++
            lastCrypto = crypto
            onSuccess(crypto?.cipher)
        }
    }

    private class CancelController : BiometricPromptController {
        var launched = false

        override fun authenticate(
            crypto: BiometricPrompt.CryptoObject?,
            onSuccess: (Cipher?) -> Unit,
            onError: (promptErrorCode: Int) -> Unit,
            onFailed: () -> Unit,
        ) {
            launched = true
            onError(BiometricPrompt.ERROR_USER_CANCELED)
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
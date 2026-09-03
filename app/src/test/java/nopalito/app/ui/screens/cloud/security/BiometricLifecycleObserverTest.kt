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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BiometricLifecycleObserverTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun freshPrefs(): android.content.SharedPreferences =
        context.getSharedPreferences(
            BiometricTokenStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).apply { edit().clear().commit() }

    @Test
    fun `ON_STOP wipes tier two`() {
        val session = BiometricUnlockSession(BiometricTokenStore(freshPrefs(), JvmCryptoKeyStore()))
        session.activate(ByteArray(32) { it.toByte() })
        assertThat(session.isActive).isTrue()

        BiometricLifecycleObserver(session).onStateChanged(fakeOwner(), Lifecycle.Event.ON_STOP)

        assertThat(session.isActive).isFalse()
        assertThat(session.decryptRefreshToken()).isNull()
    }

    @Test
    fun `other lifecycle events keep tier two`() {
        val session = BiometricUnlockSession(BiometricTokenStore(freshPrefs(), JvmCryptoKeyStore()))
        session.activate(ByteArray(32) { it.toByte() })
        val observer = BiometricLifecycleObserver(session)
        val owner = fakeOwner()

        observer.onStateChanged(owner, Lifecycle.Event.ON_CREATE)
        observer.onStateChanged(owner, Lifecycle.Event.ON_START)
        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        observer.onStateChanged(owner, Lifecycle.Event.ON_PAUSE)

        assertThat(session.isActive).isTrue()
    }

    @Test
    fun `real lifecycle transition to background fires the wipe`() {
        val session = BiometricUnlockSession(BiometricTokenStore(freshPrefs(), JvmCryptoKeyStore()))
        session.activate(ByteArray(32) { it.toByte() })

        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = registry
        }
        registry = LifecycleRegistry(owner)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        registry.addObserver(BiometricLifecycleObserver(session))

        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertThat(session.isActive).isFalse()
    }

    @Test
    fun `a stop while backgrounded again keeps the session wiped`() {
        val session = BiometricUnlockSession(BiometricTokenStore(freshPrefs(), JvmCryptoKeyStore()))
        session.activate(ByteArray(32) { it.toByte() })
        val observer = BiometricLifecycleObserver(session)

        observer.onStateChanged(fakeOwner(), Lifecycle.Event.ON_STOP)
        observer.onStateChanged(fakeOwner(), Lifecycle.Event.ON_START)
        observer.onStateChanged(fakeOwner(), Lifecycle.Event.ON_STOP)

        assertThat(session.isActive).isFalse()
    }

    @Test
    fun `a full background and return cycle requires a fresh prompt`() {
        val controller = AutoSuccessController()
        val manager = BiometricSessionManager(
            BiometricTokenStore(
                freshPrefs(),
                JvmCryptoKeyStore()
            )
        ) { controller }
        var outcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-token") { outcome = it }
        assertThat(outcome).isEqualTo(BiometricUnlockOutcome.Enabled)
        manager.setSessionActive(false)
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Hidden)

        BiometricLifecycleObserver(manager.unlockSession).onStateChanged(
            fakeOwner(),
            Lifecycle.Event.ON_STOP
        )
        manager.setSessionActive(true)
        manager.setSessionActive(false)

        assertThat(manager.hasActiveSession).isFalse()
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Required)
    }

    @Test
    fun `returning after background requires a fresh prompt`() {
        val controller = AutoSuccessController()
        val manager = BiometricSessionManager(
            BiometricTokenStore(
                freshPrefs(),
                JvmCryptoKeyStore()
            )
        ) { controller }
        var outcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-token") { outcome = it }
        assertThat(outcome).isEqualTo(BiometricUnlockOutcome.Enabled)
        manager.setSessionActive(false)
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Hidden)

        BiometricLifecycleObserver(manager.unlockSession).onStateChanged(
            fakeOwner(),
            Lifecycle.Event.ON_STOP
        )
        manager.setSessionActive(false)

        assertThat(manager.hasActiveSession).isFalse()
        assertThat(manager.gate.value).isEqualTo(BiometricGateState.Required)
    }

    private fun fakeOwner(): LifecycleOwner = object : LifecycleOwner {
        override val lifecycle: Lifecycle = LifecycleRegistry(this)
    }

    private class AutoSuccessController : BiometricPromptController {
        var lastCrypto: BiometricPrompt.CryptoObject? = null

        override fun authenticate(
            crypto: BiometricPrompt.CryptoObject?,
            onSuccess: (Cipher?) -> Unit,
            onError: (promptErrorCode: Int) -> Unit,
            onFailed: () -> Unit,
        ) {
            lastCrypto = crypto
            onSuccess(crypto?.cipher)
        }
    }
}
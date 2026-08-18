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
import nopalito.app.ui.screens.cloud.data.NeedsBiometricUnlockException
import nopalito.app.ui.screens.cloud.model.TokenData
import nopalito.app.ui.screens.cloud.network.LogoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BiometricSessionRefresherTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun freshPrefs(): android.content.SharedPreferences =
        context.getSharedPreferences(
            BiometricTokenStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).apply { edit().clear().commit() }

    private fun fakeToken(refresh: String) = TokenData(
        accessToken = "access-token",
        refreshToken = refresh,
        accessTokenExpiresIn = "900",
        refreshTokenExpiresIn = "2592000",
        user = null,
    )

    private class AutoSuccessController : BiometricPromptController {
        var launchCount = 0

        override fun authenticate(
            crypto: BiometricPrompt.CryptoObject?,
            onSuccess: (Cipher?) -> Unit,
            onError: (promptErrorCode: Int) -> Unit,
            onFailed: () -> Unit,
        ) {
            launchCount++
            onSuccess(crypto?.cipher)
        }
    }

    /** Returns a manager with biometric mode enabled (one prompt already used). */
    private fun enabledManager(): Pair<BiometricSessionManager, AutoSuccessController> {
        val controller = AutoSuccessController()
        val manager = BiometricSessionManager(BiometricTokenStore(freshPrefs(), JvmCryptoKeyStore())) { controller }
        var outcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-old") { outcome = it }
        controller.launchCount = 0
        assertThat(outcome).isEqualTo(BiometricUnlockOutcome.Enabled)
        return manager to controller
    }

    @Test
    fun `refresh without an active session fails with NeedsBiometricUnlockException`() = runTest {
        val session = BiometricUnlockSession(BiometricTokenStore(freshPrefs(), JvmCryptoKeyStore()))
        var calls = 0
        val refresher = BiometricSessionRefresher(session) {
            calls++
            Result.success(fakeToken("refresh-new"))
        }

        val result = refresher.refresh()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(NeedsBiometricUnlockException::class.java)
        assertThat(calls).isZero()
    }

    @Test
    fun `successful refresh rotates the blob without a second prompt`() = runTest {
        val (manager, controller) = enabledManager()
        val refresher = BiometricSessionRefresher(manager.unlockSession) {
            Result.success(fakeToken("refresh-new"))
        }

        val result = refresher.refresh()

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.unlockSession.decryptRefreshToken()).isEqualTo("refresh-new")
        assertThat(controller.launchCount).isZero()
    }

    @Test
    fun `HTTP 401 wipes tier two and keeps the persisted blob`() = runTest {
        val (manager, _) = enabledManager()
        val refresher = BiometricSessionRefresher(manager.unlockSession) {
            Result.failure(LogoutException("refresh rejected"))
        }

        val result = refresher.refresh()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(LogoutException::class.java)
        assertThat(manager.hasActiveSession).isFalse()
        assertThat(manager.isEnabled).isTrue() // persisted mode is cleared by the caller
    }

    @Test
    fun `HTTP 500 keeps the session and the blob intact`() = runTest {
        val (manager, _) = enabledManager()
        val refresher = BiometricSessionRefresher(manager.unlockSession) {
            Result.failure(IOException("server error"))
        }

        val result = refresher.refresh()

        assertThat(result.isFailure).isTrue()
        assertThat(manager.hasActiveSession).isTrue()
        assertThat(manager.unlockSession.decryptRefreshToken()).isEqualTo("refresh-old")
    }

    @Test
    fun `failed rotation persistence continues in memory and logs sanitized`() = runTest {
        val controller = AutoSuccessController()
        val failingPrefs = FailingCommitPrefs(freshPrefs())
        val manager = BiometricSessionManager(BiometricTokenStore(failingPrefs, JvmCryptoKeyStore())) { controller }
        var outcome: BiometricUnlockOutcome? = null
        manager.enable("refresh-old") { outcome = it }
        controller.launchCount = 0
        assertThat(outcome).isEqualTo(BiometricUnlockOutcome.Enabled)

        failingPrefs.failNextCommit = true
        val refresher = BiometricSessionRefresher(manager.unlockSession) {
            Result.success(fakeToken("refresh-new"))
        }

        val result = refresher.refresh()

        assertThat(result.isSuccess).isTrue() // the HTTP exchange succeeded
        assertThat(manager.hasActiveSession).isTrue()
        assertThat(manager.unlockSession.decryptRefreshToken()).isEqualTo("refresh-old")
        val logText = ShadowLog.getLogs().joinToString { it.msg }
        assertThat(logText).doesNotContain("refresh-new")
        assertThat(logText).doesNotContain("refresh-old")
    }

    private class FailingCommitPrefs(private val delegate: android.content.SharedPreferences) :
        android.content.SharedPreferences by delegate {
        var failNextCommit = false

        override fun edit(): android.content.SharedPreferences.Editor =
            FailingEditor(delegate.edit(), this)
    }

    private class FailingEditor(
        private val delegate: android.content.SharedPreferences.Editor,
        private val prefs: FailingCommitPrefs,
    ) : android.content.SharedPreferences.Editor by delegate {
        override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
            delegate.putString(key, value)
            return this
        }

        override fun commit(): Boolean {
            if (prefs.failNextCommit) {
                prefs.failNextCommit = false
                return false
            }
            return delegate.commit()
        }
    }
}
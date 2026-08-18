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
import android.content.SharedPreferences
import android.util.Base64
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BiometricUnlockSessionTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun freshPrefs(): SharedPreferences =
        context.getSharedPreferences(
            BiometricTokenStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).apply { edit().clear().commit() }

    private fun newSession(prefs: SharedPreferences = freshPrefs()): BiometricUnlockSession =
        BiometricUnlockSession(BiometricTokenStore(prefs, JvmCryptoKeyStore()))

    private fun tier2(): ByteArray = ByteArray(32) { (it + 7).toByte() }

    @Test
    fun `decrypt requires an active session`() {
        val session = newSession()

        assertThat(session.isActive).isFalse()
        assertThat(session.decryptRefreshToken()).isNull()
        assertThat(session.rotateRefreshToken("refresh-1")).isFalse()
    }

    @Test
    fun `rotate then decrypt round trips through the persisted blob`() {
        val session = newSession()
        session.activate(tier2())

        assertThat(session.rotateRefreshToken("refresh-1")).isTrue()
        assertThat(session.decryptRefreshToken()).isEqualTo("refresh-1")

        assertThat(session.rotateRefreshToken("refresh-2")).isTrue()
        assertThat(session.decryptRefreshToken()).isEqualTo("refresh-2")
    }

    @Test
    fun `wipe zeroizes tier two and forces a fresh unlock`() {
        val session = newSession()
        session.activate(tier2())
        assertThat(session.rotateRefreshToken("refresh-1")).isTrue()

        session.wipe()

        assertThat(session.isActive).isFalse()
        assertThat(session.decryptRefreshToken()).isNull()
        assertThat(session.rotateRefreshToken("refresh-2")).isFalse()
    }

    @Test
    fun `a fresh session over the same store requires a new prompt`() {
        val prefs = freshPrefs()
        val first = newSession(prefs)
        first.activate(tier2())
        assertThat(first.rotateRefreshToken("refresh-1")).isTrue()

        // Process death: a brand-new session has no Tier-2 in memory.
        val restarted = newSession(prefs)

        assertThat(restarted.isActive).isFalse()
        assertThat(restarted.decryptRefreshToken()).isNull()
    }

    @Test
    fun `corrupted blob decrypts to null without wiping the session`() {
        val prefs = freshPrefs()
        val session = newSession(prefs)
        session.activate(tier2())
        assertThat(session.rotateRefreshToken("refresh-1")).isTrue()

        prefs.edit()
            .putString(
                BiometricTokenStore.REFRESH_CT_KEY,
                Base64.encodeToString("garbage".toByteArray(), Base64.NO_WRAP)
            )
            .commit()

        assertThat(session.decryptRefreshToken()).isNull()
        assertThat(session.isActive).isTrue()

        assertThat(session.rotateRefreshToken("refresh-2")).isTrue()
        assertThat(session.decryptRefreshToken()).isEqualTo("refresh-2")
    }

    @Test
    fun `failed commit keeps the previous blob and logs sanitized`() {
        val prefs = FailingCommitPrefs(freshPrefs())
        val session = newSession(prefs)
        session.activate(tier2())
        assertThat(session.rotateRefreshToken("refresh-1")).isTrue()

        prefs.failNextCommit = true
        assertThat(session.rotateRefreshToken("refresh-2")).isFalse()

        assertThat(session.decryptRefreshToken()).isEqualTo("refresh-1")
        val logText = ShadowLog.getLogs().joinToString { it.msg }
        assertThat(logText).doesNotContain("refresh-2")
    }

    @Test
    fun `read-back mismatch rolls back to the previous blob`() {
        val prefs = CorruptingPrefs(freshPrefs())
        val session = newSession(prefs)
        session.activate(tier2())
        assertThat(session.rotateRefreshToken("refresh-1")).isTrue()

        prefs.corruptNextRefreshWrite = true
        assertThat(session.rotateRefreshToken("refresh-2")).isFalse()

        assertThat(session.decryptRefreshToken()).isEqualTo("refresh-1")
        val logText = ShadowLog.getLogs().joinToString { it.msg }
        assertThat(logText).doesNotContain("refresh-2")
    }

    // --- fakes ---

    private class FailingCommitPrefs(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var failNextCommit = false

        override fun edit(): SharedPreferences.Editor =
            FailingEditor(delegate.edit(), this)
    }

    private class FailingEditor(
        private val delegate: SharedPreferences.Editor,
        private val prefs: FailingCommitPrefs,
    ) : SharedPreferences.Editor by delegate {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
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

    /** Persists garbage instead of the refresh ciphertext on the next write. */
    private class CorruptingPrefs(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var corruptNextRefreshWrite = false

        override fun edit(): SharedPreferences.Editor =
            CorruptingEditor(delegate.edit(), this)
    }

    private class CorruptingEditor(
        private val delegate: SharedPreferences.Editor,
        private val prefs: CorruptingPrefs,
    ) : SharedPreferences.Editor by delegate {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (prefs.corruptNextRefreshWrite && key == BiometricTokenStore.REFRESH_CT_KEY) {
                prefs.corruptNextRefreshWrite = false
                delegate.putString(key, Base64.encodeToString("garbage".toByteArray(), Base64.NO_WRAP))
            } else {
                delegate.putString(key, value)
            }
            return this
        }
    }
}
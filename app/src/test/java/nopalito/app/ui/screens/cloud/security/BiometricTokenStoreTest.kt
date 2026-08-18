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
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Robolectric ships no `AndroidKeyStore` provider, so these tests drive the
 * store through [JvmCryptoKeyStore]: a stand-in that mirrors the Keystore
 * semantics (key lifecycle, cipher release) using real AES-256-GCM on the JVM.
 * The AndroidKeyStore-only wrapper ([AndroidCryptoKeyStore]) is intentionally
 * kept too thin to need coverage; its authenticator matrix is asserted at the
 * [BiometricCapability] layer instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BiometricTokenStoreTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun newStore(): BiometricTokenStore {
        val prefs = context.getSharedPreferences(
            BiometricTokenStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        prefs.edit().clear().commit()
        return BiometricTokenStore(prefs, JvmCryptoKeyStore())
    }

    @Test
    fun `store starts disabled with no key and no blob`() {
        val store = newStore()

        assertThat(store.isEnabled).isFalse()
        assertThat(store.hasKey()).isFalse()
        assertThat(store.hasBlob()).isFalse()
    }

    @Test
    fun `encrypt cipher requires the key to exist first`() {
        val store = newStore()

        val result = store.createEncryptCipher()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isEqualTo(BiometricTokenError.NotEnabled)
    }

    @Test
    fun `creating the key alone does not enable the store`() {
        val store = newStore()
        store.createKeyIfNeeded()

        assertThat(store.hasKey()).isTrue()
        assertThat(store.isEnabled).isFalse()
        assertThat(store.createEncryptCipher().isSuccess).isTrue()
    }

    @Test
    fun `decrypt cipher without a stored blob reports not enabled`() {
        val store = newStore()
        store.createKeyIfNeeded()

        val result = store.createDecryptCipher()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isEqualTo(BiometricTokenError.NotEnabled)
    }

    @Test
    fun `full encrypt - persist - decrypt roundtrip restores tier two`() {
        val store = newStore()
        store.createKeyIfNeeded()
        val tier2 = ByteArray(32) { it.toByte() }

        val encryptCipher = store.createEncryptCipher().getOrThrow()
        val ciphertext = encryptCipher.doFinal(tier2)
        store.saveKeyBlob(encryptCipher, ciphertext)

        assertThat(store.isEnabled).isFalse() // refresh blob still missing
        assertThat(store.hasKeyBlob()).isTrue()

        val decryptCipher = store.createDecryptCipher().getOrThrow()
        val restored = decryptCipher.doFinal(store.loadKeyCiphertext()!!)

        assertThat(restored).isEqualTo(tier2)
    }

    @Test
    fun `refresh blob roundtrip with explicit iv`() {
        val store = newStore()
        val iv = ByteArray(12) { it.toByte() }
        val ct = "encrypted-refresh".toByteArray()

        assertThat(store.saveRefreshBlob(iv, ct)).isTrue()

        assertThat(store.hasRefreshBlob()).isTrue()
        assertThat(store.loadRefreshIv()).isEqualTo(iv)
        assertThat(store.loadRefreshCiphertext()).isEqualTo(ct)
    }

    @Test
    fun `store is enabled only when key and both blobs exist`() {
        val store = newStore()
        store.createKeyIfNeeded()
        val encryptCipher = store.createEncryptCipher().getOrThrow()
        store.saveKeyBlob(encryptCipher, encryptCipher.doFinal(ByteArray(32) { it.toByte() }))
        assertThat(store.isEnabled).isFalse()

        store.saveRefreshBlob(ByteArray(12), "x".toByteArray())

        assertThat(store.isEnabled).isTrue()
        assertThat(store.hasBlob()).isTrue()
    }

    @Test
    fun `repeated key creation is idempotent and preserves the blobs`() {
        val store = newStore()
        store.createKeyIfNeeded()
        val encryptCipher = store.createEncryptCipher().getOrThrow()
        store.saveKeyBlob(encryptCipher, encryptCipher.doFinal("still-here".toByteArray()))
        store.saveRefreshBlob(ByteArray(12), "refresh".toByteArray())

        store.createKeyIfNeeded()
        store.createKeyIfNeeded()

        assertThat(store.isEnabled).isTrue()
        val decryptCipher = store.createDecryptCipher().getOrThrow()
        val restored = decryptCipher.doFinal(store.loadKeyCiphertext()!!)
        assertThat(String(restored)).isEqualTo("still-here")
        assertThat(store.hasRefreshBlob()).isTrue()
    }

    @Test
    fun `deleting the key disables the store`() {
        val store = newStore()
        store.createKeyIfNeeded()
        val encryptCipher = store.createEncryptCipher().getOrThrow()
        store.saveKeyBlob(encryptCipher, encryptCipher.doFinal("x".toByteArray()))
        store.saveRefreshBlob(ByteArray(12), "x".toByteArray())
        assertThat(store.isEnabled).isTrue()

        store.deleteKey()

        assertThat(store.hasKey()).isFalse()
        assertThat(store.isEnabled).isFalse()
        assertThat(store.createEncryptCipher().exceptionOrNull())
            .isEqualTo(BiometricTokenError.NotEnabled)
    }

    @Test
    fun `clearing the blobs disables the store`() {
        val store = newStore()
        store.createKeyIfNeeded()
        val encryptCipher = store.createEncryptCipher().getOrThrow()
        store.saveKeyBlob(encryptCipher, encryptCipher.doFinal("x".toByteArray()))
        store.saveRefreshBlob(ByteArray(12), "x".toByteArray())
        assertThat(store.isEnabled).isTrue()

        store.clearBlob()

        assertThat(store.hasBlob()).isFalse()
        assertThat(store.isEnabled).isFalse()
        assertThat(store.createDecryptCipher().exceptionOrNull())
            .isEqualTo(BiometricTokenError.NotEnabled)
    }

    @Test
    fun `permanently invalidated key maps to KeyInvalidated`() {
        assertThat(mapCipherInitFailure(KeyPermanentlyInvalidatedException()))
            .isEqualTo(BiometricTokenError.KeyInvalidated)
    }

    @Test
    fun `unreleased key maps to KeyLocked`() {
        assertThat(mapCipherInitFailure(UserNotAuthenticatedException()))
            .isEqualTo(BiometricTokenError.KeyLocked)
    }

    @Test
    fun `unknown keystore failure maps to StorageUnavailable`() {
        assertThat(mapCipherInitFailure(IOException("boom")))
            .isEqualTo(BiometricTokenError.StorageUnavailable)
    }

    @Test
    fun `keystore without the required cipher maps to NotAvailable`() {
        assertThat(mapCipherInitFailure(java.security.NoSuchAlgorithmException("Provider AndroidKeyStore does not provide AES/GCM/NoPadding")))
            .isEqualTo(BiometricTokenError.NotAvailable)
    }
}

/**
 * In-memory stand-in for the Android Keystore used only by JVM tests. Mirrors
 * the exact contract the store relies on: key lifecycle plus a real
 * AES-256-GCM [Cipher] bound to the stored key.
 */
internal class JvmCryptoKeyStore : CryptoKeyStore {

    private val keys = mutableMapOf<String, SecretKey>()

    override fun hasKey(alias: String): Boolean = keys.containsKey(alias)

    override fun createKey(alias: String) {
        if (hasKey(alias)) return
        keys[alias] = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    override fun deleteKey(alias: String) {
        keys.remove(alias)
    }

    override fun createCipher(mode: Int, alias: String, iv: ByteArray?): Result<Cipher> {
        val key = keys[alias] ?: return Result.failure(BiometricTokenError.NotEnabled)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            if (iv != null) {
                cipher.init(mode, key, GCMParameterSpec(128, iv))
            } else {
                cipher.init(mode, key)
            }
            Result.success(cipher)
        } catch (e: Throwable) {
            Result.failure(mapCipherInitFailure(e))
        }
    }
}
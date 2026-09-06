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

import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "BiometricUnlockSession"

/**
 * The ONLY owner of Tier-2: a random 256-bit AES key that exists solely in
 * memory while the cloud session is unlocked.
 *
 * Memory contract:
 * - Lifetime: from [activate] (right after the OS prompt unwraps Tier-2, or
 *   after [enable]) until [wipe] — logout, disable, account change, key
 *   invalidation, `ON_STOP` (app backgrounded) or process death.
 * - Release: [wipe] zeroizes the byte array and drops the reference; the JVM
 *   garbage collector then reclaims it. Nothing else in the process holds it.
 * - The decrypted refresh token exists only in the [decryptRefreshToken] call
 *   frame and in whoever consumes it for the refresh call — never in a field,
 *   a flow, a ViewModel, Compose state, Bundle, SavedState or a log.
 *
 * All Tier-2 crypto is plain AES-256-GCM on the JVM/device runtime — never
 * the Android Keystore (that is Tier-1, held by [BiometricTokenStore]).
 */
class BiometricUnlockSession(
    private val store: BiometricTokenStore,
) {

    @Volatile
    private var tier2: ByteArray? = null

    val isActive: Boolean
        get() = tier2 != null

    /** Replaces any previous Tier-2 (zeroized first) with the fresh one. */
    fun activate(tier2Bytes: ByteArray) {
        wipe()
        tier2 = tier2Bytes
    }

    /** Zeroizes Tier-2 and drops it. Safe to call any number of times. */
    fun wipe() {
        tier2?.fill(0)
        tier2 = null
    }

    /**
     * Decrypts the persisted refresh token with Tier-2 (no prompt). Returns
     * `null` when the session is inactive or the blob is unreadable.
     */
    fun decryptRefreshToken(): String? {
        val t2 = tier2 ?: return null
        val iv = store.loadRefreshIv() ?: return null
        val ct = store.loadRefreshCiphertext() ?: return null
        val plain = runCatching { aesGcm(t2, iv, ct, Cipher.DECRYPT_MODE) }.getOrNull()
            ?: return null
        return plain.toString(Charsets.UTF_8)
    }

    /**
     * Atomically re-encrypts the refresh token with Tier-2 (no prompt):
     * encrypt with a fresh IV, commit the new blob in one transaction, verify
     * the blob reads back as [newRefresh], and only on failure restore the
     * previous blob. A failed commit or a failed read-back keeps the old
     * refresh valid and returns `false`.
     */
    fun rotateRefreshToken(newRefresh: String): Boolean {
        val t2 = tier2 ?: return false
        val prevIv = store.loadRefreshIv()
        val prevCt = store.loadRefreshCiphertext()
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val ct = runCatching {
            aesGcm(
                t2,
                iv,
                newRefresh.toByteArray(),
                Cipher.ENCRYPT_MODE
            )
        }.getOrNull()
            ?: return false
        if (!store.saveRefreshBlob(iv, ct)) {
            Log.w(TAG, "rotateRefreshToken: atomic save failed; previous blob kept")
            return false
        }
        if (decryptRefreshToken() != newRefresh) {
            Log.w(TAG, "rotateRefreshToken: read-back verification failed; restoring previous blob")
            if (prevIv != null && prevCt != null) {
                store.saveRefreshBlob(prevIv, prevCt)
            }
            return false
        }
        return true
    }

    private fun aesGcm(tier2: ByteArray, iv: ByteArray, input: ByteArray, mode: Int): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            mode,
            SecretKeySpec(tier2, KEY_ALGORITHM),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        return cipher.doFinal(input)
    }

    private companion object {
        const val KEY_ALGORITHM = "AES"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val IV_SIZE = 12
    }
}
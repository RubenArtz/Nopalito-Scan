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

// EncryptedSharedPreferences/MasterKey are deprecated by Google (androidx
// security-crypto 1.1.0) but still work. There is no 1:1 migration path to
// the recommended replacement (Tink/Keystore): the storage format differs, so
// migrating would orphan every device id and cloud token already written with
// this scheme and require on-device testing. Deliberately kept.
@file:Suppress("DEPRECATION")

package nopalito.app.push

import android.content.Context
import androidx.core.content.edit
import nopalito.app.platform.SecurePrefs
import java.util.UUID

/**
 * Stable per-install device identifier sent to the backend with the FCM token.
 *
 * A UUID is generated once and persisted in EncryptedSharedPreferences (the
 * same storage used for the cloud auth tokens), so it survives app restarts,
 * is unique per app install, and carries no personal data (unlike the raw
 * ANDROID_ID). It is NOT a credential: it only distinguishes devices of the
 * same user so tokens can be updated/revoked individually.
 *
 * Creation goes through [SecurePrefs] so a corrupted Keystore-wrapped keyset
 * resets the store (a new id is minted) instead of crashing FCM registration.
 */
object DeviceIdentity {
    private const val PREFS_NAME = "push_device_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    @Volatile
    private var cached: String? = null

    fun getOrCreate(context: Context): String {
        cached?.let { return it }

        val prefs = SecurePrefs.create(context, PREFS_NAME)

        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) {
            cached = existing
            return existing
        }

        val created = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_DEVICE_ID, created) }
        cached = created
        return created
    }
}
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
// security-crypto 1.1.0) but still work; see DeviceIdentity.kt for why they
// are deliberately kept.
@file:Suppress("DEPRECATION")

package nopalito.app.platform

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

/**
 * Creates the app's EncryptedSharedPreferences stores, healing the known
 * fatal corruption modes instead of crash-looping at startup:
 *
 * 1. The stored Tink keyset no longer decrypts with this device's Android
 *    Keystore master key (`AEADBadTagException` / `KeyStoreException`).
 *    Typical triggers: a cloud/device backup restored onto another device,
 *    or an OEM Keystore reset. The wrapped keyset is unrecoverable BY
 *    DESIGN, so the store file is deleted and recreated empty — the user
 *    signs into Cloud again and a new push device id is minted.
 * 2. The master key alias itself is unusable (e.g. invalidated). As a last
 *    resort the alias is deleted from AndroidKeyStore so the next attempt
 *    generates a fresh one. Only reached when the plain reset did not help,
 *    i.e. when every store sharing this master key is unreadable anyway.
 *
 * Every EncryptedSharedPreferences user in this app must go through here:
 * these stores are opened from Application.onCreate paths, so a raw
 * create() call turns any of the states above into a startup crash-loop.
 */
object SecurePrefs {
    private const val TAG = "SecurePrefs"

    fun create(context: Context, fileName: String): SharedPreferences {
        val appContext = try {
            context.applicationContext
        } catch (_: Exception) {
            context
        }
        // Robolectric/unit test fallback: EncryptedSharedPreferences requires AndroidKeyStore/AES256_GCM
        // which is unavailable in JVM tests. Use plain prefs for tests.
        if (isTestEnvironment()) {
            return appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        }
        try {
            return createOnce(appContext, fileName)
        } catch (first: Exception) {
            Log.e(
                TAG,
                "$fileName unreadable (${first.javaClass.simpleName}); resetting encrypted store",
                first
            )
        }
        // Attempt 2: drop the corrupted keyset + data.
        wipeStore(appContext, fileName)
        try {
            return createOnce(appContext, fileName)
        } catch (second: Exception) {
            Log.e(TAG, "$fileName still unreadable after reset; resetting master key", second)
        }
        // Attempt 3 (last resort): invalidate the shared master key alias.
        deleteMasterKey()
        wipeStore(appContext, fileName)
        return try {
            createOnce(appContext, fileName)
        } catch (e: Exception) {
            Log.w(TAG, "$fileName falling back to plain prefs due to ${e.javaClass.simpleName}")
            appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        }
    }

    private fun isTestEnvironment(): Boolean {
        return try {
            // Robolectric or pure JVM unit test: no AndroidKeyStore
            Class.forName("org.robolectric.RobolectricTestRunner") != null
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    private fun createOnce(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Deletes [fileName]'s XML, clearing the framework cache first. */
    private fun wipeStore(context: Context, fileName: String) {
        try {
            // Clear through SharedPreferences first so its in-memory cache
            // cannot resurrect the deleted file on the next write.
            context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
                .edit(commit = true) {
                    clear()
                }
            File(context.applicationInfo.dataDir, "shared_prefs/$fileName.xml").delete()
        } catch (e: Exception) {
            Log.w(TAG, "wipeStore($fileName): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun deleteMasterKey() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                Log.w(TAG, "Deleted unusable AndroidKeyStore master key alias")
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteMasterKey: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
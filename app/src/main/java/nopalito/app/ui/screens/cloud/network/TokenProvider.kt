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
// migrating would orphan every persisted cloud token and require on-device
// testing. Deliberately kept.
@file:Suppress("DEPRECATION")

package nopalito.app.ui.screens.cloud.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import nopalito.app.ui.screens.cloud.model.UserData

private const val TAG = "TokenProvider"

/**
 * TokenProvider manages secure storage of cloud auth tokens using EncryptedSharedPreferences.
 * Tokens are encrypted at rest using AndroidX Security Crypto with AES256-GCM.
 */
class TokenProvider(
    context: Context,
    private val biometricMode: () -> Boolean = { false },
) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val onLogoutCallbacks = mutableListOf<() -> Unit>()
    private val onLoginCallbacks = mutableListOf<() -> Unit>()
    private val onBeforeTokensClearedCallbacks = mutableListOf<() -> Unit>()

    /** Register a callback invoked when tokens are cleared. */
    fun onLogout(callback: () -> Unit) {
        onLogoutCallbacks.add(callback)
    }

    /**
     * Register a callback invoked after a real sign-in (a session established
     * with a user payload). Token refresh (which also calls [saveTokens] with
     * `user = null`) does NOT trigger it, so e.g. FCM re-registration only runs
     * on actual login, not on every 401→refresh cycle.
     */
    fun onLogin(callback: () -> Unit) {
        onLoginCallbacks.add(callback)
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    fun saveTokens(
        accessToken: String,
        refreshToken: String,
        user: UserData? = null
    ) {
        Log.i(TAG, "saveTokens: access and refresh tokens stored")
        saveAccessToken(accessToken)
        saveRefreshToken(refreshToken)
        if (user != null) {
            saveUser(user)
        }
        // A real sign-in carries the user payload; fire the login callbacks
        // (e.g. register the FCM device). Token refresh passes user = null.
        if (user != null) {
            onLoginCallbacks.forEach { it() }
        }
    }

    fun saveAccessToken(accessToken: String) {
        prefs.edit { putString(KEY_ACCESS_TOKEN, accessToken) }
    }

    fun saveRefreshToken(refreshToken: String) {
        prefs.edit { putString(KEY_REFRESH_TOKEN, refreshToken) }
    }

    fun removeRefreshToken() {
        prefs.edit { remove(KEY_REFRESH_TOKEN) }
    }

    fun saveUser(user: UserData) {
        prefs.edit {
            putString(KEY_USER_ID, user.id)
                .putString(KEY_USER_EMAIL, user.email)
        }
    }

    fun clearTokens() {
        Log.w(TAG, "clearTokens() — clearing ALL tokens, callbacks=${onLogoutCallbacks.size}")
        // Let hooks that still need a valid session run first (e.g. revoking
        // the push device with the still-valid Bearer token).
        onBeforeTokensClearedCallbacks.forEach { it() }
        prefs.edit {
            clear()
        }
        // Notify all registered callbacks that the session is gone
        onLogoutCallbacks.forEach { it() }
    }

    fun hasSession(): Boolean {
        // In biometric mode the refresh token lives ONLY in the auth-bound
        // biometric blob, so "has session" = mode on + identity present.
        val result = getRefreshToken() != null || (biometricMode() && getUserEmail() != null)
        Log.d(TAG, "hasSession() → $result  (biometricMode=${biometricMode()})")
        return result
    }

    companion object {
        private const val PREFS_NAME = "cloud_secure_prefs"
        private const val KEY_ACCESS_TOKEN = "cloud_access_token"
        private const val KEY_REFRESH_TOKEN = "cloud_refresh_token"
        private const val KEY_USER_ID = "cloud_user_id"
        private const val KEY_USER_EMAIL = "cloud_user_email"
    }
}

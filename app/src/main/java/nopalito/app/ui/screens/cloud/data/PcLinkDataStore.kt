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

package nopalito.app.ui.screens.cloud.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nopalito.app.ui.screens.cloud.model.PcLinkSyncStatus

/** One DataStore file for the PC-link pairing state. */
private val Context.pcLinkDataStore by preferencesDataStore(name = "pc_link")

private const val TAG = "PcLinkDataStore"

/** In-memory snapshot of the persisted pairing state. */
data class PcLinkPersistedState(
    val linkSessionId: String? = null,
    val webDeviceId: String? = null,
    val lastUploadAt: Long? = null,
    val lastSyncStatus: PcLinkSyncStatus = PcLinkSyncStatus.IDLE
)

/**
 * Persists the Cloud Link pairing (Receive scans on your PC).
 *
 * Only opaque identifiers live here — never the PIN, the QR payload or any
 * token. The FCM service clears the pairing on `pc_unlinked`; ViewModels
 * observe [state] for the header badge (gray/green/amber).
 */
class PcLinkDataStore private constructor(private val context: Context) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(PcLinkPersistedState())
    val state: StateFlow<PcLinkPersistedState> = _state.asStateFlow()

    /** True when a pairing was stored (badge shows green until proven stale). */
    val isLinked: Flow<Boolean> = context.pcLinkDataStore.data
        .map { prefs -> prefs[KEY_LINK_SESSION_ID] != null }

    suspend fun reload(): PcLinkPersistedState = mutex.withLock {
        readFromDisk().also { _state.value = it }
    }

    suspend fun saveLinkSession(sessionId: String, webDeviceId: String) {
        mutex.withLock {
            context.pcLinkDataStore.edit { prefs ->
                prefs[KEY_LINK_SESSION_ID] = sessionId
                prefs[KEY_WEB_DEVICE_ID] = webDeviceId
            }
            _state.value = readFromDisk()
        }
        Log.i(TAG, "link session saved")
    }

    suspend fun getLinkSession(): Pair<String, String>? {
        val current = ensureLoaded()
        val id = current.linkSessionId ?: return null
        val device = current.webDeviceId ?: return null
        return id to device
    }

    suspend fun clearLinkSession() {
        mutex.withLock {
            context.pcLinkDataStore.edit { prefs ->
                prefs.remove(KEY_LINK_SESSION_ID)
                prefs.remove(KEY_WEB_DEVICE_ID)
            }
            _state.value = readFromDisk()
        }
        Log.i(TAG, "link session cleared")
    }

    suspend fun recordUpload(status: PcLinkSyncStatus, atMs: Long = System.currentTimeMillis()) {
        mutex.withLock {
            context.pcLinkDataStore.edit { prefs ->
                prefs[KEY_LAST_UPLOAD_AT] = atMs
                prefs[KEY_LAST_SYNC_STATUS] = status.name
            }
            _state.value = readFromDisk()
        }
    }

    private suspend fun ensureLoaded(): PcLinkPersistedState {
        val current = _state.value
        if (current.linkSessionId != null || current.webDeviceId != null ||
            current.lastUploadAt != null
        ) {
            return current
        }
        return readFromDisk().also { _state.value = it }
    }

    private suspend fun readFromDisk(): PcLinkPersistedState {
        return try {
            val prefs = context.pcLinkDataStore.data.first()
            PcLinkPersistedState(
                linkSessionId = prefs[KEY_LINK_SESSION_ID],
                webDeviceId = prefs[KEY_WEB_DEVICE_ID],
                lastUploadAt = prefs[KEY_LAST_UPLOAD_AT],
                lastSyncStatus = prefs[KEY_LAST_SYNC_STATUS]
                    ?.let { runCatching { PcLinkSyncStatus.valueOf(it) }.getOrNull() }
                    ?: PcLinkSyncStatus.IDLE
            )
        } catch (_: Exception) {
            PcLinkPersistedState()
        }
    }

    companion object {
        private val KEY_LINK_SESSION_ID = stringPreferencesKey("link_session_id")
        private val KEY_WEB_DEVICE_ID = stringPreferencesKey("web_device_id")
        private val KEY_LAST_UPLOAD_AT = longPreferencesKey("last_upload_at")
        private val KEY_LAST_SYNC_STATUS = stringPreferencesKey("last_sync_status")

        @Volatile
        private var instance: PcLinkDataStore? = null

        fun getInstance(context: Context): PcLinkDataStore {
            return instance ?: synchronized(this) {
                instance ?: PcLinkDataStore(context.applicationContext).also { instance = it }
            }
        }
    }
}

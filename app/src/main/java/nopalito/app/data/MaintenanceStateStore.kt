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

package nopalito.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nopalito.app.ui.screens.cloud.model.MaintenanceStatus

/** One DataStore file for the persisted maintenance snapshot. */
private val Context.maintenanceDataStore by preferencesDataStore(name = "maintenance_state")

/**
 * Persisted, process-wide store of [LocalMaintenanceState] (Phase 2).
 *
 * The FCM service writes pushes here ([updateFromFcm], version-guarded) and
 * ViewModels read/reconcile through it ([reload], [updateFromHttp]). State
 * survives process death and app restarts; a corrupted file degrades to
 * EMPTY and the next bootstrap fetch rebuilds it.
 *
 * Logs carry metadata only (event/version/source/flags), never message text.
 */
class MaintenanceStateStore private constructor(private val context: Context) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(LocalMaintenanceState.EMPTY)

    /** In-memory mirror of the persisted state (hot after [reload]). */
    val state: StateFlow<LocalMaintenanceState> = _state.asStateFlow()

    /**
     * Re-reads the persisted snapshot from disk into memory. Called once per
     * consumer start (VM init); subsequent reads use the in-memory mirror.
     */
    suspend fun reload(): LocalMaintenanceState = mutex.withLock {
        readFromDisk().also { _state.value = it }
    }

    /**
     * Applies one FCM `type: maintenance` data payload. Returns true when it
     * was applied (newer version); false when discarded (malformed, old or
     * duplicate version). Emits nothing here: callers own event publishing.
     */
    suspend fun updateFromFcm(data: Map<String, String>): Boolean = mutex.withLock {
        val current = ensureLoaded()
        val payload = MaintenanceStateLogic.parseFcmPayload(data)
        if (payload == null) {
            Log.w(TAG, "fcm payload malformed; ignored")
            return@withLock false
        }
        // Phase 3 correlation: ties this push to the operator action that
        // caused it (panel -> backend -> FCM -> here). Metadata only.
        val correlationId = data["correlation_id"]?.trim()?.takeIf { it.isNotEmpty() }
        val next = MaintenanceStateLogic.applyFcm(current, payload, System.currentTimeMillis())
        if (next == null) {
            Log.i(
                TAG,
                "skip fcm: old/duplicate version ${payload.version} <= local ${current.version} " +
                        "(event=${payload.event}, corr=$correlationId)"
            )
            return@withLock false
        }
        persistLocked(next)
        Log.i(
            TAG,
            "applied fcm: event=${payload.event} v=${payload.version} " +
                    "active=${next.maintenanceActive} scheduled=${next.maintenanceScheduled} " +
                    "source=fcm corr=$correlationId"
        )
        true
    }

    /**
     * Applies an HTTP reconciliation snapshot (authoritative). Pass the
     * timestamp captured BEFORE the request so an FCM push that lands while
     * the request is in flight wins over this older snapshot. [correlationId]
     * is the X-Correlation-ID echoed by the server, when it sent one back.
     */
    suspend fun updateFromHttp(
        status: MaintenanceStatus?,
        requestStartedAtMs: Long,
        source: String = LocalMaintenanceState.SOURCE_HTTP_RECONCILE,
        correlationId: String? = null,
    ): LocalMaintenanceState = mutex.withLock {
        val startedAtNs = System.nanoTime()
        val current = ensureLoaded()
        val next = MaintenanceStateLogic.applyHttp(
            current, status, requestStartedAtMs, System.currentTimeMillis(), source
        )
        val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000
        if (next == null) {
            Log.i(TAG, "skip http: superseded by newer update mid-flight")
            return@withLock current
        }
        persistLocked(next)
        Log.i(
            TAG,
            "applied http: source=$source v=${next.version} " +
                    "active=${next.maintenanceActive} scheduled=${next.maintenanceScheduled} " +
                    "elapsed_ms=$elapsedMs corr=${correlationId ?: "none"}"
        )
        next
    }

    /** Wipes the snapshot (debug/tooling only). Next consumer bootstraps. */
    suspend fun clear() = mutex.withLock {
        context.maintenanceDataStore.edit { it.clear() }
        _state.value = LocalMaintenanceState.EMPTY
        Log.i(TAG, "cleared")
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    private var loaded = false

    /** Must run under [mutex]. */
    private suspend fun ensureLoaded(): LocalMaintenanceState {
        if (loaded) return _state.value
        return readFromDisk().also {
            _state.value = it
            loaded = true
        }
    }

    /** Must run under [mutex]. Corrupt/unreadable files degrade to EMPTY. */
    private suspend fun readFromDisk(): LocalMaintenanceState {
        return try {
            val prefs = context.maintenanceDataStore.data.first()
            decode(prefs)
        } catch (e: Exception) {
            Log.w(
                TAG,
                "maintenance datastore unreadable; starting empty (${e.javaClass.simpleName})"
            )
            LocalMaintenanceState.EMPTY
        }
    }

    /** Must run under [mutex]. */
    private suspend fun persistLocked(next: LocalMaintenanceState) {
        context.maintenanceDataStore.edit { prefs ->
            prefs[KEY_ACTIVE] = next.maintenanceActive
            prefs[KEY_SCHEDULED] = next.maintenanceScheduled
            next.maintenanceId?.let { prefs[KEY_ID] = it } ?: prefs.remove(KEY_ID)
            prefs[KEY_VERSION] = next.version
            next.updatedAt?.let { prefs[KEY_UPDATED_AT] = it } ?: prefs.remove(KEY_UPDATED_AT)
            next.title?.let { prefs[KEY_TITLE] = it } ?: prefs.remove(KEY_TITLE)
            next.message?.let { prefs[KEY_MESSAGE] = it } ?: prefs.remove(KEY_MESSAGE)
            next.reason?.let { prefs[KEY_REASON] = it } ?: prefs.remove(KEY_REASON)
            next.type?.let { prefs[KEY_TYPE] = it } ?: prefs.remove(KEY_TYPE)
            next.code?.let { prefs[KEY_CODE] = it } ?: prefs.remove(KEY_CODE)
            next.titleKey?.let { prefs[KEY_TITLE_KEY] = it } ?: prefs.remove(KEY_TITLE_KEY)
            next.messageKey?.let { prefs[KEY_MESSAGE_KEY] = it } ?: prefs.remove(KEY_MESSAGE_KEY)
            next.reasonKey?.let { prefs[KEY_REASON_KEY] = it } ?: prefs.remove(KEY_REASON_KEY)
            next.startsAt?.let { prefs[KEY_STARTS_AT] = it } ?: prefs.remove(KEY_STARTS_AT)
            next.endsAt?.let { prefs[KEY_ENDS_AT] = it } ?: prefs.remove(KEY_ENDS_AT)
            next.timezone?.let { prefs[KEY_TIMEZONE] = it } ?: prefs.remove(KEY_TIMEZONE)
            prefs[KEY_RETRY_AFTER] = next.retryAfter
            prefs[KEY_LAST_FETCHED_AT] = next.lastFetchedAt
            prefs[KEY_SOURCE] = next.source
        }
        _state.value = next
        loaded = true
    }

    private fun decode(prefs: androidx.datastore.preferences.core.Preferences): LocalMaintenanceState {
        val hasAny = KEY_LAST_FETCHED_AT in prefs || KEY_VERSION in prefs
        if (!hasAny) return LocalMaintenanceState.EMPTY
        return LocalMaintenanceState(
            maintenanceActive = prefs[KEY_ACTIVE] ?: false,
            maintenanceScheduled = prefs[KEY_SCHEDULED] ?: false,
            maintenanceId = prefs[KEY_ID],
            version = prefs[KEY_VERSION] ?: 0L,
            updatedAt = prefs[KEY_UPDATED_AT],
            title = prefs[KEY_TITLE],
            message = prefs[KEY_MESSAGE],
            reason = prefs[KEY_REASON],
            type = prefs[KEY_TYPE],
            code = prefs[KEY_CODE],
            titleKey = prefs[KEY_TITLE_KEY],
            messageKey = prefs[KEY_MESSAGE_KEY],
            reasonKey = prefs[KEY_REASON_KEY],
            startsAt = prefs[KEY_STARTS_AT],
            endsAt = prefs[KEY_ENDS_AT],
            timezone = prefs[KEY_TIMEZONE],
            retryAfter = prefs[KEY_RETRY_AFTER]
                ?: LocalMaintenanceState.RETRY_AFTER_IDLE_SECONDS,
            lastFetchedAt = prefs[KEY_LAST_FETCHED_AT] ?: 0L,
            source = prefs[KEY_SOURCE] ?: LocalMaintenanceState.SOURCE_NONE,
        )
    }

    companion object {
        private const val TAG = "MaintStateStore"

        @Volatile
        private var instance: MaintenanceStateStore? = null

        fun getInstance(context: Context): MaintenanceStateStore =
            instance ?: synchronized(this) {
                instance
                    ?: MaintenanceStateStore(context.applicationContext).also { instance = it }
            }

        private val KEY_ACTIVE = booleanPreferencesKey("maintenance_active")
        private val KEY_SCHEDULED = booleanPreferencesKey("maintenance_scheduled")
        private val KEY_ID = stringPreferencesKey("maintenance_id")
        private val KEY_VERSION = longPreferencesKey("version")
        private val KEY_UPDATED_AT = stringPreferencesKey("updated_at")
        private val KEY_TITLE = stringPreferencesKey("title")
        private val KEY_MESSAGE = stringPreferencesKey("message")
        private val KEY_REASON = stringPreferencesKey("reason")
        private val KEY_TYPE = stringPreferencesKey("type")
        private val KEY_CODE = stringPreferencesKey("code")
        private val KEY_TITLE_KEY = stringPreferencesKey("title_key")
        private val KEY_MESSAGE_KEY = stringPreferencesKey("message_key")
        private val KEY_REASON_KEY = stringPreferencesKey("reason_key")
        private val KEY_STARTS_AT = stringPreferencesKey("starts_at")
        private val KEY_ENDS_AT = stringPreferencesKey("ends_at")
        private val KEY_TIMEZONE = stringPreferencesKey("timezone")
        private val KEY_RETRY_AFTER = intPreferencesKey("retry_after")
        private val KEY_LAST_FETCHED_AT = longPreferencesKey("last_fetched_at")
        private val KEY_SOURCE = stringPreferencesKey("source")
    }
}

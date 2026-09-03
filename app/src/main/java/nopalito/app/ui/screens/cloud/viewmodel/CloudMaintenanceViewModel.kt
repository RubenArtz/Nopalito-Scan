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

package nopalito.app.ui.screens.cloud.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nopalito.app.R
import nopalito.app.data.LocalMaintenanceState
import nopalito.app.data.MaintenanceStateLogic
import nopalito.app.data.MaintenanceStateStore
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.MaintenanceStatus
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel exposing cloud maintenance state (Phase 2 architecture).
 *
 * The persisted [MaintenanceStateStore] snapshot is the operational source of
 * truth; this ViewModel only bootstraps and reconciles it:
 *
 *  1. Local-first bootstrap: the persisted state drives the UI immediately,
 *     with no network call at all while it is still fresh (cold start shows
 *     the maintenance gate without a single request).
 *  2. ONE HTTP reconciliation when the snapshot is stale ([isStale]).
 *  3. TTL-driven staleness checks for the ViewModel's lifetime: delays come
 *     from [MaintenanceStateLogic.nextCheckDelayMs] (server `retry_after` /
 *     time-window heuristics / 24h idle), so an idle session performs zero
 *     polls and failures back off exponentially.
 *  4. Live updates: FCM pushes land in the store (NopalitoMessagingService)
 *     and reach the UI through the [store.state] collector - no polling.
 *
 * The host (CloudHost) gates every cloud screen on [maintenanceState], so the
 * maintenance view appears/disappears as soon as the store changes.
 */
class CloudMaintenanceViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = CloudRepository(application.applicationContext)
    private val store = MaintenanceStateStore.getInstance(application)

    private val _maintenanceState = MutableStateFlow<MaintenanceStatus?>(null)
    val maintenanceState: StateFlow<MaintenanceStatus?> = _maintenanceState.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isOffline = MutableStateFlow(false)

    /** Seconds until the next automatic check; surfaced for the UI label. */
    private val _refreshIntervalSeconds =
        MutableStateFlow(LocalMaintenanceState.RETRY_AFTER_ACTIVE_SECONDS)
    val refreshIntervalSeconds: StateFlow<Int> = _refreshIntervalSeconds.asStateFlow()

    private var consecutiveFailures = 0

    /** Guards [revalidateIfStale] against overlapping resume/loop fetches. */
    @Volatile
    private var revalidating = false

    init {
        viewModelScope.launch {
            // Local-first: persisted snapshot renders immediately.
            val local = store.reload()
            if (MaintenanceStateLogic.isStale(local, System.currentTimeMillis())) {
                Log.i(TAG, "bootstrap decision=stale_local -> one http fetch (v=${local.version})")
                publish(local)
                performFetch(LocalMaintenanceState.SOURCE_HTTP_BOOTSTRAP)
            } else {
                Log.i(
                    TAG,
                    "bootstrap decision=fresh_local v=${local.version} " +
                            "active=${local.maintenanceActive} scheduled=${local.maintenanceScheduled} " +
                            "source=${local.source}"
                )
                publish(local) // zero network: local state is the source of truth
            }

            // TTL-driven checks for the rest of this VM's lifetime.
            stalenessLoop()
        }
        // FCM-applied changes reach the UI here, without any polling.
        viewModelScope.launch {
            store.state.collect { publish(it) }
        }
    }

    /**
     * Foreground/resume reconciliation entry point (Phase 3). Fetches once
     * ONLY when the persisted snapshot expired its TTL; a fresh snapshot
     * costs nothing. Deduplicated against the staleness loop and concurrent
     * resume events via [revalidating].
     */
    fun revalidateIfStale() {
        if (revalidating) return
        val current = store.state.value
        if (!MaintenanceStateLogic.isStale(current, System.currentTimeMillis())) {
            Log.d(TAG, "resume skip: state fresh (v=${current.version}, source=${current.source})")
            return
        }
        viewModelScope.launch {
            revalidating = true
            try {
                Log.i(TAG, "resume reconcile: state stale (v=${current.version}) -> one http fetch")
                performFetch(LocalMaintenanceState.SOURCE_HTTP_RECONCILE)
            } finally {
                revalidating = false
            }
        }
    }

    /**
     * Manual re-check (retry button / external callers). Resets backoff and
     * forces one authoritative HTTP reconciliation.
     */
    fun checkMaintenanceStatus() {
        consecutiveFailures = 0
        viewModelScope.launch {
            performFetch(LocalMaintenanceState.SOURCE_HTTP_MANUAL)
        }
    }

    /** Retry button alias; kept explicit for readability in the screen. */
    fun retry() = checkMaintenanceStatus()

    /**
     * Returns the time remaining until the maintenance ends.
     * Returns null if no maintenance is scheduled.
     *
     * Works on API 26+: java.time is available from API 26. The only API 31
     * call (`Duration.toMinutesPart()`) was replaced by `toMinutes() % 60`,
     * which yields the identical value for the non-negative durations handled
     * here (negative durations return null above).
     */
    fun getTimeRemaining(): String? {
        val status = _maintenanceState.value ?: return null
        if (!status.maintenanceActive) return null

        return try {
            val endsAt = status.endsAt ?: return null
            val endInstant = java.time.Instant.parse(endsAt)
            val now = java.time.Instant.now()
            val remaining = java.time.Duration.between(now, endInstant)

            if (remaining.isNegative) return null

            val hours = remaining.toHours()
            val minutes = remaining.toMinutes() % 60

            when {
                hours > 0 -> getApplication<Application>().stringFor(
                    R.string.cloud_maint_duration_hours_min,
                    AppLocaleOverride.locale,
                    hours,
                    minutes
                )

                minutes > 0 -> getApplication<Application>().stringFor(
                    R.string.cloud_maint_duration_minutes, AppLocaleOverride.locale, minutes
                )

                else -> getApplication<Application>().stringFor(
                    R.string.cloud_maint_duration_seconds,
                    AppLocaleOverride.locale,
                    remaining.seconds
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    /**
     * Sleeps until the current snapshot may have gone stale, then reconciles
     * once. Idle-fresh state sleeps up to 24h (no meaningful polling); an
     * active maintenance re-checks roughly every server-suggested retry_after;
     * failures widen the wait exponentially instead of hammering.
     */
    private suspend fun stalenessLoop() {
        while (currentCoroutineContext().isActive) {
            val before = store.state.value
            val delayMs = MaintenanceStateLogic.nextCheckDelayMs(
                before, System.currentTimeMillis(), consecutiveFailures
            )
            delay(delayMs.milliseconds)
            val now = store.state.value
            if (!MaintenanceStateLogic.isStale(now, System.currentTimeMillis())) continue
            performFetch(LocalMaintenanceState.SOURCE_HTTP_RECONCILE)
        }
    }

    /**
     * One authoritative HTTP reconciliation. The store applies the snapshot
     * unless something newer landed mid-flight; the collector republishes.
     */
    private suspend fun performFetch(source: String) {
        _isChecking.value = true
        _error.value = null
        val startedAtMs = System.currentTimeMillis()
        try {
            val result = repository.checkMaintenanceStatus()
            result.onSuccess { status ->
                Log.d(
                    TAG,
                    "fetch SUCCESS: active=${status?.maintenanceActive}, " +
                            "scheduled=${status?.maintenanceScheduled}, id=${status?.id}, " +
                            "v=${status?.version}, retryAfter=${status?.retryAfter}, src=$source"
                )
                store.updateFromHttp(status, startedAtMs, source)
                consecutiveFailures = 0
                _isOffline.value = false
            }.onFailure { e ->
                Log.e(TAG, "fetch FAILURE (${e.javaClass.simpleName}) src=$source")
                _error.value =
                    CloudErrorPresenter.message(getApplication(), e, R.string.error_unknown)
                consecutiveFailures++
                _isOffline.value = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetch EXCEPTION (${e.javaClass.simpleName}) src=$source")
            _error.value =
                CloudErrorPresenter.message(getApplication(), e, R.string.error_unknown)
            consecutiveFailures++
            _isOffline.value = true
        } finally {
            _isChecking.value = false
        }
    }

    /** Mirrors a store change into the legacy UI model consumed by screens. */
    private fun publish(local: LocalMaintenanceState) {
        _maintenanceState.value = local.toUiModel()
        val seconds = MaintenanceStateLogic.clampedInterval(local.retryAfter).toInt()
        if (_refreshIntervalSeconds.value != seconds) {
            Log.d(TAG, "poll interval -> ${seconds}s (state source=${local.source})")
        }
        _refreshIntervalSeconds.value = seconds
    }

    /** Store model -> existing API model so screens/localizer stay untouched. */
    private fun LocalMaintenanceState.toUiModel(): MaintenanceStatus = MaintenanceStatus(
        maintenanceActive = maintenanceActive,
        maintenanceScheduled = maintenanceScheduled,
        id = maintenanceId,
        title = title,
        message = message,
        reason = reason,
        type = type,
        code = code,
        titleKey = titleKey,
        messageKey = messageKey,
        reasonKey = reasonKey,
        startsAt = startsAt,
        endsAt = endsAt,
        timezone = timezone,
        retryAfter = retryAfter,
        version = version,
        updatedAt = updatedAt,
    )

    companion object {
        private const val TAG = "CloudMaintenanceVM"
    }
}

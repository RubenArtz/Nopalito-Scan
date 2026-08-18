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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.MaintenanceStatus
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel for checking cloud maintenance status.
 *
 * Polls the backend every 30 seconds to determine if the cloud service
 * is in maintenance mode. The Android app uses this to block access to
 * the cloud module and show a maintenance screen.
 *
 * Key behaviors:
 * - Initial check on creation
 * - Auto-refresh every 30 seconds
 * - Manual retry via [retry]
 * - Handles offline gracefully (last known state)
 */
class CloudMaintenanceViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = CloudRepository(application.applicationContext)

    private val _maintenanceState = MutableStateFlow<MaintenanceStatus?>(null)
    val maintenanceState: StateFlow<MaintenanceStatus?> = _maintenanceState.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isOffline = MutableStateFlow(false)

    init {
        checkMaintenanceStatus()
        startAutoRefresh()
    }

    /**
     * Checks the maintenance status with the backend.
     * Called automatically on creation and every 30 seconds.
     * Can also be called manually via the retry button.
     */
    fun checkMaintenanceStatus() {
        viewModelScope.launch {
            _isChecking.value = true
            _error.value = null

            try {
                val result = repository.checkMaintenanceStatus()
                result.onSuccess { status ->
                    android.util.Log.d(
                        "CloudMaintenanceVM",
                        "checkMaintenanceStatus SUCCESS: maintenanceActive=${status?.maintenanceActive}, maintenanceScheduled=${status?.maintenanceScheduled}, id=${status?.id}"
                    )
                    _maintenanceState.value = status
                    _isOffline.value = false
                }.onFailure { e ->
                    android.util.Log.e(
                        "CloudMaintenanceVM",
                        "checkMaintenanceStatus FAILURE (${e.javaClass.simpleName})",
                    )
                    _error.value = CloudErrorPresenter.message(getApplication(), e, R.string.error_unknown)
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "CloudMaintenanceVM",
                    "checkMaintenanceStatus EXCEPTION (${e.javaClass.simpleName})",
                )
                _error.value = CloudErrorPresenter.message(getApplication(), e, R.string.error_unknown)
            } finally {
                _isChecking.value = false
            }
        }
    }

    /**
     * Starts auto-refresh every 30 seconds.
     * The interval is based on the retry_after value from the backend
     * if available, otherwise defaults to 30 seconds.
     */
    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_INTERVAL_MS.milliseconds)
                checkMaintenanceStatus()
            }
        }
    }

    /**
     * Manual retry triggered by the user tapping the retry button.
     */
    fun retry() {
        checkMaintenanceStatus()
    }

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
                    R.string.cloud_maint_duration_hours_min, AppLocaleOverride.locale, hours, minutes
                )

                minutes > 0 -> getApplication<Application>().stringFor(
                    R.string.cloud_maint_duration_minutes, AppLocaleOverride.locale, minutes
                )

                else -> getApplication<Application>().stringFor(
                    R.string.cloud_maint_duration_seconds, AppLocaleOverride.locale, remaining.seconds
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Auto-refresh interval in milliseconds (30 seconds). */
        private const val AUTO_REFRESH_INTERVAL_MS = 30_000L

        /** Auto-refresh interval in seconds, used to display the auto-refresh info. */
        const val autoRefreshSeconds: Int = (AUTO_REFRESH_INTERVAL_MS / 1000).toInt()
    }
}

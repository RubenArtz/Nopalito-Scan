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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.network.LogoutException
import kotlin.time.Duration.Companion.milliseconds

sealed class SplashState {
    data object Loading : SplashState()
    data object Authenticated : SplashState()
    data object Unauthenticated : SplashState()

    /** Biometric mode is on: the session must be unlocked before any API call. */
    data object NeedsUnlock : SplashState()

    data class Error(val message: String) : SplashState()
}

class CloudSplashViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow<SplashState>(SplashState.Loading)
    val state: StateFlow<SplashState> = _state.asStateFlow()

    init {
        checkSession()
    }

    /**
     * Public entry point for retry from UI button.
     * Also called once from init.
     */
    fun checkSession() {
        viewModelScope.launch {
            _state.value = SplashState.Loading

            // 1. Quick check: no session tokens at all → go to login
            if (!repository.hasSession()) {
                _state.value = SplashState.Unauthenticated
                return@launch
            }

            // 2. Biometric mode: no API call can run without the OS unlock
            // prompt; the gate screen drives it. No getMe/refresh here.
            if (repository.isBiometricMode()) {
                _state.value = SplashState.NeedsUnlock
                return@launch
            }

            try {
                // Total timeout: 15 seconds for the entire suspend operation
                val result = withTimeout(15_000L.milliseconds) {
                    performSessionCheck()
                }
                // Apply result from the session check
                when (result) {
                    is SessionCheckResult.Success -> _state.value = SplashState.Authenticated
                    is SessionCheckResult.Logout -> {
                        repository.clearSession()
                        _state.value = SplashState.Unauthenticated
                    }

                    is SessionCheckResult.Error -> {
                        _state.value = SplashState.Error(result.message)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                _state.value =
                    SplashState.Error(
                        application.stringFor(
                            R.string.cloud_session_timeout,
                            AppLocaleOverride.locale
                        )
                    )
            }
        }
    }

    private sealed class SessionCheckResult {
        data object Success : SessionCheckResult()
        data object Logout : SessionCheckResult()
        data class Error(val message: String) : SessionCheckResult()
    }

    private suspend fun performSessionCheck(): SessionCheckResult {
        // 2. Try GET /api/auth/me to validate current session
        val meResult = repository.getMe()
        if (meResult.isSuccess) {
            return SessionCheckResult.Success
        }

        // 3. getMe failed, try refresh token as fallback
        val refreshResult = repository.refreshToken()
        if (refreshResult.isSuccess) {
            return SessionCheckResult.Success
        }

        // 4. Both failed → determine if it's a refresh failure (logout) or network error
        val refreshError = refreshResult.exceptionOrNull()
        return if (refreshError is LogoutException) {
            SessionCheckResult.Logout
        } else {
            val message = when {
                refreshError is java.net.SocketTimeoutException -> application.stringFor(
                    R.string.cloud_connection_timeout,
                    AppLocaleOverride.locale
                )

                refreshError is java.net.UnknownHostException -> application.stringFor(
                    R.string.cloud_connection_error,
                    AppLocaleOverride.locale
                )

                refreshError != null -> CloudErrorPresenter.message(
                    application,
                    refreshError,
                    R.string.cloud_connection_generic_error
                )

                else -> application.stringFor(
                    R.string.cloud_connection_failed,
                    AppLocaleOverride.locale
                )
            }
            SessionCheckResult.Error(message)
        }
    }
}
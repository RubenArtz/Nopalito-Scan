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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.ApiException
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.SessionData

data class SessionsUiState(
    val isLoading: Boolean = false,
    val sessions: List<SessionData> = emptyList(),
    val activeCount: Int = 0,
    val maxConcurrent: Int = 0,
    val errorMessage: String? = null,
    val revokeSuccess: String? = null,
    val currentRevoked: Boolean = false
)

class CloudSessionsViewModel(
    private val application: Application,
    private val repository: CloudRepository
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SessionsUiState(isLoading = true))
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            val result = repository.listSessions()
            result.fold(
                onSuccess = { data ->
                    _state.value = SessionsUiState(
                        isLoading = false,
                        sessions = data.sessions,
                        activeCount = data.activeCount,
                        maxConcurrent = data.maxConcurrent
                    )
                },
                onFailure = { e ->
                    val msg = CloudErrorPresenter.message(application, e, R.string.error_unknown)
                    // Suspended session -> backend returns 403 AUTH_ACCOUNT_SUSPENDED, handled globally as logout
                    _state.value = _state.value.copy(isLoading = false, errorMessage = msg)
                }
            )
        }
    }

    fun revoke(sessionId: String, isCurrent: Boolean) {
        viewModelScope.launch {
            val result = repository.revokeSession(sessionId)
            result.fold(
                onSuccess = {
                    if (isCurrent) {
                        // Current device revoked — clear local session and signal logout
                        repository.clearSession()
                        _state.value = _state.value.copy(
                            currentRevoked = true,
                            revokeSuccess = application.stringFor(
                                R.string.cloud_sessions_current_revoked,
                                AppLocaleOverride.locale
                            )
                        )
                    } else {
                        _state.value = _state.value.copy(
                            revokeSuccess = application.stringFor(
                                R.string.cloud_sessions_revoked,
                                AppLocaleOverride.locale
                            )
                        )
                        refresh()
                    }
                },
                onFailure = { e ->
                    val msg = if (e is ApiException && e.code == "SESSION_NOT_FOUND") {
                        application.stringFor(
                            R.string.cloud_sessions_empty,
                            AppLocaleOverride.locale
                        )
                    } else {
                        CloudErrorPresenter.message(application, e, R.string.error_unknown)
                    }
                    _state.value = _state.value.copy(errorMessage = msg)
                }
            )
        }
    }

    fun consumeRevokeSuccess() {
        _state.value = _state.value.copy(revokeSuccess = null)
    }

    fun consumeError() {
        _state.value = _state.value.copy(errorMessage = null)
    }
}
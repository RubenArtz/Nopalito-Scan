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
import kotlinx.coroutines.Job
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
import nopalito.app.ui.screens.cloud.screens.CloudAuthDialog
import nopalito.app.ui.screens.cloud.screens.authAccountDialog
import kotlin.time.Duration.Companion.milliseconds

data class SessionRecoveryUiState(
    val email: String = "",
    val code: String = "",
    val isRequestLoading: Boolean = false,
    val isVerifyLoading: Boolean = false,
    val codeSent: Boolean = false,
    val isResending: Boolean = false,
    val resendCooldownSeconds: Int = 0,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null,
    val authDialog: CloudAuthDialog? = null
)

class SessionRecoveryViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionRecoveryUiState())
    val state: StateFlow<SessionRecoveryUiState> = _state.asStateFlow()

    private var cooldownJob: Job? = null

    fun updateEmail(email: String) {
        _state.value = _state.value.copy(email = email, errorMessage = null, authDialog = null)
    }

    fun updateCode(code: String) {
        val filtered = code.filter { it.isDigit() }.take(6)
        _state.value = _state.value.copy(code = filtered, errorMessage = null)
        if (filtered.length == 6) {
            // Auto-verify is not done for recovery — user must press Verify
        }
    }

    fun dismissDialog() {
        _state.value = _state.value.copy(authDialog = null)
    }

    fun consumeSuccess() {
        _state.value = _state.value.copy(isSuccess = false)
    }

    fun clear() {
        cooldownJob?.cancel()
        _state.value = SessionRecoveryUiState()
    }

    fun requestCode() {
        val email = _state.value.email.trim()
        if (email.isBlank() || !email.contains("@")) {
            _state.value = _state.value.copy(
                errorMessage = application.stringFor(
                    R.string.cloud_invalid_email,
                    AppLocaleOverride.locale
                )
            )
            return
        }
        viewModelScope.launch {
            _state.value =
                _state.value.copy(isRequestLoading = true, errorMessage = null, authDialog = null)
            val result = repository.requestSessionRecovery(email)
            result.fold(
                onSuccess = { data ->
                    _state.value = _state.value.copy(
                        isRequestLoading = false,
                        codeSent = true,
                        errorMessage = null
                    )
                    startResendCooldown(data.resendAvailableInSeconds)
                },
                onFailure = { e ->
                    val dialog = authAccountDialog(e, application)
                    _state.value = _state.value.copy(
                        isRequestLoading = false,
                        errorMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_sending_code
                        ),
                        authDialog = dialog
                    )
                }
            )
        }
    }

    fun resendCode() {
        val st = _state.value
        if (st.isRequestLoading || st.isResending || st.resendCooldownSeconds > 0) return
        val email = st.email.trim()
        if (email.isBlank()) return
        viewModelScope.launch {
            _state.value =
                _state.value.copy(isResending = true, errorMessage = null, authDialog = null)
            val result = repository.requestSessionRecovery(email)
            result.fold(
                onSuccess = { data ->
                    _state.value = _state.value.copy(
                        isResending = false,
                        errorMessage = null
                    )
                    startResendCooldown(data.resendAvailableInSeconds)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isResending = false,
                        errorMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_sending_code
                        ),
                        authDialog = authAccountDialog(e, application)
                    )
                }
            )
        }
    }

    fun verifyCode() {
        val email = _state.value.email.trim()
        val code = _state.value.code.trim()
        if (code.length != 6) {
            _state.value = _state.value.copy(
                errorMessage = application.stringFor(
                    R.string.cloud_otp_six_digits,
                    AppLocaleOverride.locale
                )
            )
            return
        }
        viewModelScope.launch {
            _state.value =
                _state.value.copy(isVerifyLoading = true, errorMessage = null, authDialog = null)
            val result = repository.verifySessionRecovery(email, code)
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(isVerifyLoading = false, isSuccess = true)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isVerifyLoading = false,
                        errorMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_invalid_code
                        ),
                        authDialog = authAccountDialog(e, application)
                    )
                }
            )
        }
    }

    private fun startResendCooldown(seconds: Int) {
        cooldownJob?.cancel()
        if (seconds <= 0) return
        cooldownJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _state.value = _state.value.copy(resendCooldownSeconds = remaining)
                delay(1000.milliseconds)
            }
            _state.value = _state.value.copy(resendCooldownSeconds = 0)
        }
    }
}
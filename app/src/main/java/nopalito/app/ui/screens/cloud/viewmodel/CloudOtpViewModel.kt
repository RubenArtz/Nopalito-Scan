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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository

data class OtpUiState(
    val email: String = "",
    val code: String = "",
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null,
    val isLogin: Boolean = true
)

class CloudOtpViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(OtpUiState())
    val state: StateFlow<OtpUiState> = _state.asStateFlow()

    fun initialize(email: String, isLogin: Boolean) {
        _state.value = OtpUiState(email = email, isLogin = isLogin)
    }

    fun updateCode(code: String) {
        // Only allow digits, max 6 characters
        val filtered = code.filter { it.isDigit() }.take(6)
        _state.value = _state.value.copy(code = filtered, errorMessage = null)

        // Auto-verify when 6 digits entered
        if (filtered.length == 6) {
            verifyCode()
        }
    }

    fun verifyCode() {
        val code = _state.value.code
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
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            val result = if (_state.value.isLogin) {
                repository.verifyLoginCode(_state.value.email, code)
            } else {
                repository.verifyRegisterCode(_state.value.email, code)
            }
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(isLoading = false, isSuccess = true)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = CloudErrorPresenter.message(application, e, R.string.cloud_invalid_code)
                    )
                }
            )
        }
    }

    fun resendCode() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            val result = repository.resendCode(_state.value.email)
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = null
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = CloudErrorPresenter.message(application, e, R.string.cloud_error_resending)
                    )
                }
            )
        }
    }
}

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
import nopalito.app.ui.screens.cloud.navigation.CloudRecoverMode
import nopalito.app.ui.screens.cloud.screens.CloudAuthDialog
import nopalito.app.ui.screens.cloud.screens.authAccountDialog

data class RecoverUiState(
    val mode: CloudRecoverMode = CloudRecoverMode.FORGOT,
    val email: String = "",
    val code: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val codeSent: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
    val authDialog: CloudAuthDialog? = null
)

@Suppress("USELESS_ELVIS_LEFT_IS_NULL")
class CloudRecoverViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(RecoverUiState())
    val state: StateFlow<RecoverUiState> = _state.asStateFlow()

    fun initialize(mode: CloudRecoverMode) {
        if (_state.value.mode != mode) {
            _state.value = RecoverUiState(mode = mode)
        }
    }

    fun updateEmail(email: String) = update { it.copy(email = email, errorMessage = null, authDialog = null) }
    fun updateCode(code: String) = update { it.copy(code = code.filter { it.isDigit() }.take(6), errorMessage = null) }
    fun updateNewPassword(password: String) = update { it.copy(newPassword = password, errorMessage = null) }
    fun updateConfirmPassword(confirm: String) = update { it.copy(confirmPassword = confirm, errorMessage = null) }

    fun dismissDialog() = update { it.copy(authDialog = null) }

    fun sendCode() {
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
            _state.value = _state.value.copy(isLoading = true, errorMessage = null, authDialog = null)
            val result = if (_state.value.mode == CloudRecoverMode.FORGOT) {
                repository.forgotPassword(email)
            } else {
                repository.requestSetPasswordCode(email)
            }
            result.fold(
                onSuccess = {
                    // The response is generic by design (no user enumeration).
                    _state.value = _state.value.copy(
                        isLoading = false,
                        codeSent = true,
                        email = email,
                        message = application.stringFor(R.string.cloud_recover_code_sent, AppLocaleOverride.locale)
                    )
                },
                onFailure = { e ->
                    val dialog = authAccountDialog(e, application)
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = CloudErrorPresenter.message(application, e, R.string.cloud_error_sending_code),
                        authDialog = dialog
                    )
                }
            )
        }
    }

    fun sendCodeAgain() {
        _state.value = _state.value.copy(code = "", newPassword = "", confirmPassword = "", codeSent = false)
        sendCode()
    }

    fun submit() {
        val st = _state.value
        val error = when {
            st.code.length != 6 -> R.string.cloud_otp_six_digits
            st.newPassword.length < CloudRegisterViewModel.MIN_PASSWORD_LENGTH -> R.string.cloud_password_too_short
            st.newPassword != st.confirmPassword -> R.string.cloud_passwords_mismatch
            else -> null
        }
        if (error != null) {
            _state.value = _state.value.copy(errorMessage = application.stringFor(error, AppLocaleOverride.locale))
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            val result = if (_state.value.mode == CloudRecoverMode.FORGOT) {
                repository.resetPassword(st.email, st.code, st.newPassword, st.confirmPassword)
            } else {
                repository.setPassword(st.email, st.code, st.newPassword, st.confirmPassword)
            }
            result.fold(
                onSuccess = {
                    // No auto-login: the user signs in with the new password.
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

    private inline fun update(block: (RecoverUiState) -> RecoverUiState) {
        _state.value = block(_state.value)
    }
}

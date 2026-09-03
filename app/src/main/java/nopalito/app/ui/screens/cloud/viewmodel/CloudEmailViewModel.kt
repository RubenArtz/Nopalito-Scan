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
import androidx.lifecycle.ViewModel
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
import nopalito.app.ui.screens.cloud.screens.CloudAuthDialog
import nopalito.app.ui.screens.cloud.screens.authAccountDialog

data class EmailUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val isDirectLogin: Boolean = false,
    val errorMessage: String? = null,
    val authDialog: CloudAuthDialog? = null
)

@Suppress("USELESS_ELVIS_LEFT_IS_NULL")
class CloudEmailViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(EmailUiState())
    val state: StateFlow<EmailUiState> = _state.asStateFlow()

    fun updateEmail(email: String) {
        _state.value = _state.value.copy(email = email, errorMessage = null, authDialog = null)
    }

    fun updatePassword(password: String) {
        _state.value =
            _state.value.copy(password = password, errorMessage = null, authDialog = null)
    }

    /**
     * Resets the state so that returning from other auth screens does not
     * re-trigger an implicit login due to residual isSuccess.
     */
    fun resetState() {
        _state.value = EmailUiState(email = _state.value.email)
    }

    fun consumeSuccess() {
        _state.value = _state.value.copy(isSuccess = false, isDirectLogin = false)
    }

    fun dismissDialog() {
        _state.value = _state.value.copy(authDialog = null)
    }

    /**
     * Password login. When the account has `require_login_code=true` (default)
     * the server returns a code challenge and the OTP screen must be shown;
     * when false the server returns tokens directly and we can mark the
     * session authenticated without an extra step.
     */
    fun login() {
        val email = _state.value.email.trim()
        val password = _state.value.password
        if (email.isBlank() || !email.contains("@") || password.isBlank()) {
            _state.value = _state.value.copy(
                errorMessage = application.stringFor(
                    R.string.cloud_invalid_credentials,
                    AppLocaleOverride.locale
                )
            )
            return
        }

        viewModelScope.launch {
            _state.value =
                _state.value.copy(isLoading = true, errorMessage = null, authDialog = null)
            val result = repository.loginWithPassword(email, password)
            result.fold(
                onSuccess = { data ->
                    val isDirect = data.accessToken != null && data.refreshToken != null
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isSuccess = true,
                        isDirectLogin = isDirect
                    )
                },
                onFailure = { e ->
                    // Debug: log actual backend code to ensure suspended is not masked as invalid credentials
                    val apiCode = (e as? ApiException)?.code
                    val httpStatus = (e as? ApiException)?.httpStatus
                    Log.d(
                        "CloudEmailVM",
                        "login failure code=$apiCode status=$httpStatus msg=${e.message}"
                    )
                    // Explicit suspended check — ensures modal is shown even if mapper fallback would hide it
                    // Also treat 403 with null code as suspended (parsing fallback when errorBody was closed)
                    val isSuspended = apiCode == ApiException.AUTH_ACCOUNT_SUSPENDED ||
                            apiCode == ApiException.AUTH_LOGIN_BLOCKED_SUSPENDED ||
                            apiCode == ApiException.AUTH_PASSWORD_RESET_BLOCKED_SUSPENDED ||
                            apiCode?.contains("SUSPENDED") == true ||
                            (apiCode == null && httpStatus == 403)
                    val dialog = authAccountDialog(e, application) ?: if (isSuspended) {
                        CloudAuthDialog(
                            application.stringFor(
                                R.string.cloud_dialog_account_suspended_title,
                                AppLocaleOverride.locale
                            ),
                            application.stringFor(
                                R.string.cloud_dialog_account_suspended_body,
                                AppLocaleOverride.locale
                            )
                        )
                    } else null
                    val errorMsg = if (isSuspended) {
                        // Use dedicated suspended string, not generic invalid credentials
                        application.stringFor(
                            R.string.cloud_error_auth_account_suspended,
                            AppLocaleOverride.locale
                        )
                    } else {
                        CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_invalid_credentials
                        )
                    }
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = errorMsg,
                        authDialog = dialog
                    )
                }
            )
        }
    }
}

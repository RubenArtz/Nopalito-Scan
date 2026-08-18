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
import nopalito.app.ui.screens.cloud.screens.CloudAuthDialog
import nopalito.app.ui.screens.cloud.screens.authAccountDialog

data class EmailUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
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
        _state.value = _state.value.copy(password = password, errorMessage = null, authDialog = null)
    }

    /**
     * Resets the state so that returning from other auth screens does not
     * re-trigger an implicit login due to residual isSuccess.
     */
    fun resetState() {
        _state.value = EmailUiState(email = _state.value.email)
    }

    fun dismissDialog() {
        _state.value = _state.value.copy(authDialog = null)
    }

    /**
     * Password login (primary flow). The server answers with a uniform generic
     * error when credentials are wrong or the account has no password yet
     * (legacy account) â€” the user can use the set-password/forgot flows below.
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
            _state.value = _state.value.copy(isLoading = true, errorMessage = null, authDialog = null)
            val result = repository.loginWithPassword(email, password)
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(isLoading = false, isSuccess = true)
                },
                onFailure = { e ->
                    val dialog = authAccountDialog(e, application)
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = CloudErrorPresenter.message(application, e, R.string.cloud_invalid_credentials),
                        authDialog = dialog
                    )
                }
            )
        }
    }
}

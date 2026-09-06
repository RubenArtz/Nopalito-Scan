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
import nopalito.app.ui.screens.cloud.data.ApiException
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository

data class RegisterUiState(
    val displayName: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
)

class CloudRegisterViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(RegisterUiState())
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    fun updateDisplayName(displayName: String) =
        _state.update { it.copy(displayName = displayName, errorMessage = null) }

    fun updateEmail(email: String) = _state.update { it.copy(email = email, errorMessage = null) }
    fun updatePassword(password: String) =
        _state.update { it.copy(password = password, errorMessage = null) }

    fun updateConfirmPassword(confirmPassword: String) =
        _state.update { it.copy(confirmPassword = confirmPassword, errorMessage = null) }

    fun reset() = _state.update {
        RegisterUiState(
            displayName = _state.value.displayName,
            email = _state.value.email
        )
    }

    /**
     * Client-side validation only â€” the backend is the authority (password
     * policy, email format). On success the OTP screen completes the signup.
     */
    fun register() {
        val displayName = _state.value.displayName.trim()
        val email = _state.value.email.trim()
        val password = _state.value.password
        val confirm = _state.value.confirmPassword
        val error = when {
            displayName.isNotBlank() && displayName.length < 2 -> R.string.cloud_name_too_short
            email.isBlank() || !email.contains("@") -> R.string.cloud_invalid_email
            password.length < MIN_PASSWORD_LENGTH -> R.string.cloud_password_too_short
            password != confirm -> R.string.cloud_passwords_mismatch
            else -> null
        }
        if (error != null) {
            _state.update {
                it.copy(
                    errorMessage = application.stringFor(
                        error,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            repository.register(displayName, email, password, confirm).fold(
                onSuccess = {
                    _state.update { it.copy(isLoading = false, isSuccess = true) }
                },
                onFailure = { e ->
                    // A registered email is rejected by the backend with a clear
                    // code; show a friendly message instead of the raw error.
                    val msg =
                        if (e is ApiException && e.code == ApiException.EMAIL_ALREADY_REGISTERED) {
                            application.stringFor(
                                R.string.cloud_email_already_registered,
                                AppLocaleOverride.locale
                            )
                        } else {
                            CloudErrorPresenter.message(
                                application,
                                e,
                                R.string.cloud_error_sending_code
                            )
                        }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = msg
                        )
                    }
                }
            )
        }
    }

    private inline fun MutableStateFlow<RegisterUiState>.update(block: (RegisterUiState) -> RegisterUiState) {
        value = block(value)
    }

    companion object {
        // Mirrors the backend policy (PASSWORD_MIN_LENGTH default 8).
        const val MIN_PASSWORD_LENGTH = 8
    }
}

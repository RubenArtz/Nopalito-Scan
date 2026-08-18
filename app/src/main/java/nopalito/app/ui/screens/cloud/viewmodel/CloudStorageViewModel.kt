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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nopalito.app.NopalitoApp
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.StorageUsage
import nopalito.app.ui.screens.cloud.security.BiometricUnlockOutcome

data class StorageUiState(
    val usage: StorageUsage? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,

    // Download folder (SAF tree URI used by every download destination).
    val downloadDirUri: String? = null,

    // Biometric unlock (toggle on this screen).
    val biometricEnabled: Boolean = false,
    val biometricBusy: Boolean = false,
    val biometricMessage: String? = null,

    // Change password (inline, same view): code by email â†’ set new password.
    val changeOpen: Boolean = false,
    val changeCode: String = "",
    val changeNewPassword: String = "",
    val changeConfirmPassword: String = "",
    val changeCodeSent: Boolean = false,
    val changeSending: Boolean = false,
    val changeSubmitting: Boolean = false,
    val changeSuccess: Boolean = false,
    val changeError: String? = null
)

class CloudStorageViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(
        StorageUiState(biometricEnabled = repository.isBiometricMode())
    )
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    private val settingsRepository by lazy {
        (application.applicationContext as NopalitoApp).appContainer.settingsRepository
    }

    init {
        refreshDownloadDir()
    }

    fun refreshDownloadDir() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                downloadDirUri = settingsRepository.downloadDirUri.first()
            )
        }
    }

    /** Stores the SAF tree URI picked by the user; null resets to the default. */
    fun setDownloadDir(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setDownloadDirUri(uri)
            _state.value = _state.value.copy(downloadDirUri = uri)
        }
    }

    /**
     * Turns biometric unlock on/off. The repository owns the token migration
     * (normal prefs ↔ auth-bound blob); this only renders the outcome and
     * refuses to re-trigger while a prompt is in flight.
     */
    fun toggleBiometric() {
        val current = _state.value
        if (current.biometricBusy) return
        _state.value = current.copy(biometricBusy = true, biometricMessage = null)
        repository.setBiometricEnabled(!current.biometricEnabled) { outcome ->
            _state.value = when (outcome) {
                is BiometricUnlockOutcome.Enabled -> _state.value.copy(
                    biometricEnabled = true, biometricBusy = false, biometricMessage = null
                )

                is BiometricUnlockOutcome.Disabled -> _state.value.copy(
                    biometricEnabled = false, biometricBusy = false, biometricMessage = null
                )

                is BiometricUnlockOutcome.Cancelled,
                is BiometricUnlockOutcome.Unlocked,
                    -> _state.value.copy(biometricBusy = false)

                is BiometricUnlockOutcome.LockedOut -> _state.value.copy(
                    biometricBusy = false,
                    biometricMessage = application.stringFor(
                        R.string.cloud_biometric_unlock_locked_out,
                        AppLocaleOverride.locale
                    )
                )

                is BiometricUnlockOutcome.NotAvailable -> _state.value.copy(
                    biometricBusy = false,
                    biometricMessage = application.stringFor(
                        R.string.cloud_biometric_unlock_unavailable,
                        AppLocaleOverride.locale
                    )
                )

                is BiometricUnlockOutcome.NoSecureLockScreen -> _state.value.copy(
                    biometricBusy = false,
                    biometricMessage = application.stringFor(
                        R.string.cloud_biometric_unlock_no_screen_lock,
                        AppLocaleOverride.locale
                    )
                )

                is BiometricUnlockOutcome.Failed,
                is BiometricUnlockOutcome.KeyInvalidated,
                    -> _state.value.copy(
                    biometricBusy = false,
                    biometricMessage = application.stringFor(
                        R.string.cloud_biometric_toggle_failed,
                        AppLocaleOverride.locale
                    )
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true, errorMessage = null)
            repository.getStorageUsage().fold(
                onSuccess = { usage ->
                    // The limit/plan always come from the backend: the client
                    // only renders them.
                    _state.value = _state.value.copy(usage = usage, isLoading = false, isRefreshing = false)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = CloudErrorPresenter.message(application, e, R.string.error_unknown)
                    )
                }
            )
        }
    }

    fun toggleChangePassword() {
        _state.value = _state.value.copy(
            changeOpen = !_state.value.changeOpen,
            changeCode = "",
            changeNewPassword = "",
            changeConfirmPassword = "",
            changeCodeSent = false,
            changeSuccess = false,
            changeError = null
        )
    }

    private inline fun updateChange(block: (StorageUiState) -> StorageUiState) {
        _state.value = block(_state.value)
    }

    fun updateChangeCode(code: String) =
        updateChange { it.copy(changeCode = code.filter { c -> c.isDigit() }.take(6), changeError = null) }

    fun updateChangeNewPassword(password: String) =
        updateChange { it.copy(changeNewPassword = password, changeError = null) }

    fun updateChangeConfirmPassword(confirm: String) =
        updateChange { it.copy(changeConfirmPassword = confirm, changeError = null) }

    /**
     * Sends a single-use verification code to the account email. Everything
     * (code + new password) stays inside this same Storage view.
     */
    fun requestChangePasswordCode() {
        val email = repository.getCurrentUserEmail()
        if (email.isNullOrBlank()) {
            updateChange {
                it.copy(
                    changeError = application.stringFor(
                        R.string.cloud_no_session,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            updateChange { it.copy(changeSending = true, changeError = null) }
            repository.requestSetPasswordCode(email).fold(
                onSuccess = {
                    // Generic by design: never reveals whether the mail is valid.
                    updateChange { it.copy(changeSending = false, changeCodeSent = true) }
                },
                onFailure = { e ->
                    updateChange {
                        it.copy(
                            changeSending = false,
                            changeError = CloudErrorPresenter.message(application, e, R.string.cloud_error_sending_code)
                        )
                    }
                }
            )
        }
    }

    fun resendChangePasswordCode() {
        updateChange { it.copy(changeCode = "") }
        requestChangePasswordCode()
    }

    /**
     * Verifies the code and stores the new password hash. All sessions are
     * revoked by the backend â†’ the app returns to the sign-in screen.
     */
    fun submitChangePassword() {
        val email = repository.getCurrentUserEmail()
        if (email.isNullOrBlank()) {
            updateChange {
                it.copy(
                    changeError = application.stringFor(
                        R.string.cloud_no_session,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }
        val st = _state.value
        val error = when {
            st.changeCode.length != 6 -> R.string.cloud_otp_six_digits
            st.changeNewPassword.length < CloudRegisterViewModel.MIN_PASSWORD_LENGTH -> R.string.cloud_password_too_short
            st.changeNewPassword != st.changeConfirmPassword -> R.string.cloud_passwords_mismatch
            else -> null
        }
        if (error != null) {
            updateChange { it.copy(changeError = application.stringFor(error, AppLocaleOverride.locale)) }
            return
        }

        viewModelScope.launch {
            updateChange { it.copy(changeSubmitting = true, changeError = null) }
            repository.setPassword(email, st.changeCode, st.changeNewPassword, st.changeConfirmPassword).fold(
                onSuccess = { updateChange { it.copy(changeSubmitting = false, changeSuccess = true) } },
                onFailure = { e ->
                    updateChange {
                        it.copy(
                            changeSubmitting = false,
                            changeError = CloudErrorPresenter.message(application, e, R.string.cloud_invalid_code)
                        )
                    }
                }
            )
        }
    }
}

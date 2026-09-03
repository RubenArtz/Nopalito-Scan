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
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository

data class AccountLinkUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false,
    val showDialog: Boolean = false,
    val successMessage: String? = null
)

class AccountLinkGoogleViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountLinkUiState())
    val state: StateFlow<AccountLinkUiState> = _state.asStateFlow()

    fun linkGoogle(idToken: String) {
        if (idToken.isBlank()) {
            Log.w(TAG, "linkGoogle called with blank idToken")
            _state.value = _state.value.copy(
                errorMessage = application.stringFor(
                    R.string.account_error_link_google,
                    AppLocaleOverride.locale
                ),
                showDialog = true
            )
            return
        }
        Log.d(TAG, "Calling backend POST /api/account/link-google: idTokenReceived=true")
        viewModelScope.launch {
            _state.value =
                _state.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            repository.linkGoogleAccount(idToken).fold(
                onSuccess = {
                    Log.d(
                        TAG,
                        "Backend POST /api/account/link-google succeeded: linked=${it.linked} created=${it.created}"
                    )
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isSuccess = true,
                        showDialog = true,
                        successMessage = application.stringFor(
                            R.string.account_google_linked_success,
                            AppLocaleOverride.locale
                        )
                    )
                },
                onFailure = { e ->
                    val code = (e as? nopalito.app.ui.screens.cloud.data.ApiException)?.code
                    Log.w(
                        TAG,
                        "Backend POST /api/account/link-google failed: code=$code message=${e.message}"
                    )
                    val message = CloudErrorPresenter.message(
                        application,
                        e,
                        R.string.account_error_link_google
                    )
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = message,
                        showDialog = true
                    )
                }
            )
        }
    }

    fun onGoogleCancelled() {
        Log.d(TAG, "Google link cancelled by user")
        _state.value = _state.value.copy(isLoading = false)
    }

    fun onGoogleNetworkError() {
        Log.w(TAG, "Google link network error")
        _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = application.stringFor(
                R.string.cloud_error_google_network,
                AppLocaleOverride.locale
            ),
            showDialog = true
        )
    }

    fun onGoogleNoAccount() {
        Log.w(TAG, "Google link no account available")
        _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = application.stringFor(
                R.string.cloud_error_google_no_account,
                AppLocaleOverride.locale
            ),
            showDialog = true
        )
    }

    fun onGoogleConfigError() {
        Log.e(TAG, "Google link configuration error")
        _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = application.stringFor(
                R.string.cloud_error_google_unavailable,
                AppLocaleOverride.locale
            ),
            showDialog = true
        )
    }

    fun onGoogleNotConfigured() {
        Log.e(TAG, "Google link not configured (blank client ID)")
        _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = application.stringFor(
                R.string.cloud_error_google_not_configured,
                AppLocaleOverride.locale
            ),
            showDialog = true
        )
    }

    fun onGoogleGenericError() {
        Log.w(TAG, "Google link generic failure")
        _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = application.stringFor(
                R.string.cloud_error_google_generic,
                AppLocaleOverride.locale
            ),
            showDialog = true
        )
    }

    fun onGoogleReauthRequired() {
        Log.w(TAG, "Google link re-auth required")
        _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = application.stringFor(
                R.string.cloud_error_google_reauth_required,
                AppLocaleOverride.locale
            ),
            showDialog = true
        )
    }

    companion object {
        private const val TAG = "GoogleSignIn"
    }

    fun reset() {
        _state.value = AccountLinkUiState()
    }
}
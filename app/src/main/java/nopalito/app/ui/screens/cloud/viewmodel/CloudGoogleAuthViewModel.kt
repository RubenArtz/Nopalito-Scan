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
import nopalito.app.ui.screens.cloud.screens.CloudAuthDialog
import nopalito.app.ui.screens.cloud.screens.authAccountDialog

data class GoogleAuthUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null,
    val authDialog: CloudAuthDialog? = null
)

class CloudGoogleAuthViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(GoogleAuthUiState())
    val state: StateFlow<GoogleAuthUiState> = _state.asStateFlow()

    fun reset() {
        _state.value = GoogleAuthUiState()
    }

    fun consumeSuccess() {
        _state.value = _state.value.copy(isSuccess = false)
    }

    fun dismissDialog() {
        _state.value = _state.value.copy(authDialog = null)
    }

    /**
     * Exchanges a Google ID token for backend JWTs.
     * On success the session is persisted by [CloudRepository.googleSignIn]
     * (including biometric migration) so the caller can navigate to the
     * authenticated destination.
     */
    fun signInWithGoogle(idToken: String) {
        if (idToken.isBlank()) {
            Log.w(TAG, "signInWithGoogle called with blank idToken - aborting")
            _state.value = _state.value.copy(
                errorMessage = application.stringFor(
                    R.string.cloud_error_google_token_invalid,
                    AppLocaleOverride.locale
                )
            )
            return
        }
        Log.d(TAG, "Calling backend POST /api/auth/google: idTokenReceived=true")
        viewModelScope.launch {
            _state.value =
                _state.value.copy(isLoading = true, errorMessage = null, authDialog = null)
            val result = repository.googleSignIn(idToken)
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Backend POST /api/auth/google succeeded")
                    _state.value = _state.value.copy(isLoading = false, isSuccess = true)
                },
                onFailure = { e ->
                    val code = (e as? nopalito.app.ui.screens.cloud.data.ApiException)?.code
                    Log.w(
                        TAG,
                        "Backend POST /api/auth/google failed: code=$code httpStatus=${(e as? nopalito.app.ui.screens.cloud.data.ApiException)?.httpStatus} message=${e.message}"
                    )
                    val dialog = authAccountDialog(e, application)
                    val message = CloudErrorPresenter.message(
                        application,
                        e,
                        R.string.cloud_error_google_token_invalid
                    )
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = message,
                        authDialog = dialog
                    )
                }
            )
        }
    }

    /**
     * Handles a Google Sign-In cancellation / network error that never reached
     * the backend (e.g. user dismissed the Credential Manager sheet).
     */
    fun onGoogleCancelled() {
        Log.d(TAG, "Google sign-in cancelled by user - no UI error")
        // Silent: user intentionally dismissed, no error banner
        _state.value = _state.value.copy(isLoading = false)
    }

    fun onGoogleNetworkError() {
        Log.w(TAG, "Google sign-in network error")
        _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = application.stringFor(
                R.string.cloud_error_google_network,
                AppLocaleOverride.locale
            )
        )
    }

    fun onGoogleNoAccount() {
        Log.w(TAG, "Google sign-in no account available")
        _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = application.stringFor(
                R.string.cloud_error_google_no_account,
                AppLocaleOverride.locale
            )
        )
    }

    fun onGoogleConfigError() {
        Log.e(TAG, "Google sign-in configuration error")
        _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = application.stringFor(
                R.string.cloud_error_google_unavailable,
                AppLocaleOverride.locale
            )
        )
    }

    fun onGoogleNotConfigured() {
        Log.e(TAG, "Google sign-in not configured (blank client ID)")
        _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = application.stringFor(
                R.string.cloud_error_google_not_configured,
                AppLocaleOverride.locale
            )
        )
    }

    fun onGoogleGenericError() {
        Log.w(TAG, "Google sign-in generic failure")
        _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = application.stringFor(
                R.string.cloud_error_google_generic,
                AppLocaleOverride.locale
            )
        )
    }

    fun onGoogleReauthRequired() {
        Log.w(TAG, "Google sign-in re-auth required")
        _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = application.stringFor(
                R.string.cloud_error_google_reauth_required,
                AppLocaleOverride.locale
            )
        )
    }

    companion object {
        private const val TAG = "GoogleSignIn"
    }
}
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
 */

package nopalito.app.ui.screens.cloud.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
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

/**
 * ViewModel for the profile avatar modal.
 * Handles loading, uploading and removing the avatar.
 * All user-visible messages are localized via string resources.
 */
data class ProfileAvatarUiState(
    val avatarUrl: String? = null,
    val thumbnailUrl: String? = null,
    val source: String = "default", // custom|google|default
    val provider: String = "local", // local|google|hybrid
    val version: Int = 0,
    val displayName: String? = null,
    val email: String? = null,
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val isDeleting: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
) {
    val isGoogleManaged: Boolean get() = provider == "google" || provider == "hybrid"
    val hasCustomAvatar: Boolean get() = source == "custom" && !avatarUrl.isNullOrBlank()
    val canRemovePhoto: Boolean get() = !isGoogleManaged && hasCustomAvatar
}

class ProfileAvatarViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ProfileAvatarUiState(isLoading = true))
    val state: StateFlow<ProfileAvatarUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value =
                _state.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            // Load profile to get provider/displayName/email plus avatar.
            val profileResult = repository.getProfile()
            val avatarResult = repository.getAvatar()

            val profile = profileResult.getOrNull()
            val avatar = avatarResult.getOrNull()

            if (avatar != null) {
                _state.value = _state.value.copy(
                    avatarUrl = avatar.url,
                    thumbnailUrl = avatar.thumbnailUrl,
                    source = avatar.source ?: "default",
                    provider = avatar.provider ?: profile?.authProvider ?: "local",
                    version = avatar.version,
                    displayName = profile?.displayName,
                    email = profile?.email,
                    isLoading = false,
                )
            } else if (profile != null) {
                // Fallback to profile.avatar if direct avatar fetch failed.
                val fallback = profile.avatar
                _state.value = _state.value.copy(
                    avatarUrl = fallback?.url ?: profile.googlePicture,
                    thumbnailUrl = fallback?.thumbnailUrl,
                    source = fallback?.source
                        ?: if (!profile.googlePicture.isNullOrBlank() && (profile.authProvider == "google" || profile.authProvider == "hybrid")) "google" else "default",
                    provider = profile.authProvider ?: "local",
                    version = fallback?.version ?: 0,
                    displayName = profile.displayName,
                    email = profile.email,
                    isLoading = false,
                    errorMessage = if (avatarResult.isFailure) {
                        CloudErrorPresenter.message(
                            application,
                            avatarResult.exceptionOrNull(),
                            R.string.error_unknown
                        )
                    } else null,
                )
            } else {
                _state.value = _state.value.copy(
                    isLoading = false,
                    errorMessage = application.stringFor(
                        R.string.error_unknown,
                        AppLocaleOverride.locale
                    ),
                )
            }
        }
    }

    fun uploadAvatar(uri: Uri) {
        if (_state.value.isGoogleManaged) {
            _state.value = _state.value.copy(
                errorMessage = application.stringFor(
                    R.string.avatar_google_managed_error,
                    AppLocaleOverride.locale
                ),
            )
            return
        }
        viewModelScope.launch {
            _state.value =
                _state.value.copy(isUploading = true, errorMessage = null, successMessage = null)
            val result = repository.uploadAvatar(uri)
            if (result.isSuccess) {
                val avatar = result.getOrNull()
                _state.value = _state.value.copy(
                    avatarUrl = avatar?.url,
                    thumbnailUrl = avatar?.thumbnailUrl,
                    source = avatar?.source ?: "custom",
                    version = avatar?.version ?: (_state.value.version + 1),
                    isUploading = false,
                    successMessage = application.stringFor(
                        R.string.avatar_upload_success,
                        AppLocaleOverride.locale
                    ),
                )
            } else {
                val ex = result.exceptionOrNull() as? Exception
                val apiCode = (ex as? nopalito.app.ui.screens.cloud.data.ApiException)?.code
                val message = when (apiCode) {
                    "AVATAR_GOOGLE_MANAGED" -> application.stringFor(
                        R.string.avatar_google_managed_error,
                        AppLocaleOverride.locale
                    )

                    "AVATAR_INVALID_FILE" -> application.stringFor(
                        R.string.avatar_invalid_file,
                        AppLocaleOverride.locale
                    )

                    "AVATAR_TOO_LARGE" -> application.stringFor(
                        R.string.avatar_invalid_file,
                        AppLocaleOverride.locale
                    )

                    else -> CloudErrorPresenter.message(
                        application,
                        ex,
                        R.string.avatar_upload_error
                    )
                }
                _state.value = _state.value.copy(isUploading = false, errorMessage = message)
            }
        }
    }

    fun deleteAvatar() {
        if (_state.value.isGoogleManaged || !_state.value.hasCustomAvatar) return
        viewModelScope.launch {
            _state.value =
                _state.value.copy(isDeleting = true, errorMessage = null, successMessage = null)
            val result = repository.deleteAvatar()
            if (result.isSuccess) {
                val avatar = result.getOrNull()
                _state.value = _state.value.copy(
                    avatarUrl = avatar?.url,
                    thumbnailUrl = avatar?.thumbnailUrl,
                    source = avatar?.source ?: "default",
                    version = avatar?.version ?: 0,
                    isDeleting = false,
                    successMessage = application.stringFor(
                        R.string.avatar_remove_success,
                        AppLocaleOverride.locale
                    ),
                )
            } else {
                val ex = result.exceptionOrNull() as? Exception
                val apiCode = (ex as? nopalito.app.ui.screens.cloud.data.ApiException)?.code
                val message = when (apiCode) {
                    "AVATAR_GOOGLE_MANAGED" -> application.stringFor(
                        R.string.avatar_google_managed_error,
                        AppLocaleOverride.locale
                    )

                    else -> CloudErrorPresenter.message(
                        application,
                        ex,
                        R.string.avatar_remove_error
                    )
                }
                _state.value = _state.value.copy(isDeleting = false, errorMessage = message)
            }
        }
    }
}
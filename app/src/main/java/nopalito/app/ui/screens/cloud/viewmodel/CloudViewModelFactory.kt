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
import androidx.lifecycle.ViewModelProvider
import nopalito.app.ui.screens.cloud.data.CloudRepository

/**
 * Simple ViewModelProvider.Factory that provides CloudRepository to all cloud ViewModels.
 * Matches the existing app pattern of custom ViewModelFactory.
 */
class CloudViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {

    private val repository by lazy { CloudRepository(application) }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(CloudSplashViewModel::class.java) ->
                CloudSplashViewModel(repository, application) as T

            modelClass.isAssignableFrom(BiometricGateViewModel::class.java) ->
                BiometricGateViewModel(repository.biometricSessionManager(), application) as T

            modelClass.isAssignableFrom(CloudEmailViewModel::class.java) ->
                CloudEmailViewModel(repository, application) as T

            modelClass.isAssignableFrom(CloudOtpViewModel::class.java) ->
                CloudOtpViewModel(repository, application) as T

            modelClass.isAssignableFrom(CloudRegisterViewModel::class.java) ->
                CloudRegisterViewModel(repository, application) as T

            modelClass.isAssignableFrom(CloudRecoverViewModel::class.java) ->
                CloudRecoverViewModel(repository, application) as T

            modelClass.isAssignableFrom(CloudHomeViewModel::class.java) ->
                CloudHomeViewModel(repository, application) as T

            modelClass.isAssignableFrom(CloudStorageViewModel::class.java) ->
                CloudStorageViewModel(repository, application) as T

            modelClass.isAssignableFrom(CloudFileListViewModel::class.java) ->
                CloudFileListViewModel(repository, application) as T

            modelClass.isAssignableFrom(CloudUploadViewModel::class.java) ->
                CloudUploadViewModel(repository, application) as T

            modelClass.isAssignableFrom(CloudTrashViewModel::class.java) ->
                CloudTrashViewModel(repository, application) as T

            modelClass.isAssignableFrom(CloudQrHistoryViewModel::class.java) ->
                CloudQrHistoryViewModel(repository, application) as T

            modelClass.isAssignableFrom(CloudQrTrashViewModel::class.java) ->
                CloudQrTrashViewModel(repository, application) as T

            modelClass.isAssignableFrom(CloudMaintenanceViewModel::class.java) ->
                CloudMaintenanceViewModel(application) as T

            modelClass.isAssignableFrom(CloudSessionsViewModel::class.java) ->
                CloudSessionsViewModel(application, repository) as T

            modelClass.isAssignableFrom(CloudGoogleAuthViewModel::class.java) ->
                CloudGoogleAuthViewModel(repository, application) as T

            modelClass.isAssignableFrom(AccountLinkGoogleViewModel::class.java) ->
                AccountLinkGoogleViewModel(repository, application) as T

            modelClass.isAssignableFrom(SessionRecoveryViewModel::class.java) ->
                SessionRecoveryViewModel(repository, application) as T

            modelClass.isAssignableFrom(ProfileAvatarViewModel::class.java) ->
                ProfileAvatarViewModel(repository, application) as T

            else ->
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.StorageUsage

data class HomeUiState(
    val userEmail: String? = null,
    val fileCount: Int = 0,
    val storageUsed: Long = 0L,
    /** Server-authoritative storage usage (plan + quota + used bytes). */
    val storageUsage: StorageUsage? = null,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val isLoggedOut: Boolean = false,
    val lastSyncTime: String? = null
)

class CloudHomeViewModel(
    private val repository: CloudRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        loadHome()
        refreshStorageUsage()
    }

    fun loadHome() {
        viewModelScope.launch {
            // Files/export groups are loaded by CloudFileListViewModel (embedded list);
            // here we only need the account email for the premium header.
            _state.value = _state.value.copy(
                isLoading = false,
                errorMessage = null,
                userEmail = repository.getCurrentUserEmail()
            )
        }
    }

    /**
     * Refreshes the usage from GET /api/storage/usage so the header reflects
     * backend state after uploads, deletes, trash purges and restores.
     */
    fun refreshStorageUsage() {
        viewModelScope.launch {
            repository.getStorageUsage().fold(
                onSuccess = { usage -> _state.value = _state.value.copy(storageUsage = usage) },
                onFailure = { _state.value = _state.value.copy(storageUsage = null) }
            )
        }
    }
}
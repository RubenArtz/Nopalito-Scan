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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nopalito.app.R
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.QrScan

data class CloudQrHistoryUiState(
    val scans: List<QrScan> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
)

/** Shows the QR/barcode scan history stored in the cloud. */
class CloudQrHistoryViewModel(
    private val repository: CloudRepository,
    private val application: Application
) : ViewModel() {

    private val _state = MutableStateFlow(CloudQrHistoryUiState())
    val state: StateFlow<CloudQrHistoryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, error = null) }
            repository.listCloudScans()
                .onSuccess { scans ->
                    _state.update {
                        it.copy(
                            isRefreshing = false,
                            isLoading = false,
                            scans = scans
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            isRefreshing = false,
                            isLoading = false,
                            error = CloudErrorPresenter.message(application, e, R.string.error_unknown)
                        )
                    }
                }
        }
    }

    fun delete(scanId: String) {
        viewModelScope.launch {
            repository.deleteCloudScan(scanId)
                .onSuccess { refresh() }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            error = CloudErrorPresenter.message(
                                application,
                                e,
                                R.string.error_unknown
                            )
                        )
                    }
                }
        }
    }

    // ====== Selection ======

    fun toggleSelection(id: String) = _state.update {
        it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id)
    }

    /** Enters selection mode selecting only [id]. */
    fun select(id: String) = _state.update { it.copy(selectedIds = setOf(id)) }

    fun selectAll() = _state.update { it.copy(selectedIds = it.scans.map { s -> s.id }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selectedIds = emptySet()) }

    /** Moves the selected scans to the cloud trash (soft delete, batch). */
    fun deleteSelected() {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id -> repository.deleteCloudScan(id) }
            clearSelection()
            refresh()
        }
    }
}

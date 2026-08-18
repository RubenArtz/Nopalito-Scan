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
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nopalito.app.R
import nopalito.app.ui.screens.cloud.data.ApiException
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository

data class SelectedFile(val name: String, val uri: Uri)

data class UploadUiState(
    val selectedFiles: List<SelectedFile> = emptyList(),
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val uploadSuccess: Boolean = false,
    val errorMessage: String? = null,
    /** True when the server rejected the upload with QUOTA_EXCEEDED. */
    val quotaExceeded: Boolean = false
)

class CloudUploadViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(UploadUiState())
    val state: StateFlow<UploadUiState> = _state.asStateFlow()

    fun addFiles(files: List<SelectedFile>) {
        _state.value = _state.value.copy(
            selectedFiles = _state.value.selectedFiles + files,
            errorMessage = null
        )
    }

    fun removeFile(uri: Uri) {
        _state.value = _state.value.copy(
            selectedFiles = _state.value.selectedFiles.filter { it.uri != uri }
        )
    }

    fun upload() {
        val files = _state.value.selectedFiles
        if (files.isEmpty()) return

        viewModelScope.launch {
            _state.value = _state.value.copy(
                isUploading = true,
                uploadProgress = 0f,
                errorMessage = null
            )

            var completed = 0
            for (file in files) {
                val result = repository.uploadFile(fileUri = file.uri)
                if (result.isFailure) {
                    val e = result.exceptionOrNull()
                    val quota = e is ApiException && e.isQuotaExceeded()
                    _state.value = _state.value.copy(
                        isUploading = false,
                        // A quota rejection opens the full-screen premium modal
                        // instead of the inline error card.
                        quotaExceeded = quota,
                        errorMessage = if (quota) null else CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_upload
                        )
                    )
                    return@launch
                }
                completed++
                _state.value = _state.value.copy(
                    uploadProgress = completed.toFloat() / files.size
                )
            }

            _state.value = _state.value.copy(
                isUploading = false,
                uploadProgress = 1f,
                uploadSuccess = true
            )
        }
    }

    fun dismissQuota() {
        _state.value = _state.value.copy(quotaExceeded = false)
    }

    fun resetState() {
        _state.value = UploadUiState()
    }
}


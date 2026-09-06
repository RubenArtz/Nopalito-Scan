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
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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

data class SelectedFile(val name: String, val uri: Uri)

private const val TAG = "CloudUpload"

data class UploadUiState(
    val selectedFiles: List<SelectedFile> = emptyList(),
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val uploadSuccess: Boolean = false,
    val errorMessage: String? = null,
    /** Files finished in the current batch (partial-success summary). */
    val uploadedCount: Int = 0,
    /** True when the server rejected the upload with QUOTA_EXCEEDED. */
    val quotaExceeded: Boolean = false
)

class CloudUploadViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(UploadUiState())
    val state: StateFlow<UploadUiState> = _state.asStateFlow()

    /** Target folder for plain uploads (null = root); set by the host screen. */
    private var targetFolderId: String? = null

    fun setTargetFolder(folderId: String?) {
        targetFolderId = folderId?.takeIf { it.isNotBlank() }
    }

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

    /**
     * Uploads the listed files one by one. Every SUCCESS is removed from the
     * list immediately (it can never be re-uploaded by accident); failures
     * STAY listed so a retry only sends what is left. The batch continues
     * after an individual failure; quota rejections stop everything and open
     * the premium modal.
     */
    fun upload() {
        val current = _state.value
        if (current.selectedFiles.isEmpty() || current.isUploading) return

        viewModelScope.launch {
            val batch = current.selectedFiles
            _state.value = _state.value.copy(
                isUploading = true,
                uploadProgress = 0f,
                errorMessage = null,
                uploadSuccess = false
            )

            var completed = 0
            for (file in batch) {
                try {
                    // Plain uploads land in the browsed folder (root when unset).
                    repository.uploadFile(fileUri = file.uri, parentId = targetFolderId).fold(
                        onSuccess = {
                            completed += 1
                            _state.value = _state.value.copy(
                                selectedFiles = _state.value.selectedFiles.filterNot { it.uri == file.uri },
                                uploadedCount = completed,
                                uploadProgress = completed.toFloat() / batch.size
                            )
                        },
                        onFailure = { e ->
                            Log.w(
                                TAG,
                                "Upload failed for \"${file.name}\": " +
                                        "${e.javaClass.simpleName}: ${e.message}"
                            )
                            if (e is ApiException && e.isQuotaExceeded()) {
                                _state.value = _state.value.copy(
                                    isUploading = false,
                                    quotaExceeded = true
                                )
                                return@launch
                            }
                            // Keep the failed file listed for a clean retry.
                            _state.value = _state.value.copy(
                                errorMessage = CloudErrorPresenter.message(
                                    application,
                                    e,
                                    R.string.cloud_error_upload
                                )
                            )
                        }
                    )
                } catch (e: CancellationException) {
                    throw e
                }
            }

            val remaining = _state.value.selectedFiles.size
            if (remaining == 0) {
                _state.value = _state.value.copy(
                    isUploading = false,
                    uploadProgress = 1f,
                    uploadSuccess = true
                )
            } else {
                // Partial success: leave the screen usable with only the
                // failed files; the message doubles as the retry hint.
                _state.value = _state.value.copy(
                    isUploading = false,
                    errorMessage = application.stringFor(
                        R.string.cloud_upload_partial_summary,
                        AppLocaleOverride.locale,
                        completed,
                        batch.size
                    )
                )
            }
        }
    }

    fun dismissQuota() {
        _state.value = _state.value.copy(quotaExceeded = false)
    }

    fun resetState() {
        _state.value = UploadUiState()
    }
}


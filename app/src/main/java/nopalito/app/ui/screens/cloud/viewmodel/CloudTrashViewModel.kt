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
import nopalito.app.ui.screens.cloud.data.BulkZipPhase
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.CloudFile
import java.io.File

data class TrashUiState(
    val files: List<CloudFile> = emptyList(),
    /** Ancestor path inside the trash; last item = the open trashed folder. */
    val folderStack: List<CloudFile> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val restoringId: String? = null,
    val deletingPermanentId: String? = null,
    // selection
    val selectedIds: Set<String> = emptySet(),
    // preview
    val previewFile: CloudFile? = null,
    val previewCacheFile: File? = null,
    val isDownloadingPreview: Boolean = false,
    // rename
    val renameDialogFile: CloudFile? = null,
    val renameName: String = "",
    val isRenaming: Boolean = false,
    // bulk ZIP export
    val zipPhase: BulkZipPhase? = null,
    val zipProgress: Float = 0f,
    // snackbar
    val snackbarMessage: String? = null
)

class CloudTrashViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    /**
     * Re-fetches the current trash level (roots, or the children of the
     * opened trashed folder). Skips when a request is already in flight.
     */
    fun refresh() {
        if (_state.value.isLoading) return
        viewModelScope.launch {
            _state.value =
                _state.value.copy(isLoading = true, isRefreshing = true, errorMessage = null)
            repository.listDeletedFiles(
                parentId = _state.value.folderStack.lastOrNull()?.id
            ).fold(
                onSuccess = { files ->
                    _state.value =
                        _state.value.copy(isLoading = false, isRefreshing = false, files = files)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_load_trash
                        )
                    )
                }
            )
        }
    }

    // ── Trash folder browsing ──

    fun openTrashFolder(folder: CloudFile) {
        if (folder.itemType != "folder") return
        _state.value = _state.value.copy(folderStack = _state.value.folderStack + folder)
        refresh()
    }

    /** Navigates back to a breadcrumb level (-1 = trash roots). */
    fun navigateTrashTo(index: Int) {
        val stack = if (index < 0) emptyList() else _state.value.folderStack.take(index + 1)
        if (stack.size == _state.value.folderStack.size) return
        _state.value = _state.value.copy(folderStack = stack)
        refresh()
    }

    /**
     * Restores the OPENED trashed folder with its whole content (recursive)
     * and goes back to the trash roots.
     */
    fun restoreWholeOpenFolder(onRestored: () -> Unit = {}) {
        val folder = _state.value.folderStack.lastOrNull() ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(restoringId = folder.id, errorMessage = null)
            repository.restoreFile(folder.id).fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        restoringId = null,
                        folderStack = emptyList()
                    )
                    onRestored()
                    refresh()
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        restoringId = null,
                        errorMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_restore
                        )
                    )
                }
            )
        }
    }

    fun restoreFile(fileId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(restoringId = fileId, errorMessage = null)
            repository.restoreFile(fileId).fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        restoringId = null,
                        files = _state.value.files.filter { it.id != fileId }
                    )
                },
                onFailure = { e ->
                    // The server rejects a restore that would exceed the quota
                    // (QUOTA_EXCEEDED_ON_RESTORE): show the specific message
                    // instead of a generic restore failure.
                    val quotaMessage = (e as? ApiException)?.isRestoreQuotaExceeded() == true
                    _state.value = _state.value.copy(
                        restoringId = null,
                        errorMessage = if (quotaMessage) {
                            application.stringFor(
                                R.string.cloud_error_restore_quota,
                                AppLocaleOverride.locale
                            )
                        } else {
                            CloudErrorPresenter.message(
                                application,
                                e,
                                R.string.cloud_error_restore
                            )
                        }
                    )
                }
            )
        }
    }

    fun permanentlyDelete(fileId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(deletingPermanentId = fileId, errorMessage = null)
            repository.permanentlyDeleteFile(fileId).fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        deletingPermanentId = null,
                        files = _state.value.files.filter { it.id != fileId }
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        deletingPermanentId = null,
                        errorMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_delete_permanent
                        )
                    )
                }
            )
        }
    }

    // â”€â”€ Preview â”€â”€

    fun previewFile(file: CloudFile) {
        if (_state.value.previewFile != null || file.itemType == "folder") return
        _state.value = _state.value.copy(
            previewFile = file,
            isDownloadingPreview = true,
            previewCacheFile = null
        )
        viewModelScope.launch {
            val updatedAt = file.updatedAt ?: file.createdAt
            val result = repository.downloadToCache(file.id, file.originalName, updatedAt)
            result.fold(
                onSuccess = { cacheFile ->
                    _state.value = _state.value.copy(
                        isDownloadingPreview = false,
                        previewCacheFile = cacheFile
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isDownloadingPreview = false,
                        snackbarMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_load_preview
                        )
                    )
                }
            )
        }
    }

    fun dismissPreview() {
        _state.value = _state.value.copy(
            previewFile = null,
            previewCacheFile = null,
            isDownloadingPreview = false
        )
    }

    // â”€â”€ Download â”€â”€

    fun downloadFile(file: CloudFile) {
        viewModelScope.launch {
            val result = repository.downloadFile(file.id, file.originalName)
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        snackbarMessage = application.stringFor(
                            R.string.cloud_downloaded_to,
                            AppLocaleOverride.locale,
                            file.originalName,
                            application.stringFor(
                                R.string.download_dirname,
                                AppLocaleOverride.locale
                            )
                        )
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        snackbarMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_download_error
                        )
                    )
                }
            )
        }
    }

    // â”€â”€ Rename â”€â”€

    fun startRename(file: CloudFile) {
        _state.value = _state.value.copy(
            renameDialogFile = file,
            renameName = file.originalName,
            isRenaming = false
        )
    }

    fun updateRenameName(name: String) {
        _state.value = _state.value.copy(renameName = name)
    }

    fun confirmRename() {
        val file = _state.value.renameDialogFile ?: return
        val newName = _state.value.renameName.trim()
        if (newName.isBlank()) return

        _state.value = _state.value.copy(isRenaming = true)
        viewModelScope.launch {
            val result = repository.updateFile(file.id, originalName = newName)
            result.fold(
                onSuccess = { updated ->
                    _state.value = _state.value.copy(
                        isRenaming = false,
                        renameDialogFile = null,
                        files = _state.value.files.map { if (it.id == updated.id) updated else it },
                        snackbarMessage = application.stringFor(
                            R.string.cloud_file_renamed,
                            AppLocaleOverride.locale
                        )
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isRenaming = false,
                        snackbarMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_rename_error
                        )
                    )
                }
            )
        }
    }

    fun cancelRename() {
        _state.value = _state.value.copy(
            renameDialogFile = null,
            renameName = "",
            isRenaming = false
        )
    }

    fun clearSnackbar() {
        _state.value = _state.value.copy(snackbarMessage = null)
    }

    /** Download file bytes to cache â€” used by composables for thumbnail rendering */
    suspend fun downloadForCache(file: CloudFile): Result<File> {
        val updatedAt = file.updatedAt ?: file.createdAt
        return repository.downloadToCache(file.id, file.originalName, updatedAt)
    }

    // â”€â”€ Selection â”€â”€

    fun toggleSelection(fileId: String) {
        val ids = _state.value.selectedIds.toMutableSet()
        if (ids.contains(fileId)) ids.remove(fileId) else ids.add(fileId)
        _state.value = _state.value.copy(selectedIds = ids)
    }

    fun selectAll() {
        val all = _state.value.files.map { it.id }.toSet()
        _state.value = _state.value.copy(
            selectedIds = if (_state.value.selectedIds.size == all.size) emptySet() else all
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedIds = emptySet())
    }

    /**
     * Downloads EVERYTHING currently selected as ONE ZIP archive (works for
     * trashed items too). Selection clears on success.
     */
    fun downloadSelectedAsZip() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty() || _state.value.zipPhase != null) return
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())

        _state.value = _state.value.copy(zipPhase = BulkZipPhase.PREPARING, zipProgress = 0f)
        viewModelScope.launch {
            repository.downloadBulkZip(
                ids = ids,
                fileName = "trash-$stamp.zip",
                onPhase = { phase -> _state.value = _state.value.copy(zipPhase = phase) },
                onProgress = { p -> _state.value = _state.value.copy(zipProgress = p) },
            ).fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        zipPhase = null,
                        selectedIds = emptySet(),
                        snackbarMessage = application.stringFor(
                            R.string.cloud_zip_saved,
                            AppLocaleOverride.locale
                        )
                    )
                },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.value = _state.value.copy(
                        zipPhase = null,
                        snackbarMessage = application.stringFor(
                            R.string.cloud_zip_error,
                            AppLocaleOverride.locale
                        )
                    )
                }
            )
        }
    }

    fun batchRestore() {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            // The server is the authority on storage: trashed bytes already
            // count against the quota, so restoring never needs free space.
            // Each file is restored one by one and the server decides;
            // files that fail stay in the list — nothing is removed that was
            // not actually restored.
            var restored = 0
            val failedIds = mutableSetOf<String>()
            for (id in ids) {
                repository.restoreFile(id).fold(
                    onSuccess = { restored++ },
                    onFailure = { failedIds += id }
                )
            }
            val restoredIds = ids - failedIds
            _state.value = _state.value.copy(
                selectedIds = emptySet(),
                files = _state.value.files.filter { it.id !in restoredIds },
                snackbarMessage = if (failedIds.isEmpty()) {
                    application.stringFor(
                        R.string.cloud_restored_n,
                        AppLocaleOverride.locale,
                        restored
                    )
                } else {
                    application.stringFor(
                        R.string.cloud_restored_partial,
                        AppLocaleOverride.locale,
                        restored,
                        ids.size
                    )
                }
            )
        }
    }

    fun batchPermanentDelete() {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var deleted = 0
            for (id in ids) {
                repository.permanentlyDeleteFile(id).onSuccess { deleted++ }
            }
            _state.value = _state.value.copy(
                selectedIds = emptySet(),
                files = _state.value.files.filter { it.id !in ids },
                snackbarMessage = application.stringFor(
                    R.string.cloud_deleted_n,
                    AppLocaleOverride.locale,
                    deleted
                )
            )
        }
    }
}

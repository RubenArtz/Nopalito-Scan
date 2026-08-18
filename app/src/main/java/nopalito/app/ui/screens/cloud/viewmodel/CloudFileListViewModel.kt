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
import android.content.Intent
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
import nopalito.app.ui.screens.cloud.model.CloudFile
import nopalito.app.ui.uriForFile
import java.io.File

data class FileListUiState(
    val files: List<CloudFile> = emptyList(),
    /** Multiple exports (folders), shown as a group in the list. */
    val exportGroups: List<CloudFile> = emptyList(),
    // folder detail
    val selectedExportGroup: CloudFile? = null,
    val exportChildren: List<CloudFile> = emptyList(),
    val isLoadingChildren: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val selectedCategory: String? = null,
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
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
    // download progress
    val downloadingId: String? = null,
    val downloadProgress: Float = 0f,
    // snackbar
    val snackbarMessage: String? = null,
    val snackbarActionLabel: String? = null,
    val snackbarAction: (() -> Unit)? = null
)

class CloudFileListViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(FileListUiState())
    val state: StateFlow<FileListUiState> = _state.asStateFlow()

    companion object {
        private const val PAGE_SIZE = 30
    }

    init {
        loadFiles()
    }

    fun loadFiles(category: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = true,
                errorMessage = null,
                selectedCategory = category,
                currentPage = 1,
                hasMore = true
            )
            val groups = repository.listExportGroups(limit = 100).getOrDefault(emptyList())
            val result = repository.listFiles(category = category, page = 1, limit = PAGE_SIZE)
            result.fold(
                onSuccess = { files ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        files = files,
                        exportGroups = groups,
                        currentPage = 1,
                        hasMore = files.size >= PAGE_SIZE
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        exportGroups = groups,
                        errorMessage = CloudErrorPresenter.message(application, e, R.string.cloud_error_load_files)
                    )
                }
            )
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoadingMore || !s.hasMore) return
        _state.value = s.copy(isLoadingMore = true)
        val nextPage = s.currentPage + 1
        viewModelScope.launch {
            val result = repository.listFiles(
                category = _state.value.selectedCategory,
                page = nextPage,
                limit = PAGE_SIZE
            )
            result.fold(
                onSuccess = { files ->
                    _state.value = _state.value.copy(
                        isLoadingMore = false,
                        files = _state.value.files + files,
                        currentPage = nextPage,
                        hasMore = files.size >= PAGE_SIZE
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoadingMore = false,
                        snackbarMessage = CloudErrorPresenter.message(application, e, R.string.cloud_error_load_more)
                    )
                }
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true)
            val groups = repository.listExportGroups(limit = 100).getOrDefault(emptyList())
            val result = repository.listFiles(
                category = _state.value.selectedCategory,
                page = 1,
                limit = PAGE_SIZE
            )
            result.fold(
                onSuccess = { files ->
                    _state.value = _state.value.copy(
                        isRefreshing = false,
                        files = files,
                        exportGroups = groups,
                        currentPage = 1,
                        hasMore = files.size >= PAGE_SIZE
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isRefreshing = false,
                        errorMessage = CloudErrorPresenter.message(application, e, R.string.cloud_error_load_files)
                    )
                }
            )
        }
    }

    // â”€â”€ Export groups (carpetas) â”€â”€

    fun openExportGroup(group: CloudFile) {
        val exportId = group.exportId
        if (exportId == null) {
            _state.value = _state.value.copy(
                selectedExportGroup = group,
                exportChildren = emptyList(),
                isLoadingChildren = false
            )
            return
        }
        _state.value = _state.value.copy(
            selectedExportGroup = group,
            exportChildren = emptyList(),
            isLoadingChildren = true
        )
        viewModelScope.launch {
            val result = repository.listExportChildren(exportId)
            result.fold(
                onSuccess = { children ->
                    _state.value = _state.value.copy(
                        isLoadingChildren = false,
                        exportChildren = children
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoadingChildren = false,
                        snackbarMessage = CloudErrorPresenter.message(application, e, R.string.cloud_error_load_folder)
                    )
                }
            )
        }
    }

    fun closeExportGroup() {
        _state.value = _state.value.copy(
            selectedExportGroup = null,
            exportChildren = emptyList(),
            isLoadingChildren = false
        )
    }

    /**
     * Deletes a complete multiple export: moves the folder row and all its
     * children to trash. Closes the detail view.
     */
    fun deleteExportGroup(group: CloudFile, onNavigateToTrash: () -> Unit = {}) {
        viewModelScope.launch {
            val children = _state.value.exportChildren
                .ifEmpty { repository.listExportChildren(group.exportId ?: group.id).getOrDefault(emptyList()) }

            for (child in children) {
                repository.deleteFile(child.id)
            }
            repository.deleteFile(group.id)

            _state.value = _state.value.copy(
                selectedExportGroup = null,
                exportChildren = emptyList(),
                isLoadingChildren = false,
                exportGroups = _state.value.exportGroups.filter { it.id != group.id },
                snackbarMessage = application.stringFor(R.string.cloud_folder_moved_to_trash, AppLocaleOverride.locale),
                snackbarActionLabel = application.stringFor(R.string.cloud_go_to_trash, AppLocaleOverride.locale),
                snackbarAction = onNavigateToTrash
            )
        }
    }

    /** Downloads the cover of a folder for the thumbnail. */
    suspend fun downloadExportCover(group: CloudFile): Result<File> {
        val coverId = group.coverFileId ?: return Result.failure(Exception("No cover"))
        val updatedAt = group.updatedAt ?: group.createdAt
        return repository.downloadToCache(coverId, group.originalName, updatedAt)
    }

    fun deleteFile(fileId: String, onNavigateToTrash: () -> Unit = {}) {
        val file = _state.value.files.find { it.id == fileId }
            ?: _state.value.exportChildren.find { it.id == fileId }
            ?: return
        _state.value = _state.value.copy(
            files = _state.value.files.filter { it.id != fileId },
            exportChildren = _state.value.exportChildren.filter { it.id != fileId },
            snackbarMessage = application.stringFor(R.string.cloud_file_moved_to_trash, AppLocaleOverride.locale),
            snackbarActionLabel = application.stringFor(R.string.cloud_go_to_trash, AppLocaleOverride.locale),
            snackbarAction = onNavigateToTrash
        )
        viewModelScope.launch {
            repository.deleteFile(fileId).fold(
                onSuccess = { /* optimista */ },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        files = _state.value.files + file,
                        exportChildren = _state.value.exportChildren + file,
                        snackbarMessage = CloudErrorPresenter.message(application, e, R.string.cloud_error_delete_file),
                        snackbarActionLabel = null,
                        snackbarAction = null
                    )
                }
            )
        }
    }

    // â”€â”€ Preview â”€â”€

    fun previewFile(file: CloudFile) {
        if (_state.value.previewFile != null) return
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
                        snackbarMessage = CloudErrorPresenter.message(application, e, R.string.cloud_error_load_preview)
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
        if (_state.value.downloadingId != null) return
        _state.value = _state.value.copy(
            downloadingId = file.id,
            downloadProgress = 0f
        )
        viewModelScope.launch {
            val result = repository.downloadFile(file.id, file.originalName) { progress ->
                if (_state.value.downloadingId == file.id) {
                    _state.value = _state.value.copy(downloadProgress = progress)
                }
            }
            _state.value = _state.value.copy(downloadingId = null, downloadProgress = 0f)
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        snackbarMessage = application.stringFor(
                            R.string.cloud_downloaded_to,
                            AppLocaleOverride.locale,
                            file.originalName,
                            application.stringFor(R.string.download_dirname, AppLocaleOverride.locale)
                        )
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        snackbarMessage = CloudErrorPresenter.message(application, e, R.string.cloud_download_error)
                    )
                }
            )
        }
    }

    /** Downloads the file to cache and opens the system share sheet. */
    fun shareFile(file: CloudFile) {
        viewModelScope.launch {
            val updatedAt = file.updatedAt ?: file.createdAt
            val result = repository.downloadToCache(file.id, file.originalName, updatedAt)
            result.fold(
                onSuccess = { cacheFile ->
                    val uri = uriForFile(application, cacheFile)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = file.mimeType ?: "application/octet-stream"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(Intent.EXTRA_STREAM, uri)
                    }
                    // Application context cannot start an activity without NEW_TASK.
                    val chooser = Intent.createChooser(
                        intent,
                        application.stringFor(R.string.share_document, AppLocaleOverride.locale)
                    )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching {
                        application.startActivity(chooser)
                    }.onFailure {
                        _state.value = _state.value.copy(
                            snackbarMessage = application.stringFor(
                                R.string.cloud_share_error,
                                AppLocaleOverride.locale
                            )
                        )
                    }
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        snackbarMessage = CloudErrorPresenter.message(application, e, R.string.cloud_download_error)
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
                        snackbarMessage = application.stringFor(R.string.cloud_file_renamed, AppLocaleOverride.locale)
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isRenaming = false,
                        snackbarMessage = CloudErrorPresenter.message(application, e, R.string.cloud_rename_error)
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
        _state.value = _state.value.copy(
            snackbarMessage = null,
            snackbarActionLabel = null,
            snackbarAction = null
        )
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
        _state.value =
            _state.value.copy(selectedIds = if (_state.value.selectedIds.size == all.size) emptySet() else all)
    }

    fun batchDelete(onNavigateToTrash: () -> Unit = {}) {
        val ids = _state.value.selectedIds
        val removed = _state.value.files.filter { it.id in ids }
        if (ids.isEmpty() || removed.isEmpty()) return
        _state.value = _state.value.copy(
            selectedIds = emptySet(),
            files = _state.value.files.filter { it.id !in ids },
            snackbarMessage = application.stringFor(
                R.string.cloud_files_moved_to_trash,
                AppLocaleOverride.locale,
                ids.size
            ),
            snackbarActionLabel = application.stringFor(R.string.cloud_go_to_trash, AppLocaleOverride.locale),
            snackbarAction = onNavigateToTrash
        )
        viewModelScope.launch {
            var deleted = 0
            for (id in ids) {
                repository.deleteFile(id).onSuccess { deleted++ }
            }
        }
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedIds = emptySet())
    }
}

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.BulkZipPhase
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.CloudFile
import nopalito.app.ui.uriForFile
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

data class FileListUiState(
    val files: List<CloudFile> = emptyList(),
    /** Multiple exports (folders), shown as a group in the list. */
    val exportGroups: List<CloudFile> = emptyList(),
    /** Folders of the current level (user + export), shown above the files. */
    val folders: List<CloudFile> = emptyList(),
    /** Ancestor path of the current level; the last item is the open folder. Empty = root. */
    val folderStack: List<CloudFile> = emptyList(),
    // create-folder dialog
    val showCreateFolder: Boolean = false,
    val newFolderName: String = "",
    val isCreatingFolder: Boolean = false,
    // move-to sheet
    val moveTargets: List<CloudFile> = emptyList(),
    val pickerStack: List<CloudFile> = emptyList(),
    val pickerFolders: List<CloudFile> = emptyList(),
    val isLoadingPicker: Boolean = false,
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
    // bulk ZIP export
    val zipPhase: BulkZipPhase? = null,
    val zipProgress: Float = 0f,
    // snackbar
    val snackbarMessage: String? = null,
    val snackbarActionLabel: String? = null,
    val snackbarAction: (() -> Unit)? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class CloudFileListViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(FileListUiState())
    val state: StateFlow<FileListUiState> = _state.asStateFlow()

    companion object {
        private const val PAGE_SIZE = 30

        /**
         * Debounce window for rapid category taps (requirement 8): while the
         * user is still tapping, earlier loads are cancelled before any HTTP
         * request leaves the device.
         */
        internal const val CATEGORY_DEBOUNCE_MS = 250L
    }

    /** One list (re)load request. [Refresh] must run immediately, no debounce. */
    private sealed interface ListLoad {
        data class FirstLoad(val category: String?) : ListLoad
        data object Refresh : ListLoad
    }

    /**
     * All list loads funnel through this channel. `flatMapLatest` cancels the
     * in-flight load the moment a newer request arrives (requirements 6/7),
     * so superseded responses can never overwrite fresh ones and duplicate
     * requests are impossible. A Channel (not a SharedFlow) guarantees that a
     * request posted before the collector starts is still delivered, and it
     * all runs in viewModelScope, so leaving the screen cancels everything.
     */
    private val loadRequests = Channel<ListLoad>(
        capacity = Channel.UNLIMITED,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /** Pagination job; cancelled whenever a new full load supersedes it. */
    private var loadMoreJob: Job? = null

    init {
        viewModelScope.launch {
            loadRequests.receiveAsFlow()
                // Collapse rapid taps: each request waits its window; a newer
                // one arriving meanwhile cancels the waiting one (debounce).
                .flatMapLatest { request ->
                    flow {
                        val waitMs = when (request) {
                            is ListLoad.FirstLoad -> CATEGORY_DEBOUNCE_MS
                            ListLoad.Refresh -> 0L
                        }
                        if (waitMs > 0) delay(waitMs.milliseconds)
                        emit(request)
                    }
                }
                .collect { request -> runListLoad(request) }
        }
        loadFiles() // Initial load.
    }

    fun loadFiles(category: String? = null) {
        loadRequests.trySend(ListLoad.FirstLoad(category))
    }

    private suspend fun runListLoad(request: ListLoad) {
        val category = when (request) {
            is ListLoad.FirstLoad -> request.category
            ListLoad.Refresh -> _state.value.selectedCategory
        }
        loadMoreJob?.cancel()
        val parentId = _state.value.folderStack.lastOrNull()?.id
        _state.value = _state.value.copy(
            isLoading = request is ListLoad.FirstLoad,
            isRefreshing = request is ListLoad.Refresh,
            errorMessage = null,
            selectedCategory = category,
            currentPage = 1,
            hasMore = true
        )
        try {
            // Level listing: files AND folders of the current level (root when
            // the stack is empty). Folders are rendered in their own section.
            val result = repository.listFolder(
                parentId = parentId,
                category = category,
                page = 1,
                limit = PAGE_SIZE
            )
            result.fold(
                onSuccess = { items ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        files = items.filter { it.itemType != "folder" },
                        folders = items.filter { it.itemType == "folder" },
                        exportGroups = emptyList(),
                        currentPage = 1,
                        hasMore = items.size >= PAGE_SIZE
                    )
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_load_files
                        )
                    )
                }
            )
        } catch (e: CancellationException) {
            // Superseded by a newer load or the ViewModel was cleared — never
            // surface as an error, and leave previous data untouched on screen.
            throw e
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoadingMore || !s.hasMore || s.isLoading) return
        _state.value = s.copy(isLoadingMore = true)
        val nextPage = s.currentPage + 1
        loadMoreJob = viewModelScope.launch {
            val result = repository.listFolder(
                parentId = s.folderStack.lastOrNull()?.id,
                category = s.selectedCategory,
                page = nextPage,
                limit = PAGE_SIZE
            )
            result.fold(
                onSuccess = { items ->
                    _state.value = _state.value.copy(
                        isLoadingMore = false,
                        files = _state.value.files + items.filter { it.itemType != "folder" },
                        folders = _state.value.folders + items.filter { it.itemType == "folder" },
                        currentPage = nextPage,
                        hasMore = items.size >= PAGE_SIZE
                    )
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _state.value = _state.value.copy(
                        isLoadingMore = false,
                        snackbarMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_load_more
                        )
                    )
                }
            )
        }
    }

    /**
     * Pull-to-refresh: re-runs the current category immediately. The previous
     * list stays visible while [FileListUiState.isRefreshing] is true — no
     * flicker back to an empty/spinner state (requirement 16).
     */
    fun refresh() {
        loadRequests.trySend(ListLoad.Refresh)
    }

    // â”€â”€ Export groups (carpetas) â”€â”€

    // ── Folder navigation ──

    /** Opens a folder: pushes it onto the ancestor stack and loads its level. */
    fun openFolder(folder: CloudFile) {
        if (folder.itemType != "folder") return
        _state.value = _state.value.copy(folderStack = _state.value.folderStack + folder)
        loadFiles()
    }

    /**
     * Navigates back to a breadcrumb level: [index] is the position inside the
     * ancestor stack; -1 navigates to the root.
     */
    fun navigateToLevel(index: Int) {
        val stack = if (index < 0) emptyList() else _state.value.folderStack.take(index + 1)
        if (stack.size == _state.value.folderStack.size) return
        _state.value = _state.value.copy(folderStack = stack)
        loadFiles()
    }

    /** True when a level load is already pending for the current stack. */

    /**
     * Deletes a complete multiple export with ONE recursive server-side call
     * (the backend trashes the whole subtree atomically). No client-side loop
     * anymore: a mid-loop failure can never orphan children.
     */
    fun deleteExportGroup(group: CloudFile, onNavigateToTrash: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteFile(group.id).fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        folders = _state.value.folders.filter { it.id != group.id },
                        exportGroups = _state.value.exportGroups.filter { it.id != group.id },
                        snackbarMessage = application.stringFor(
                            R.string.cloud_folder_moved_to_trash,
                            AppLocaleOverride.locale
                        ),
                        snackbarActionLabel = application.stringFor(
                            R.string.cloud_go_to_trash,
                            AppLocaleOverride.locale
                        ),
                        snackbarAction = onNavigateToTrash
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        snackbarMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_delete_file
                        )
                    )
                }
            )
        }
    }

    // ── Create folder ──

    fun showCreateFolderDialog() {
        _state.value = _state.value.copy(
            showCreateFolder = true,
            newFolderName = "",
            errorMessage = null
        )
    }

    fun updateNewFolderName(name: String) {
        _state.value = _state.value.copy(newFolderName = name)
    }

    fun dismissCreateFolder() {
        if (_state.value.isCreatingFolder) return
        _state.value = _state.value.copy(showCreateFolder = false, newFolderName = "")
    }

    /**
     * Creates a USER folder inside the currently open level (root when the
     * stack is empty). Optimistic: the new row is prepended to the folders of
     * the level and removed again if the backend rejects the name.
     */
    fun confirmCreateFolder() {
        val name = _state.value.newFolderName.trim()
        if (name.isBlank() || _state.value.isCreatingFolder) return
        val parentId = _state.value.folderStack.lastOrNull()?.id

        _state.value = _state.value.copy(isCreatingFolder = true)
        viewModelScope.launch {
            repository.createUserFolder(name, parentId).fold(
                onSuccess = { folder ->
                    _state.value = _state.value.copy(
                        isCreatingFolder = false,
                        showCreateFolder = false,
                        newFolderName = "",
                        folders = listOf(folder) + _state.value.folders,
                        snackbarMessage = application.stringFor(
                            R.string.cloud_folder_created,
                            AppLocaleOverride.locale
                        )
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isCreatingFolder = false,
                        snackbarMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_load_files
                        )
                    )
                }
            )
        }
    }

    // ── Move to… ──

    /** Opens the move sheet for one [item] with the picker at the root. */
    fun startMove(item: CloudFile) = startMoveFor(listOf(item))

    /**
     * Opens the move sheet for EVERYTHING currently selected (bulk action of
     * the selection bar).
     */
    fun startBulkMove() {
        val s = _state.value
        if (s.selectedIds.isEmpty()) return
        val items = (s.files + s.folders).filter { it.id in s.selectedIds }
        if (items.isEmpty()) return
        startMoveFor(items)
    }

    private fun startMoveFor(items: List<CloudFile>) {
        _state.value = _state.value.copy(
            moveTargets = items,
            pickerStack = emptyList(),
            pickerFolders = emptyList()
        )
        loadPickerLevel(null)
    }

    /** Quietly reloads the current level (move/delete side effects elsewhere). */
    private fun softRefresh() {
        loadRequests.trySend(ListLoad.Refresh)
    }

    private fun targetIds(): Set<String> = _state.value.moveTargets.map { it.id }.toSet()

    fun openPickerFolder(folder: CloudFile) {
        if (folder.id in targetIds()) return
        _state.value = _state.value.copy(pickerStack = _state.value.pickerStack + folder)
        loadPickerLevel(folder.id)
    }

    /** Navigates the picker back to a breadcrumb level (-1 = root). */
    fun navigatePickerTo(index: Int) {
        val stack = if (index < 0) emptyList() else _state.value.pickerStack.take(index + 1)
        if (stack.size == _state.value.pickerStack.size) return
        _state.value = _state.value.copy(pickerStack = stack)
        loadPickerLevel(stack.lastOrNull()?.id)
    }

    private fun loadPickerLevel(parentId: String?) {
        _state.value = _state.value.copy(isLoadingPicker = true)
        viewModelScope.launch {
            repository.listFolder(parentId = parentId, page = 1, limit = 100).fold(
                onSuccess = { items ->
                    val excluded = targetIds()
                    _state.value = _state.value.copy(
                        isLoadingPicker = false,
                        pickerFolders = items.filter { f ->
                            f.itemType == "folder" && f.id !in excluded
                        }
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoadingPicker = false,
                        snackbarMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_load_folder
                        )
                    )
                }
            )
        }
    }

    /**
     * Moves every target into the folder the picker is showing (root when its
     * stack is empty), one server call per item. Optimistic removal up front;
     * failures roll back into their original section; a quiet reload follows
     * so the open level always reflects reality (the destination may be the
     * level being browsed).
     */
    fun confirmMove() {
        val targets = _state.value.moveTargets
        if (targets.isEmpty() || _state.value.isLoadingPicker) return
        val destination = _state.value.pickerStack.lastOrNull()
        val destinationLabel = destination?.originalName
            ?: application.stringFor(R.string.cloud_root_crumb, AppLocaleOverride.locale)
        val ids = targets.map { it.id }.toSet()

        _state.value = _state.value.copy(
            files = _state.value.files.filter { it.id !in ids },
            folders = _state.value.folders.filter { it.id !in ids },
            moveTargets = emptyList(),
            pickerStack = emptyList(),
            pickerFolders = emptyList(),
            selectedIds = _state.value.selectedIds - ids
        )
        viewModelScope.launch {
            val failures = mutableListOf<CloudFile>()
            for (item in targets) {
                repository.moveItem(item.id, destination?.id).onFailure {
                    if (it is CancellationException) throw it
                    failures += item
                }
            }
            if (failures.isEmpty()) {
                _state.value = _state.value.copy(
                    snackbarMessage = application.stringFor(
                        R.string.cloud_moved_to_folder,
                        AppLocaleOverride.locale,
                        destinationLabel
                    )
                )
            } else {
                // Rollback the failures into the section they came from.
                _state.value = _state.value.copy(
                    files = _state.value.files + failures.filter { it.itemType != "folder" },
                    folders = _state.value.folders + failures.filter { it.itemType == "folder" },
                    snackbarMessage = application.stringFor(
                        R.string.cloud_error_move,
                        AppLocaleOverride.locale
                    )
                )
            }
            softRefresh()
        }
    }

    fun cancelMove() {
        if (_state.value.isLoadingPicker) return
        _state.value = _state.value.copy(
            moveTargets = emptyList(),
            pickerStack = emptyList(),
            pickerFolders = emptyList()
        )
    }

    fun deleteFile(fileId: String, onNavigateToTrash: () -> Unit = {}) {
        val file = _state.value.files.find { it.id == fileId }
            ?: _state.value.folders.find { it.id == fileId }
            ?: return
        val isFolder = file.itemType == "folder"
        _state.value = _state.value.copy(
            files = if (isFolder) _state.value.files else _state.value.files.filter { it.id != fileId },
            folders = _state.value.folders.filter { it.id != fileId },
            selectedIds = _state.value.selectedIds - fileId,
            snackbarMessage = application.stringFor(
                R.string.cloud_file_moved_to_trash,
                AppLocaleOverride.locale
            ),
            snackbarActionLabel = application.stringFor(
                R.string.cloud_go_to_trash,
                AppLocaleOverride.locale
            ),
            snackbarAction = onNavigateToTrash
        )
        viewModelScope.launch {
            repository.deleteFile(fileId).fold(
                onSuccess = {
                    // A trashed folder may have had visible effects on counts;
                    // keep the level authoritative.
                    softRefresh()
                },
                onFailure = { e ->
                    // Rollback into the section the item came from.
                    _state.value = if (isFolder) {
                        _state.value.copy(folders = listOf(file) + _state.value.folders)
                    } else {
                        _state.value.copy(files = listOf(file) + _state.value.files)
                    }.copy(
                        snackbarMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_error_delete_file
                        ),
                        snackbarActionLabel = null,
                        snackbarAction = null
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
        if (_state.value.downloadingId != null || file.itemType == "folder") return
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

    /** Downloads the file to cache and opens the system share sheet. */
    fun shareFile(file: CloudFile) {
        if (file.itemType == "folder") return
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
                    val replaceIn: (List<CloudFile>) -> List<CloudFile> = { list ->
                        list.map { if (it.id == updated.id) updated else it }
                    }
                    _state.value = _state.value.copy(
                        isRenaming = false,
                        renameDialogFile = null,
                        files = replaceIn(_state.value.files),
                        folders = replaceIn(_state.value.folders),
                        folderStack = replaceIn(_state.value.folderStack),
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
            snackbarActionLabel = application.stringFor(
                R.string.cloud_go_to_trash,
                AppLocaleOverride.locale
            ),
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

    /**
     * Downloads EVERYTHING currently selected as ONE ZIP archive. Phases:
     * PREPARING (server is building the archive) → DOWNLOADING (bytes
     * arriving, determinate progress). Selection clears on success.
     */
    fun downloadSelectedAsZip() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty() || _state.value.zipPhase != null) return
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        startZipExport(ids, "nopalito-cloud-$stamp.zip")
    }

    /** Downloads ONE folder as a ZIP archive named after it. */
    fun downloadFolderAsZip(folder: CloudFile) {
        if (folder.itemType != "folder" || _state.value.zipPhase != null) return
        startZipExport(listOf(folder.id), "${folder.originalName}.zip")
    }

    private fun startZipExport(ids: List<String>, fileName: String) {
        _state.value = _state.value.copy(zipPhase = BulkZipPhase.PREPARING, zipProgress = 0f)
        viewModelScope.launch {
            repository.downloadBulkZip(
                ids = ids,
                fileName = fileName,
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
                    if (e is CancellationException) throw e
                    _state.value = _state.value.copy(
                        zipPhase = null,
                        snackbarMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.cloud_zip_error
                        )
                    )
                }
            )
        }
    }
}

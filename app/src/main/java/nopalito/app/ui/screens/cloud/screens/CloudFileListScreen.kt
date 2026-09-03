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

package nopalito.app.ui.screens.cloud.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nopalito.app.R
import nopalito.app.ui.DocPagePreview
import nopalito.app.ui.DocumentPagesPreview
import nopalito.app.ui.components.AnimatedCountdownSnackbar
import nopalito.app.ui.components.FileTypeBadge
import nopalito.app.ui.decodeDocxMedia
import nopalito.app.ui.decodeDocxPages
import nopalito.app.ui.isEncryptedOle2
import nopalito.app.ui.isPdfEncrypted
import nopalito.app.ui.renderPdfPages
import nopalito.app.ui.screens.cloud.data.ApiException
import nopalito.app.ui.screens.cloud.data.CloudConversionRepository
import nopalito.app.ui.screens.cloud.model.CloudFile
import nopalito.app.ui.screens.cloud.viewmodel.CloudFileListViewModel
import java.io.File
import java.util.Locale

/**
 * Process-wide thumbnail cache: the same file scrolls in/out many times, so
 * decoded thumbnails survive recomposition (keyed by id + version).
 */
private val thumbnailCache = LruCache<String, Bitmap>(96)

@Composable
internal fun CloudFileListView(
    viewModel: CloudFileListViewModel,
    onUpload: (parentFolderId: String?, parentFolderName: String?) -> Unit,
    onNavigateToTrash: () -> Unit = {},
    onRefreshStorageUsage: () -> Unit = {},
    topBar: @Composable (inSelection: Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Unified back priority: close preview → clear selection → climb one
    // folder level. Only when none applies does back reach CloudHost (Home).
    BackHandler(enabled = state.previewFile != null || state.selectedIds.isNotEmpty() || state.folderStack.isNotEmpty()) {
        when {
            state.previewFile != null -> viewModel.dismissPreview()
            state.selectedIds.isNotEmpty() -> viewModel.clearSelection()
            state.folderStack.isNotEmpty() -> viewModel.navigateToLevel(state.folderStack.size - 2)
        }
    }

    // Full-screen preview overlay
    if (state.previewFile != null) {
        FullScreenPreview(
            file = state.previewFile!!,
            cachedFile = state.previewCacheFile,
            isDownloading = state.isDownloadingPreview,
            onDismiss = { viewModel.dismissPreview() },
            onDownload = { viewModel.downloadFile(state.previewFile!!) }
        )
        return
    }

    val inSelection = state.selectedIds.isNotEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> AnimatedCountdownSnackbar(data) } },
        topBar = { topBar(inSelection) },
        floatingActionButton = {
            if (!inSelection) {
                // Uploads land in the browsed folder; at root they go to root.
                val current = state.folderStack.lastOrNull()
                ExtendedFloatingActionButton(
                    onClick = { onUpload(current?.id, current?.originalName) },
                    icon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                    text = { Text(stringResource(R.string.cloud_upload)) }
                )
            }
        },
        bottomBar = {
            if (inSelection) {
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.startBulkMove() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.cloud_move_to), maxLines = 1)
                        }
                        Button(
                            onClick = { viewModel.downloadSelectedAsZip() },
                            enabled = state.zipPhase == null,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Archive,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("ZIP", maxLines = 1)
                        }
                        Button(
                            onClick = { viewModel.batchDelete(onNavigateToTrash) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.cloud_delete_n, state.selectedIds.size),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = {
                viewModel.refresh()
                onRefreshStorageUsage()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(Modifier.fillMaxSize()) {
                FolderBreadcrumbs(
                    path = state.folderStack,
                    onNavigate = { viewModel.navigateToLevel(it) },
                    onNewFolder = { viewModel.showCreateFolderDialog() }
                )
                Box(Modifier.weight(1f)) {
                    // The list has content if there are loose files OR folders.
                    val hasContent =
                        state.files.isNotEmpty() || state.folders.isNotEmpty() ||
                                state.exportGroups.isNotEmpty()
                    when {
                        state.isLoading && !hasContent -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }

                        state.errorMessage != null && !hasContent -> {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.ErrorOutline, null, Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    state.errorMessage ?: stringResource(R.string.error_occurred),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { viewModel.refresh() }) {
                                    Text(stringResource(R.string.cloud_retry))
                                }
                            }
                        }

                        state.files.isEmpty() && state.folders.isEmpty() &&
                                state.exportGroups.isEmpty() -> {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.CloudOff, null, Modifier.size(80.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.cloud_no_files),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    stringResource(R.string.cloud_empty_files),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }

                        else -> {
                            FileList(
                                files = state.files,
                                folders = state.folders,
                                hasMore = state.hasMore,
                                isLoadingMore = state.isLoadingMore,
                                selectedIds = state.selectedIds,
                                selectionMode = inSelection,
                                downloadingId = state.downloadingId,
                                downloadProgress = state.downloadProgress,
                                downloadForCache = viewModel::downloadForCache,
                                onFileClick = { viewModel.previewFile(it) },
                                onFolderClick = { viewModel.openFolder(it) },
                                onDownloadZip = { viewModel.downloadFolderAsZip(it) },
                                onLoadMore = { viewModel.loadMore() },
                                onToggleSelection = { viewModel.toggleSelection(it) },
                                onDownload = { viewModel.downloadFile(it) },
                                onShare = { viewModel.shareFile(it) },
                                onRename = { viewModel.startRename(it) },
                                onMove = { viewModel.startMove(it) },
                                onDelete = { id -> viewModel.deleteFile(id, onNavigateToTrash) }
                            )
                        }
                    }

                    if (state.isLoading && hasContent) {
                        LinearProgressIndicator(
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }

    // Rename Dialog
    if (state.renameDialogFile != null) {
        RenameDialog(
            currentName = state.renameName,
            isSaving = state.isRenaming,
            onNameChange = { viewModel.updateRenameName(it) },
            onConfirm = { viewModel.confirmRename() },
            onDismiss = { viewModel.cancelRename() }
        )
    }

    // Bulk ZIP export overlay: compression → download animations.
    state.zipPhase?.let { phase ->
        ZipExportOverlay(phase = phase, progress = state.zipProgress)
    }

    // Create-folder dialog
    if (state.showCreateFolder) {
        CreateFolderDialog(
            name = state.newFolderName,
            isCreating = state.isCreatingFolder,
            onNameChange = { viewModel.updateNewFolderName(it) },
            onConfirm = { viewModel.confirmCreateFolder() },
            onDismiss = { viewModel.dismissCreateFolder() }
        )
    }

    // Move-to bottom sheet (single item from the row menu, or the whole
    // selection from the bulk bar).
    if (state.moveTargets.isNotEmpty()) {
        MoveToSheet(
            items = state.moveTargets,
            pickerStack = state.pickerStack,
            pickerFolders = state.pickerFolders,
            isLoading = state.isLoadingPicker,
            onNavigatePicker = { viewModel.navigatePickerTo(it) },
            onOpenPickerFolder = { viewModel.openPickerFolder(it) },
            onConfirm = { viewModel.confirmMove() },
            onDismiss = { viewModel.cancelMove() }
        )
    }

    // Snackbar with optional action (undo). Indefinite: the custom host
    // composable (AnimatedCountdownSnackbar) drives the 10 s countdown.
    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let { msg ->
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = state.snackbarActionLabel,
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                state.snackbarAction?.invoke()
            }
            viewModel.clearSnackbar()
        }
    }
}

// ─────────────────────────────────────────────
// FILE LIST
// ─────────────────────────────────────────────

@Composable
internal fun FileList(
    files: List<CloudFile>,
    folders: List<CloudFile>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    selectedIds: Set<String>,
    selectionMode: Boolean,
    downloadingId: String? = null,
    downloadProgress: Float = 0f,
    downloadForCache: suspend (CloudFile) -> Result<File>,
    onFileClick: (CloudFile) -> Unit,
    onFolderClick: (CloudFile) -> Unit,
    onDownloadZip: (CloudFile) -> Unit,
    onLoadMore: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onDownload: (CloudFile) -> Unit,
    onShare: (CloudFile) -> Unit,
    onRename: (CloudFile) -> Unit,
    onMove: (CloudFile) -> Unit,
    onDelete: (String) -> Unit
) {
    val listState = rememberLazyListState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            lastVisible >= total - 6 && hasMore && !isLoadingMore
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) onLoadMore() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Folders of the current level (user + export), always above the files.
        if (folders.isNotEmpty()) {
            item(key = "header-folders") {
                Text(
                    text = stringResource(R.string.cloud_folders),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
            items(folders, key = { "folder-${it.id}" }) { folder ->
                FolderRow(
                    folder = folder,
                    isSelected = folder.id in selectedIds,
                    selectionMode = selectionMode,
                    onClick = { onFolderClick(folder) },
                    onToggleSelection = { onToggleSelection(folder.id) },
                    onRename = { onRename(folder) },
                    onMove = { onMove(folder) },
                    onDownloadZip = { onDownloadZip(folder) },
                    onDelete = { onDelete(folder.id) }
                )
            }
            item(key = "divider-folders") {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }
        }

        items(files, key = { it.id }) { file ->
            FileListItem(
                file = file,
                isSelected = file.id in selectedIds,
                selectionMode = selectionMode,
                downloadProgress = if (file.id == downloadingId) downloadProgress else null,
                downloadForCache = downloadForCache,
                onClick = { onFileClick(file) },
                onToggleSelection = { onToggleSelection(file.id) },
                onDownload = { onDownload(file) },
                onShare = { onShare(file) },
                onRename = { onRename(file) },
                onMove = { onMove(file) },
                onDelete = { onDelete(file.id) }
            )
        }

        if (isLoadingMore) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp), contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// FOLDER ROW — user or export folder of the current level
// ─────────────────────────────────────────────

@Composable
private fun FolderRow(
    folder: CloudFile,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDownloadZip: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onToggleSelection,
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        // Compact metrics matching FileListItem (44dp thumb, 36dp menu) so
        // folder rows and file rows share height and the ⋮ column aligns.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.size(32.dp)
                )
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Always the folder glyph: covers made folders look like
                // loose images (user feedback 2026-08-25).
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = folder.originalName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                when {
                    folder.origin == "export" -> Text(
                        text = stringResource(
                            R.string.cloud_folder_info,
                            folder.liveItems ?: folder.itemCount ?: 0,
                            folder.outputFormat ?: ""
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Live child count (authoritative); the static item_count
                    // is only a fallback for old payloads. Hidden while empty.
                    (folder.liveItems ?: folder.itemCount ?: 0) > 0 -> Text(
                        text = stringResource(
                            R.string.cloud_folder_items,
                            folder.liveItems ?: folder.itemCount ?: 0
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.cloud_more_actions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cloud_folder_download_zip)) },
                    leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDownloadZip()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cloud_rename)) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cloud_move_to)) },
                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onMove()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cloud_move_to_trash_action)) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        confirmDelete = true
                    }
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.cloud_move_to_trash)) },
            text = {
                Text(stringResource(R.string.cloud_move_to_trash_confirm, folder.originalName))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
// ─────────────────────────────────────────────
// FILE LIST ITEM — row with actions visible
// ─────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(
    file: CloudFile,
    isSelected: Boolean,
    selectionMode: Boolean,
    downloadProgress: Float? = null,
    downloadForCache: suspend (CloudFile) -> Result<File>,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // Thumbnail bitmap (memory-cached across scrolls and recompositions).
    val cacheKey = remember(file.id, file.updatedAt, file.createdAt) {
        "${file.id}|${file.updatedAt ?: file.createdAt}"
    }
    var thumb by remember { mutableStateOf(thumbnailCache.get(cacheKey)) }
    var loadingThumb by remember { mutableStateOf(thumb == null) }

    val isDocx = file.originalName.endsWith(".docx") || file.originalName.endsWith(".doc")
    val canThumb = file.mimeType?.startsWith("image/") == true ||
            file.mimeType == "application/pdf" ||
            file.originalName.endsWith(".pdf") ||
            isDocx

    LaunchedEffect(file.id) {
        if (!canThumb) {
            loadingThumb = false
            return@LaunchedEffect
        }
        thumbnailCache.get(cacheKey)?.let { cached ->
            thumb = cached
            loadingThumb = false
            return@LaunchedEffect
        }
        loadingThumb = true
        val decoded = withContext(Dispatchers.IO) { decodeThumb(file, isDocx, downloadForCache) }
        if (decoded != null) {
            thumbnailCache.put(cacheKey, decoded)
            thumb = decoded
        }
        loadingThumb = false
    }

    // Fallback icon
    val fallbackIcon = when {
        file.originalName.endsWith(".pdf") -> Icons.Default.PictureAsPdf
        file.originalName.endsWith(".png") || file.originalName.endsWith(".jpg") ||
                file.originalName.endsWith(".jpeg") || file.originalName.endsWith(".webp") ||
                file.originalName.endsWith(".bmp") -> Icons.Default.Image

        file.originalName.endsWith(".doc") || file.originalName.endsWith(".docx") ->
            Icons.Default.Description

        file.originalName.endsWith(".xls") || file.originalName.endsWith(".xlsx") ->
            Icons.Default.TableChart

        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelection() else onClick() },
                    onLongClick = { onToggleSelection() },
                )
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surface
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.size(32.dp)
                )
            }

            // Thumbnail
            Box(
                Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        loadingThumb -> CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )

                        thumb != null -> Image(
                            bitmap = thumb!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )

                        else -> Icon(
                            fallbackIcon, null, Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                FileTypeBadge(
                    fileName = file.originalName,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

            Spacer(Modifier.width(12.dp))

            // File info
            Column(Modifier.weight(1f)) {
                Text(
                    file.originalName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.mimeType?.substringBefore("/")?.replaceFirstChar { it.uppercase() }
                            ?: file.originalName.substringAfterLast('.', "").uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (file.size != null) {
                        Text(
                            " · ${
                                formatCloudFileSize(
                                    file.size,
                                    stringResource(R.string.cloud_size_unknown)
                                )
                            }",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!selectionMode) {
                // Single overflow menu keeps the row compact; all actions are
                // one tap away instead of four always-visible buttons.
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.cloud_more_actions),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cloud_rename)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuOpen = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cloud_move_to)) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            onClick = { menuOpen = false; onMove() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cloud_download)) },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = { menuOpen = false; onDownload() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share)) },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = { menuOpen = false; onShare() },
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete, null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { menuOpen = false; showDeleteConfirm = true },
                        )
                    }
                }
            }
        }

        if (downloadProgress != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.cloud_move_to_trash)) },
            text = {
                Text(
                    stringResource(
                        R.string.cloud_move_to_trash_confirm,
                        file.originalName
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text(
                        stringResource(R.string.cloud_move_to_trash_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/** Decodes a small thumbnail for [file] (image/pdf/docx) from the local cache. */
private suspend fun decodeThumb(
    file: CloudFile,
    isDocx: Boolean,
    downloadForCache: suspend (CloudFile) -> Result<File>,
): Bitmap? = try {
    val cached = downloadForCache(file).getOrNull() ?: return null
    when {
        file.mimeType?.startsWith("image/") == true -> {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(cached.absolutePath, opts)
        }

        file.mimeType == "application/pdf" || file.originalName.endsWith(".pdf") -> {
            val pfd = ParcelFileDescriptor.open(
                cached,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val w = 120
                val h = (page.height.toFloat() / page.width.toFloat() * w).toInt()
                val bmp = createBitmap(w, h)
                bmp.eraseColor(android.graphics.Color.WHITE)
                val mtx = android.graphics.Matrix().apply {
                    postScale(
                        w.toFloat() / page.width,
                        h.toFloat() / page.height
                    )
                }
                page.render(bmp, null, mtx, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close(); renderer.close(); pfd.close()
                bmp
            } else {
                renderer.close(); pfd.close(); null
            }
        }

        isDocx -> decodeDocxMedia(cached, maxDim = 256).firstOrNull()

        else -> null
    }
} catch (_: Exception) {
    null
}

// ─────────────────────────────────────────────
// FULL-SCREEN PREVIEW
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenPreview(
    file: CloudFile,
    cachedFile: File?,
    isDownloading: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    // Intercept system back gesture/button to dismiss preview instead of exiting cloud
    BackHandler(enabled = true) { onDismiss() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.originalName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.close)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onDownload) {
                        Icon(
                            Icons.Default.Download,
                            stringResource(R.string.cloud_download)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding), contentAlignment = Alignment.Center
        ) {
            when {
                isDownloading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.cloud_downloading))
                }

                cachedFile != null && isImageMimeType(file.mimeType) -> {
                    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(cachedFile) {
                        bitmap = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(
                                cachedFile.absolutePath,
                                BitmapFactory.Options().apply { inScaled = false })
                        }
                    }
                    bitmap?.let { ZoomableImage(bmp = it) }
                        ?: Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                }

                cachedFile != null && (file.mimeType == "application/pdf" || file.originalName.endsWith(
                    ".pdf"
                )) -> {
                    var pdfPages by remember { mutableStateOf<List<DocPagePreview>?>(null) }
                    var pdfEncrypted by remember { mutableStateOf(false) }
                    LaunchedEffect(cachedFile) {
                        pdfEncrypted = withContext(Dispatchers.IO) { isPdfEncrypted(cachedFile) }
                        if (!pdfEncrypted) {
                            pdfPages = withContext(Dispatchers.IO) { renderPdfPages(cachedFile) }
                        }
                    }
                    val pages = pdfPages
                    if (pdfEncrypted) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                null,
                                Modifier.size(96.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.cloud_document_encrypted))
                        }
                    } else if (pages.isNullOrEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                Icons.Default.PictureAsPdf,
                                null,
                                Modifier.size(96.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.cloud_pdf_render_error))
                        }
                    } else {
                        DocumentPagesPreview(pages)
                    }
                }

                cachedFile != null && (file.originalName.endsWith(".docx") || file.originalName.endsWith(
                    ".doc"
                )) -> {
                    var docxPages by remember { mutableStateOf<List<DocPagePreview>?>(null) }
                    var isDocxLoading by remember { mutableStateOf(true) }
                    var docxEncrypted by remember { mutableStateOf(false) }
                    val context = LocalContext.current
                    LaunchedEffect(cachedFile) {
                        docxEncrypted = withContext(Dispatchers.IO) { isEncryptedOle2(cachedFile) }
                        val local = if (!docxEncrypted) {
                            withContext(Dispatchers.IO) {
                                runCatching { decodeDocxPages(cachedFile, 1400) }.getOrNull()
                            }
                        } else null
                        // The app's own docx always carries page images inside
                        // word/media, which decodeDocxPages turns into pages.
                        // Only then show them (fast, offline). Third-party docx
                        // without per-page images fall back to the ephemeral
                        // backend conversion so the document still renders page
                        // by page. Password-protected files (OLE2 container or
                        // backend DOCUMENT_ENCRYPTED) get a clear message.
                        if (!local.isNullOrEmpty() && local.any { it.image != null }) {
                            docxPages = local
                        } else {
                            val result = withContext(Dispatchers.IO) {
                                CloudConversionRepository(context)
                                    .previewToPdf(cachedFile, file.originalName)
                            }
                            if (result.isFailure && (result.exceptionOrNull() as? ApiException)?.code == "DOCUMENT_ENCRYPTED") {
                                docxEncrypted = true
                            } else {
                                val pdf = result.getOrNull()
                                val pages = pdf?.let {
                                    try {
                                        withContext(Dispatchers.IO) { renderPdfPages(it) }
                                    } finally {
                                        it.delete()
                                    }
                                }.orEmpty()
                                docxPages = pages
                            }
                        }
                        isDocxLoading = false
                    }
                    val pages = docxPages
                    when {
                        isDocxLoading -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }

                        docxEncrypted -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock, null, Modifier.size(96.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.cloud_document_encrypted))
                        }

                        pages.isNullOrEmpty() -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Description, null, Modifier.size(96.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.cloud_pdf_render_error))
                        }

                        else -> DocumentPagesPreview(pages)
                    }
                }

                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(96.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.cloud_no_preview),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.cloud_download_to_view))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// ZOOMABLE IMAGE
// ─────────────────────────────────────────────

@Composable
private fun ZoomableImage(bmp: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = stringResource(R.string.cloud_preview),
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                    offsetX += pan.x; offsetY += pan.y
                }
            },
        contentScale = ContentScale.Fit
    )
}

// ─────────────────────────────────────────────
// RENAME DIALOG
// ─────────────────────────────────────────────

@Composable
private fun RenameDialog(
    currentName: String, isSaving: Boolean,
    onNameChange: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cloud_rename_file)) },
        text = {
            OutlinedTextField(
                value = currentName, onValueChange = onNameChange,
                label = { Text(stringResource(R.string.cloud_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = !isSaving
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = currentName.isNotBlank() && !isSaving) {
                if (isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving
            ) { Text(stringResource(R.string.cancel)) }
        }
    )
}

// ─────────────────────────────────────────────
// HELPERS
// ─────────────────────────────────────────────

// ─────────────────────────────────────────────
// FOLDER BREADCRUMBS + CREATE DIALOG + MOVE SHEET
// ─────────────────────────────────────────────

/** Breadcrumb path of the open folder with a "new folder" shortcut. */
@Composable
private fun FolderBreadcrumbs(
    path: List<CloudFile>,
    onNavigate: (Int) -> Unit,
    onNewFolder: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BreadcrumbCrumb(
            label = stringResource(R.string.cloud_root_crumb),
            highlighted = path.isEmpty(),
            onClick = { onNavigate(-1) }
        )
        path.forEachIndexed { index, folder ->
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            BreadcrumbCrumb(
                label = folder.originalName,
                highlighted = index == path.lastIndex,
                onClick = {
                    if (index != path.lastIndex) onNavigate(index)
                }
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onNewFolder) {
            Icon(
                Icons.Default.CreateNewFolder,
                contentDescription = stringResource(R.string.cloud_new_folder),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun BreadcrumbCrumb(
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
        color = if (highlighted) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** Dialog that creates a USER folder inside the currently open level. */
@Composable
private fun CreateFolderDialog(
    name: String,
    isCreating: Boolean,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cloud_create_folder_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                enabled = !isCreating,
                label = { Text(stringResource(R.string.cloud_folder_name_hint)) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isCreating && name.isNotBlank()
            ) {
                Text(stringResource(R.string.accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * "Move to…" bottom sheet: a folder picker navigated level by level. The
 * button moves the item into the level currently shown (root when the picker
 * stack is empty).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveToSheet(
    items: List<CloudFile>,
    pickerStack: List<CloudFile>,
    pickerFolders: List<CloudFile>,
    isLoading: Boolean,
    onNavigatePicker: (Int) -> Unit,
    onOpenPickerFolder: (CloudFile) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = if (items.size == 1) {
                    items.first().originalName
                } else {
                    stringResource(R.string.cloud_n_selected, items.size)
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            // Picker breadcrumbs (Root / A / B).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BreadcrumbCrumb(
                    label = stringResource(R.string.cloud_root_crumb),
                    highlighted = pickerStack.isEmpty(),
                    onClick = { onNavigatePicker(-1) }
                )
                pickerStack.forEachIndexed { index, folder ->
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    BreadcrumbCrumb(
                        label = folder.originalName,
                        highlighted = index == pickerStack.lastIndex,
                        onClick = {
                            if (index != pickerStack.lastIndex) onNavigatePicker(index)
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Explicit destination: no ambiguity about where "Move here" lands.
            val destinationName = pickerStack.lastOrNull()?.originalName
                ?: stringResource(R.string.cloud_root_crumb)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.cloud_move_selected_dest, destinationName),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(8.dp))

            if (isLoading) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Text(
                    text = stringResource(R.string.cloud_folder_items, pickerFolders.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    items(pickerFolders, key = { it.id }) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPickerFolder(folder) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = folder.originalName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm, enabled = !isLoading) {
                    Text(stringResource(R.string.cloud_move_here))
                }
            }
        }
    }
}

private fun isImageMimeType(mimeType: String?): Boolean =
    mimeType != null && mimeType.startsWith("image/") && !mimeType.startsWith("image/svg")

fun formatCloudFileSize(bytes: Long?, unknownLabel: String = "Unknown"): String {
    if (bytes == null) return unknownLabel
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 ->
            "${String.format(Locale.US, "%.1f", bytes.toDouble() / (1024 * 1024))} MB"

        else ->
            "${String.format(Locale.US, "%.2f", bytes.toDouble() / (1024 * 1024 * 1024))} GB"
    }
}

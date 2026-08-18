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
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
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
import nopalito.app.ui.*
import nopalito.app.ui.components.AnimatedCountdownSnackbar
import nopalito.app.ui.components.FileTypeBadge
import nopalito.app.ui.screens.cloud.data.ApiException
import nopalito.app.ui.screens.cloud.data.CloudConversionRepository
import nopalito.app.ui.screens.cloud.model.CloudFile
import nopalito.app.ui.screens.cloud.viewmodel.CloudFileListViewModel
import java.io.File
import java.util.*

/**
 * Process-wide thumbnail cache: the same file scrolls in/out many times, so
 * decoded thumbnails survive recomposition (keyed by id + version).
 */
private val thumbnailCache = LruCache<String, Bitmap>(96)

@Composable
internal fun CloudFileListView(
    viewModel: CloudFileListViewModel,
    onUpload: () -> Unit,
    onNavigateToTrash: () -> Unit = {},
    onRefreshStorageUsage: () -> Unit = {},
    topBar: @Composable (inSelection: Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

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

    // Full-screen folder detail (export group)
    val selectedGroup = state.selectedExportGroup
    if (selectedGroup != null) {
        ExportFolderDetail(
            group = selectedGroup,
            children = state.exportChildren,
            isLoading = state.isLoadingChildren,
            downloadingId = state.downloadingId,
            downloadProgress = state.downloadProgress,
            downloadExportCover = viewModel::downloadExportCover,
            downloadForCache = viewModel::downloadForCache,
            onChildClick = { viewModel.previewFile(it) },
            onDownload = { viewModel.downloadFile(it) },
            onDelete = { viewModel.deleteFile(it.id, onNavigateToTrash) },
            onBack = { viewModel.closeExportGroup() },
            onDeleteGroup = { viewModel.deleteExportGroup(selectedGroup, onNavigateToTrash) },
            snackbarMessage = state.snackbarMessage,
            snackbarActionLabel = state.snackbarActionLabel,
            snackbarAction = state.snackbarAction,
            onClearSnackbar = { viewModel.clearSnackbar() },
        )
        return
    }

    val inSelection = state.selectedIds.isNotEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> AnimatedCountdownSnackbar(data) } },
        topBar = { topBar(inSelection) },
        floatingActionButton = {
            if (!inSelection) {
                ExtendedFloatingActionButton(
                    onClick = onUpload,
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
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = { viewModel.batchDelete(onNavigateToTrash) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cloud_delete_n, state.selectedIds.size))
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
            // The list has content if there are loose files OR exports (folders).
            val hasContent = state.files.isNotEmpty() || state.exportGroups.isNotEmpty()
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
                        Button(onClick = { viewModel.refresh() }) { Text(stringResource(R.string.cloud_retry)) }
                    }
                }

                state.files.isEmpty() && state.exportGroups.isEmpty() -> {
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
                            stringResource(R.string.cloud_no_files), style = MaterialTheme.typography.titleLarge,
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
                        exportGroups = state.exportGroups,
                        hasMore = state.hasMore,
                        isLoadingMore = state.isLoadingMore,
                        selectedIds = state.selectedIds,
                        selectionMode = inSelection,
                        downloadingId = state.downloadingId,
                        downloadProgress = state.downloadProgress,
                        downloadForCache = viewModel::downloadForCache,
                        downloadExportCover = viewModel::downloadExportCover,
                        onFileClick = { viewModel.previewFile(it) },
                        onExportGroupClick = { viewModel.openExportGroup(it) },
                        onLoadMore = { viewModel.loadMore() },
                        onToggleSelection = { viewModel.toggleSelection(it) },
                        onDownload = { viewModel.downloadFile(it) },
                        onShare = { viewModel.shareFile(it) },
                        onRename = { viewModel.startRename(it) },
                        onDelete = { viewModel.deleteFile(it, onNavigateToTrash) }
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
    exportGroups: List<CloudFile>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    selectedIds: Set<String>,
    selectionMode: Boolean,
    downloadingId: String? = null,
    downloadProgress: Float = 0f,
    downloadForCache: suspend (CloudFile) -> Result<File>,
    downloadExportCover: suspend (CloudFile) -> Result<File>,
    onFileClick: (CloudFile) -> Unit,
    onExportGroupClick: (CloudFile) -> Unit,
    onLoadMore: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onDownload: (CloudFile) -> Unit,
    onShare: (CloudFile) -> Unit,
    onRename: (CloudFile) -> Unit,
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
        // Multi-file exports: folder (group) section
        if (exportGroups.isNotEmpty()) {
            item(key = "header-exports") {
                Text(
                    text = stringResource(R.string.cloud_exports),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
            items(exportGroups, key = { "group-${it.id}" }) { group ->
                ExportFolderCard(
                    group = group,
                    downloadExportCover = downloadExportCover,
                    onClick = { onExportGroupClick(group) }
                )
            }
            item(key = "divider-exports") {
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
// EXPORT FOLDER CARD — multi-file export
// ─────────────────────────────────────────────

@Composable
private fun ExportFolderCard(
    group: CloudFile,
    downloadExportCover: suspend (CloudFile) -> Result<File>,
    onClick: () -> Unit,
) {
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var loadingCover by remember { mutableStateOf(false) }

    val hasCover = group.coverFileId != null
    LaunchedEffect(group.id) {
        if (hasCover) {
            loadingCover = true
            cover = withContext(Dispatchers.IO) {
                try {
                    val cached = downloadExportCover(group).getOrNull() ?: return@withContext null
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(cached.absolutePath, opts)
                } catch (_: Exception) {
                    null
                }
            }
            loadingCover = false
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    loadingCover -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    cover != null -> Image(
                        bitmap = cover!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    else -> Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = group.originalName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.cloud_folder_info,
                        group.itemCount ?: 0,
                        group.outputFormat ?: ""
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(4.dp))

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────
// EXPORT FOLDER DETAIL — view of the children
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportFolderDetail(
    group: CloudFile,
    children: List<CloudFile>,
    isLoading: Boolean,
    downloadingId: String? = null,
    downloadProgress: Float = 0f,
    downloadExportCover: suspend (CloudFile) -> Result<File>,
    downloadForCache: suspend (CloudFile) -> Result<File>,
    onChildClick: (CloudFile) -> Unit,
    onDownload: (CloudFile) -> Unit,
    onDelete: (CloudFile) -> Unit,
    onBack: () -> Unit,
    onDeleteGroup: () -> Unit,
    snackbarMessage: String?,
    snackbarActionLabel: String?,
    snackbarAction: (() -> Unit)?,
    onClearSnackbar: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // This view returns early on the main screen, so the flat list snackbar
    // is NOT composed here: own feedback for downloading/deleting children.
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = snackbarActionLabel,
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                snackbarAction?.invoke()
            }
            onClearSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> AnimatedCountdownSnackbar(data) } },
        topBar = {
            TopAppBar(
                title = { Text(group.originalName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header with cover, count and format
            FolderDetailHeader(group, downloadExportCover)

            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                children.isEmpty() -> Box(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FolderOpen, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.cloud_folder_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(children, key = { it.id }) { child ->
                        ExportChildRow(
                            file = child,
                            downloadProgress = if (child.id == downloadingId) downloadProgress else null,
                            downloadForCache = downloadForCache,
                            onClick = { onChildClick(child) },
                            onDownload = { onDownload(child) },
                            onDelete = { onDelete(child) }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.cloud_move_to_trash)) },
            text = {
                Text(
                    stringResource(
                        R.string.cloud_delete_group_warning,
                        group.itemCount ?: 0
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteGroup()
                }) {
                    Text(
                        stringResource(R.string.cloud_move_to_trash_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun FolderDetailHeader(
    group: CloudFile,
    downloadExportCover: suspend (CloudFile) -> Result<File>,
) {
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(group.id) {
        if (group.coverFileId != null) {
            cover = withContext(Dispatchers.IO) {
                try {
                    val cached = downloadExportCover(group).getOrNull() ?: return@withContext null
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(cached.absolutePath, opts)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.FolderOpen, null, Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.cloud_folder_info, group.itemCount ?: 0, group.outputFormat ?: ""),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = group.originalName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun ExportChildRow(
    file: CloudFile,
    downloadProgress: Float? = null,
    downloadForCache: suspend (CloudFile) -> Result<File>,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    var thumb by remember { mutableStateOf<Bitmap?>(null) }

    val canThumb = file.mimeType?.startsWith("image/") == true ||
            file.mimeType == "application/pdf" ||
            file.originalName.endsWith(".pdf")

    LaunchedEffect(file.id) {
        if (canThumb) {
            thumb = withContext(Dispatchers.IO) {
                try {
                    val cached = downloadForCache(file).getOrNull() ?: return@withContext null
                    when {
                        file.mimeType?.startsWith("image/") == true -> {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                            BitmapFactory.decodeFile(cached.absolutePath, opts)
                        }

                        else -> null // PDF: thumbnail simplificada, icono por defecto
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.Image, null, Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = file.originalName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (file.size != null) {
                        Text(
                            text = formatCloudFileSize(file.size, stringResource(R.string.cloud_size_unknown)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Download, stringResource(R.string.cloud_download),
                        modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete, stringResource(R.string.delete),
                        modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error
                    )
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
        }
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
                            " · ${formatCloudFileSize(file.size, stringResource(R.string.cloud_size_unknown))}",
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
            text = { Text(stringResource(R.string.cloud_move_to_trash_confirm, file.originalName)) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cloud_move_to_trash_action), color = MaterialTheme.colorScheme.error)
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
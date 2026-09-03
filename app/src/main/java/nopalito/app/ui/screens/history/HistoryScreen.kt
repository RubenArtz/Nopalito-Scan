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

package nopalito.app.ui.screens.history

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nopalito.app.R
import nopalito.app.ui.DocPagePreview
import nopalito.app.ui.DocumentPagesPreview
import nopalito.app.ui.components.FileTypeBadge
import nopalito.app.ui.components.GradientHeroAction
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.components.rememberHapticManager
import nopalito.app.ui.decodeDocxMedia
import nopalito.app.ui.decodeDocxPages
import nopalito.app.ui.renderPdfPages
import nopalito.app.ui.screens.export.ExportArtifact
import nopalito.app.ui.screens.export.ExportArtifactMapper
import nopalito.app.ui.uriForFile
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    onOpenArtifact: (ExportArtifact) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Tactile confirmation for share / open / confirmed deletes.
    val haptics = rememberHapticManager()
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var deleteConfirmItem by remember { mutableStateOf<ExportHistoryEntity?>(null) }
    var folderDetailItem by remember { mutableStateOf<ExportHistoryEntity?>(null) }
    var previewItem by remember { mutableStateOf<ExportHistoryEntity?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var deleteBatchConfirm by remember { mutableStateOf(false) }
    val exitSelection = {
        selectionMode = false
        selectedIds = emptySet()
    }

    // Shared thumbnail cache: survives the preview (which removes the list from
    // composition), so returning does not reload/render every card.
    val thumbnailCache = remember { mutableMapOf<Long, Bitmap>() }

    // System back: dismiss the selection/preview/folder first, then leave the screen.
    // Without this BackHandler the back button/gesture would close the app.
    BackHandler {
        when {
            selectionMode -> exitSelection()
            previewItem != null -> previewItem = null
            folderDetailItem != null -> folderDetailItem = null
            else -> onBack()
        }
    }

    // Detail view of a multiple export: enters the folder and
    // shows the exported images.
    val currentFolderDetail = folderDetailItem
    if (currentFolderDetail != null) {
        HistoryFolderDetail(
            item = currentFolderDetail,
            onBack = { folderDetailItem = null },
            onDeleteFolder = {
                viewModel.deleteExportFolder(currentFolderDetail, context)
                folderDetailItem = null
            },
            onDeleteChild = { child ->
                viewModel.deleteFolderChild(currentFolderDetail, child.name, child.uri, context)
            },
            onDownloadChild = { child ->
                viewModel.downloadHistoryChild(context, child.uri, child.file)
            },
        )
        return
    }

    // In-app preview of a single exported file (uses the private backup copy).
    val currentPreview = previewItem
    if (currentPreview != null) {
        HistoryFilePreview(
            title = currentPreview.documentName,
            format = currentPreview.format,
            backupPath = currentPreview.backupPath,
            uri = currentPreview.exportedFilePath?.toUri(),
            onDismiss = { previewItem = null },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectionMode) {
            GradientHeroHeader(
                title = stringResource(R.string.history_selected_count, selectedIds.size),
                onBack = { exitSelection() },
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GradientHeroAction(
                            icon = Icons.Default.SelectAll,
                            contentDescription = stringResource(R.string.history_select_all),
                            onClick = {
                                val allVisibleSelected = uiState.history.isNotEmpty() &&
                                        uiState.history.all { it.id in selectedIds }
                                selectedIds = if (allVisibleSelected) {
                                    emptySet()
                                } else {
                                    uiState.history.map { it.id }.toSet()
                                }
                            },
                        )
                        GradientHeroAction(
                            icon = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            enabled = selectedIds.isNotEmpty(),
                            onClick = { if (selectedIds.isNotEmpty()) deleteBatchConfirm = true },
                        )
                    }
                },
            )
        } else {
            GradientHeroHeader(
                title = stringResource(R.string.export_history),
                subtitle = stringResource(R.string.history_subtitle),
                onBack = onBack,
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GradientHeroAction(
                            icon = Icons.Default.Checklist,
                            contentDescription = stringResource(R.string.history_select_all),
                            onClick = { selectionMode = true },
                        )
                        GradientHeroAction(
                            icon = Icons.Default.FilterList,
                            contentDescription = stringResource(R.string.history_filter),
                            onClick = { showFilterMenu = true },
                        )
                        GradientHeroAction(
                            icon = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = stringResource(R.string.sort_by),
                            onClick = { showSortMenu = true },
                        )
                    }

                    // Filter dropdown
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.filter_all)) },
                            onClick = {
                                viewModel.setFilterFormat(null)
                                showFilterMenu = false
                            },
                            leadingIcon = {
                                if (uiState.filterFormat == null) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.filter_pdf)) },
                            onClick = {
                                viewModel.setFilterFormat("PDF")
                                showFilterMenu = false
                            },
                            leadingIcon = {
                                if (uiState.filterFormat == "PDF") {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.filter_image)) },
                            onClick = {
                                viewModel.setFilterFormat("JPEG")
                                showFilterMenu = false
                            },
                            leadingIcon = {
                                if (uiState.filterFormat == "JPEG") {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.filter_word)) },
                            onClick = {
                                viewModel.setFilterFormat("DOCX")
                                showFilterMenu = false
                            },
                            leadingIcon = {
                                if (uiState.filterFormat == "DOCX") {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                        )
                    }

                    // Sort dropdown
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        SortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(sortOptionLabel(option)) },
                                onClick = {
                                    viewModel.setSortBy(option)
                                    showSortMenu = false
                                },
                                leadingIcon = {
                                    if (uiState.sortBy == option) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            )
                        }
                    }
                },
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
        ) {
            // Search bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.history_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.clear_text)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
            )

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.history.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.history_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = uiState.history,
                        key = { it.id }
                    ) { item ->
                        HistoryItemCard(
                            item = item,
                            thumbnailCache = thumbnailCache,
                            selected = item.id in selectedIds,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = if (item.id in selectedIds) {
                                        selectedIds - item.id
                                    } else {
                                        selectedIds + item.id
                                    }
                                } else if (item.resultType == ExportHistoryEntity.RESULT_TYPE_FOLDER) {
                                    // Enter the folder to see the exported images
                                    folderDetailItem = item
                                } else {
                                    // In-app preview of the exported file (own backup)
                                    previewItem = item
                                }
                            },
                            onExport = { viewModel.exportHistory(context, item) },
                            onShare = {
                                haptics.click()
                                viewModel.shareHistory(context, item)
                            },
                            onDelete = {
                                deleteConfirmItem = item
                            },
                            onOpenResult = { artifact ->
                                haptics.click()
                                onOpenArtifact(artifact)
                            }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    deleteConfirmItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteConfirmItem = null },
            title = { Text(stringResource(R.string.delete_history_entry)) },
            text = { Text(stringResource(R.string.delete_history_entry_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.success()
                        viewModel.deleteHistoryItem(item.id)
                        deleteConfirmItem = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmItem = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Batch delete confirmation dialog (multi-select mode)
    if (deleteBatchConfirm) {
        AlertDialog(
            onDismissRequest = { deleteBatchConfirm = false },
            title = { Text(stringResource(R.string.delete_history_entry)) },
            text = {
                Text(
                    stringResource(
                        R.string.history_delete_batch_warning,
                        selectedIds.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.success()
                        val items = uiState.history.filter { it.id in selectedIds }
                        viewModel.deleteHistoryItems(items, context)
                        deleteBatchConfirm = false
                        exitSelection()
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteBatchConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun HistoryItemCard(
    item: ExportHistoryEntity,
    thumbnailCache: MutableMap<Long, Bitmap>,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onOpenResult: (ExportArtifact) -> Unit,
) {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val exportArtifact = remember(item) { ExportArtifactMapper.fromHistoryEntity(item) }
    val thumbnailBitmap = rememberHistoryThumbnail(context, item, thumbnailCache)
    val isAvailable = item.status == ExportHistoryEntity.STATUS_AVAILABLE

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Selection checkbox
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
            // Thumbnail
            Box(Modifier.size(64.dp)) {
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.30f),
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            when {
                                item.resultType == ExportHistoryEntity.RESULT_TYPE_FOLDER ->
                                    Icons.Default.Folder

                                item.format == "PDF" -> Icons.Default.PictureAsPdf
                                item.format == "DOCX" -> Icons.Default.TextFields
                                else -> Icons.Default.Image
                            },
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                FileTypeBadge(
                    fileName = "file.${item.format.lowercase()}",
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

            // Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.documentName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateFormat.format(Date(item.dateTime)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = item.format,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.history_pages, item.pageCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = Formatter.formatShortFileSize(context, item.fileSizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = qualityDisplayName(item.quality),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Status badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isAvailable)
                        MaterialTheme.colorScheme.secondaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = if (isAvailable)
                            stringResource(R.string.status_available)
                        else
                            stringResource(R.string.status_deleted),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (isAvailable)
                            MaterialTheme.colorScheme.onSecondaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Per-file actions are hidden while multi-selecting.
            if (!selectionMode) {
                var menuOpen by remember { mutableStateOf(false) }
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
                            text = { Text(stringResource(R.string.re_export)) },
                            leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                            onClick = { menuOpen = false; onExport() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share)) },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = { menuOpen = false; onShare() },
                        )
                        if (exportArtifact != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                onClick = { menuOpen = false; onOpenResult(exportArtifact) },
                            )
                        }
                        if (isAvailable) {
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
                                onClick = { menuOpen = false; onDelete() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun qualityDisplayName(quality: String): String {
    return when (quality) {
        "ORIGINAL" -> stringResource(R.string.quality_original)
        "HIGH" -> stringResource(R.string.quality_high)
        "BALANCED" -> stringResource(R.string.quality_balanced)
        "COMPRESSED" -> stringResource(R.string.quality_compressed)
        "MAX_COMPRESSION" -> stringResource(R.string.quality_max_compression)
        else -> quality
    }
}

@Composable
private fun sortOptionLabel(option: SortOption): String {
    return when (option) {
        SortOption.DATE_DESC -> stringResource(R.string.sort_date)
        SortOption.DATE_ASC -> stringResource(R.string.sort_date) + " ↑"
        SortOption.NAME_ASC -> stringResource(R.string.sort_name) + " A-Z"
        SortOption.NAME_DESC -> stringResource(R.string.sort_name) + " Z-A"
        SortOption.SIZE_ASC -> stringResource(R.string.sort_size) + " ↑"
        SortOption.SIZE_DESC -> stringResource(R.string.sort_size) + " ↓"
    }
}

// ─────────────────────────────────────────────
// FOLDER DETAIL — view of the exported images
// ─────────────────────────────────────────────

private data class HistoryChild(
    val uri: Uri?,
    val file: File?,
    val name: String,
    val sizeBytes: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFolderDetail(
    item: ExportHistoryEntity,
    onBack: () -> Unit,
    onDeleteFolder: () -> Unit,
    onDeleteChild: (HistoryChild) -> Unit = {},
    onDownloadChild: (HistoryChild) -> Unit = {},
) {
    val context = LocalContext.current
    var children by remember(item.id) { mutableStateOf<List<HistoryChild>>(emptyList()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var previewChild by remember { mutableStateOf<HistoryChild?>(null) }
    var pendingDeleteChild by remember { mutableStateOf<HistoryChild?>(null) }
    LaunchedEffect(item.id) {
        children = resolveHistoryChildren(context, item)
    }

    val child = previewChild
    if (child != null) {
        HistoryFilePreview(
            title = child.name,
            format = "JPEG",
            backupPath = child.file?.absolutePath,
            uri = child.uri,
            onDismiss = { previewChild = null },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GradientHeroHeader(
            title = item.documentName,
            subtitle = stringResource(
                R.string.history_folder_info,
                item.exportedItemCount,
                item.format
            ),
            onBack = onBack,
            actions = {
                GradientHeroAction(
                    icon = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    onClick = { showDeleteConfirm = true },
                )
            },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Header: folder + number of images + format
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.FolderOpen, null, Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(
                            R.string.history_folder_info,
                            item.exportedItemCount,
                            item.format
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = item.documentName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            if (children.isEmpty()) {
                Box(
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
                            stringResource(R.string.history_folder_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(children, key = { it.name }) { child ->
                        HistoryChildRow(
                            child = child,
                            onClick = { previewChild = child },
                            onExport = { exportHistoryChild(context, child) },
                            onDownload = { onDownloadChild(child) },
                            onDelete = { pendingDeleteChild = child },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_history_folder)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_history_folder_warning,
                        item.exportedItemCount
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteFolder()
                }) {
                    Text(
                        stringResource(R.string.delete),
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

    pendingDeleteChild?.let { child ->
        AlertDialog(
            onDismissRequest = { pendingDeleteChild = null },
            title = { Text(stringResource(R.string.delete_image)) },
            text = { Text(stringResource(R.string.delete_image_warning, child.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteChild = null
                    onDeleteChild(child)
                    children = children.filterNot { it.name == child.name }
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteChild = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun HistoryChildRow(
    child: HistoryChild,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val thumb = rememberHistoryChildThumbnail(context, child)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
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
                        bitmap = thumb.asImageBitmap(),
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
                    text = child.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (child.sizeBytes > 0) {
                    Text(
                        text = Formatter.formatShortFileSize(context, child.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Download this single image to the export folder
            IconButton(
                onClick = onDownload,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.FileUpload,
                    stringResource(R.string.re_export),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Export / share this single image
            IconButton(
                onClick = onExport,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Share,
                    stringResource(R.string.share),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Delete this single image
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    stringResource(R.string.delete),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }

            Icon(
                Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Exports (shares) a single image of a multi-image export via a share sheet. */
private fun exportHistoryChild(context: Context, child: HistoryChild) {
    val uri = child.file?.let { uriForFile(context, it) } ?: child.uri ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_STREAM, uri)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(
                intent,
                context.getString(R.string.share_document)
            )
        )
    }
}

@Composable
private fun rememberHistoryChildThumbnail(context: Context, child: HistoryChild): Bitmap? {
    var thumb by remember(child.uri, child.file) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(child.uri, child.file) {
        thumb = withContext(Dispatchers.IO) {
            runCatching {
                child.file?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    ?: child.uri?.let { uri ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
                        } else null
                    }
            }.getOrNull()
        }
    }
    return thumb
}

private suspend fun resolveHistoryChildren(
    context: Context,
    item: ExportHistoryEntity
): List<HistoryChild> =
    withContext(Dispatchers.IO) {
        // Prefer the app-private backup copy: it survives deletion from Downloads.
        val backupDir = item.backupDirPath?.let { File(it) }
        if (backupDir?.isDirectory == true) {
            backupDir.listFiles()
                ?.sortedBy { it.name.lowercase() }
                ?.map {
                    HistoryChild(
                        uri = null,
                        file = it,
                        name = it.name,
                        sizeBytes = it.length()
                    )
                }
                ?: emptyList()
        } else {
            val defaultName = context.getString(R.string.image_default_name)
            item.childrenUris
                ?.split("\n")
                ?.mapNotNull { uriString ->
                    runCatching {
                        val uri = uriString.toUri()
                        var name = uri.lastPathSegment?.substringAfterLast('/') ?: defaultName
                        var size = 0L
                        context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                            null, null, null
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                                if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                            }
                        }
                        HistoryChild(uri, null, name, size)
                    }.getOrNull()
                }
                ?: emptyList()
        }
    }

// ─────────────────────────────────────────────
// IN-APP PREVIEW — whole exported file
// ─────────────────────────────────────────────

private sealed interface HistoryPreview {
    data class Pages(val pages: List<DocPagePreview>) : HistoryPreview
    data object Error : HistoryPreview
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilePreview(
    title: String,
    format: String,
    backupPath: String?,
    uri: Uri?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<HistoryPreview?>(null) }

    LaunchedEffect(title, format, backupPath, uri) {
        preview = null
        preview = withContext(Dispatchers.IO) {
            buildHistoryPreview(context, format, backupPath, uri)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GradientHeroHeader(
            title = title,
            onBack = onDismiss,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when (val p = preview) {
                null -> CircularProgressIndicator()
                is HistoryPreview.Pages -> DocumentPagesPreview(p.pages)

                is HistoryPreview.Error -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.ErrorOutline, null, Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.cloud_no_preview),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

private fun buildHistoryPreview(
    context: Context,
    format: String,
    backupPath: String?,
    uri: Uri?,
): HistoryPreview {
    val file = previewSourceFile(context, backupPath, uri)
    val pages = when (format) {
        "PDF" -> file?.let { renderPdfPages(it) }.orEmpty()
        "DOCX" -> file?.let { decodeDocxPages(it, 1400) }.orEmpty()
        else -> renderImagePages(context, file, uri)
    }
    return if (pages.isEmpty()) HistoryPreview.Error else HistoryPreview.Pages(pages)
}

/** The private backup copy when present; otherwise resolve the exported uri. */
private fun previewSourceFile(context: Context, backupPath: String?, uri: Uri?): File? {
    backupPath?.let { path ->
        val file = File(path)
        if (file.exists()) return file
    }
    val source = uri ?: return null
    return runCatching {
        if (source.scheme == "file") {
            File(source.path!!)
        } else {
            // Unique temp file: concurrent thumbnail/preview loads must never
            // share the same path (a shared path would get corrupted by
            // simultaneous writes and fail to render).
            val tmp = File.createTempFile("history", ".bin", context.cacheDir)
            context.contentResolver.openInputStream(source)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            tmp
        }
    }.getOrNull()
}

private fun renderImagePages(context: Context, file: File?, uri: Uri?): List<DocPagePreview> {
    val bmp = file?.let { BitmapFactory.decodeFile(it.absolutePath) }
        ?: uri?.let { source ->
            runCatching {
                context.contentResolver.openInputStream(source)
                    ?.use { stream -> BitmapFactory.decodeStream(stream) }
            }.getOrNull()
        }
    return if (bmp != null) listOf(DocPagePreview(bmp, null)) else emptyList()
}

// ─────────────────────────────────────────────
// CARD THUMBNAIL — derived from the private backup
// ─────────────────────────────────────────────

@Composable
private fun rememberHistoryThumbnail(
    context: Context,
    item: ExportHistoryEntity,
    cache: MutableMap<Long, Bitmap>,
): Bitmap? {
    var thumb by remember(item.id) { mutableStateOf(cache[item.id]) }
    LaunchedEffect(item.id) {
        if (thumb == null) {
            val loaded = withContext(Dispatchers.IO) { loadHistoryThumbnail(context, item) }
            if (loaded != null) cache[item.id] = loaded
            thumb = loaded
        }
    }
    return thumb
}

private fun loadHistoryThumbnail(context: Context, item: ExportHistoryEntity): Bitmap? {
    // Folder: first image of the private backup.
    item.backupDirPath?.let { dir ->
        val backupDir = File(dir)
        if (backupDir.isDirectory) {
            return backupDir.listFiles()
                ?.sortedBy { it.name.lowercase() }
                ?.firstOrNull()
                ?.let { decodeScaled(it.absolutePath) }
        }
    }
    // Single file: private backup -> stored thumbnail -> exported file (MediaStore/SAF).
    val source = item.backupPath?.let { File(it) }?.takeIf { it.exists() }
        ?: item.thumbnailPath?.let { File(it) }?.takeIf { it.exists() }
        ?: previewSourceFile(context, item.backupPath, item.exportedFilePath?.toUri())
    if (source == null) return null
    return when (item.format) {
        "PDF" -> renderPdfFirstPage(source)
        "DOCX" -> runCatching { decodeDocxMedia(source, maxDim = 512).firstOrNull() }.getOrNull()
        else -> decodeScaled(source.absolutePath)
    }
}

private fun renderPdfFirstPage(file: File): Bitmap? = try {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)
    val bmp = if (renderer.pageCount > 0) {
        val page = renderer.openPage(0)
        val scale = minOf(1f, 512f / maxOf(page.width, page.height))
        val out = createBitmap(
            maxOf(1, (page.width * scale).toInt()),
            maxOf(1, (page.height * scale).toInt())
        )
        out.eraseColor(android.graphics.Color.WHITE)
        page.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        out
    } else null
    renderer.close()
    pfd.close()
    bmp
} catch (_: Exception) {
    null
}

private fun decodeScaled(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 512) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
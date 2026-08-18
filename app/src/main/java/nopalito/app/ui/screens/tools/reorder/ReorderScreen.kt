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

package nopalito.app.ui.screens.tools.reorder

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nopalito.app.R
import nopalito.app.ui.ZoomableBitmapDialog
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.screens.export.formatFileSize
import nopalito.app.ui.screens.tools.PickedFile
import nopalito.app.ui.screens.tools.queryDisplayName
import nopalito.app.ui.screens.tools.querySizeBytes
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Screen of the "Reorder PDF pages" tool.
 *
 * Mirrors the other tools (Extract / PasswordProtect / Convert): same top bar,
 * file picker card, save-location card, message cards and share/open result
 * flow. The distinctive sections are the main page preview (always showing the
 * page at the currently selected position of the new order) and the horizontal
 * thumbnail strip at the bottom, reordered with the same drag-and-drop
 * mechanism as the document editor (sh.calvin.reorderable).
 *
 * On wide screens (tablets / landscape) the preview is shown on the left and
 * the thumbnails + controls on the right, so pages are not shrunk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderScreen(
    viewModel: ReorderViewModel,
    onBack: () -> Unit,
    onShare: (List<ReorderResult>) -> Unit,
    onOpen: (List<ReorderResult>) -> Unit,
    onGoToCloud: () -> Unit = {},
    topBarActions: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultFileName = stringResource(R.string.tools_default_filename)

    BackHandler { onBack() }

    val singleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: defaultFileName
            if (name.substringAfterLast('.', "").lowercase() == "pdf") {
                viewModel.addFile(
                    PickedFile(
                        name = name,
                        uri = uri,
                        sizeBytes = querySizeBytes(context, uri),
                    )
                )
            } else {
                viewModel.reportInvalidFileType()
            }
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                // Not all providers support persistable grants; writing still works.
            }
            viewModel.setSaveLocation(uri.toString())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GradientHeroHeader(
            title = stringResource(R.string.tools_reorder_pdf),
            subtitle = stringResource(R.string.tools_reorder_pdf_desc),
            onBack = onBack,
            actions = { topBarActions() },
        )
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val wide = maxWidth >= 720.dp
            val previewMaxHeight = (maxHeight * 0.6f).coerceAtLeast(280.dp)
            // Reserve exactly the measured height of the pinned bottom strip, so
            // the scrollable content (export/share controls) is never hidden
            // behind it. Measured dynamically because the strip height varies
            // with the device / font scale.
            var stripHeight by remember { mutableStateOf(0.dp) }
            val bottomInset = if (state.isLoaded) stripHeight else 0.dp
            // The thumbnail strip is pinned at the bottom (like the editor's
            // DocumentBar), OUTSIDE the scrollable content: a reorderable
            // LazyRow inside a scroll container would otherwise fight the
            // parent's vertical scroll during a drag and the screen would
            // jump back to the top. The scrollable column reserves bottom
            // space so nothing is hidden behind the strip.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomInset),
            ) {
                if (wide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1.2f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            FilesSection(
                                fileName = state.fileName,
                                sizeBytes = state.sizeBytes,
                                isLoading = state.isLoading,
                                onPick = { singleLauncher.launch(arrayOf("application/pdf")) },
                            )
                            if (state.isLoading) {
                                LoadingCard()
                            }
                            state.errorMessage?.let { message ->
                                MessageCard(message = message)
                            }
                            if (state.isLoaded) {
                                PreviewSection(
                                    viewModel = viewModel,
                                    pageOrder = state.pageOrder,
                                    selectedIndex = state.selectedPreviewIndex,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                )
                                SignatureNoteCard()
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (state.isLoaded) {
                                DocumentInfoCard(
                                    fileName = state.fileName,
                                    pageCount = state.pageCount,
                                )
                                ThumbnailsInfo()
                                SaveLocationCard(
                                    locationName = state.saveLocationName,
                                    onChange = { folderLauncher.launch(null) },
                                )
                                ExportControls(
                                    state = state,
                                    onNameChange = viewModel::setOutputFileName,
                                    onPasswordEnabledChange = viewModel::setPasswordEnabled,
                                    onPasswordChange = viewModel::setPassword,
                                    onGeneratePassword = viewModel::generatePassword,
                                    onToggleCloudUpload = viewModel::toggleCloudUpload,
                                    onExport = viewModel::exportPdf,
                                    context = context,
                                    onShare = onShare,
                                    onOpen = onOpen,
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        FilesSection(
                            fileName = state.fileName,
                            sizeBytes = state.sizeBytes,
                            isLoading = state.isLoading,
                            onPick = { singleLauncher.launch(arrayOf("application/pdf")) },
                        )

                        if (state.isLoading) {
                            LoadingCard()
                        }
                        state.errorMessage?.let { message ->
                            MessageCard(message = message)
                        }

                        if (state.isLoaded) {
                            DocumentInfoCard(
                                fileName = state.fileName,
                                pageCount = state.pageCount,
                            )

                            SectionTitle(stringResource(R.string.ror_preview_title))
                            PreviewSection(
                                viewModel = viewModel,
                                pageOrder = state.pageOrder,
                                selectedIndex = state.selectedPreviewIndex,
                                modifier = Modifier.heightIn(max = previewMaxHeight),
                            )

                            SignatureNoteCard()

                            ThumbnailsInfo()

                            SaveLocationCard(
                                locationName = state.saveLocationName,
                                onChange = { folderLauncher.launch(null) },
                            )

                            ExportControls(
                                state = state,
                                onNameChange = viewModel::setOutputFileName,
                                onPasswordEnabledChange = viewModel::setPasswordEnabled,
                                onPasswordChange = viewModel::setPassword,
                                onGeneratePassword = viewModel::generatePassword,
                                onToggleCloudUpload = viewModel::toggleCloudUpload,
                                onExport = viewModel::exportPdf,
                                context = context,
                                onShare = onShare,
                                onOpen = onOpen,
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            if (state.isLoaded) {
                val density = LocalDensity.current
                Surface(
                    shape = if (wide) {
                        RoundedCornerShape(0)
                    } else {
                        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    },
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = if (wide) 0.dp else 12.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            with(density) {
                                stripHeight = coords.size.height.toDp()
                            }
                        },
                ) {
                    ThumbnailsStrip(
                        viewModel = viewModel,
                        pageOrder = state.pageOrder,
                        selectedIndex = state.selectedPreviewIndex,
                        onSelect = viewModel::selectPage,
                        onMove = viewModel::movePage,
                    )
                }
            }
        }
    }

    if (state.generateDialogVisible) {
        GeneratedPasswordDialog(
            password = state.generatedPassword,
            onUse = viewModel::acceptGeneratedPassword,
            onDismiss = viewModel::dismissGenerateDialog,
        )
    }

    if (state.premiumDialogVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPremiumDialog,
            title = { Text(stringResource(R.string.tools_upload_cloud)) },
            text = { Text(stringResource(R.string.tools_cloud_premium_required)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissPremiumDialog()
                        onGoToCloud()
                    }
                ) {
                    Text(stringResource(R.string.tools_go_login))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPremiumDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun FilesSection(
    fileName: String,
    sizeBytes: Long,
    isLoading: Boolean,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        onClick = onPick,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (fileName.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                RoundedCornerShape(32.dp),
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.ror_select_pdf),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.ror_files_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            if (isLoading) R.string.ror_loading
                            else R.string.ror_tap_to_change
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!isLoading && sizeBytes > 0) {
                        Text(
                            text = formatFileSize(sizeBytes, context),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.ror_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun DocumentInfoCard(fileName: String, pageCount: Int) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.ror_pages_total, pageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Full-document preview: every page of the current order rendered as a
 * vertical, scrollable list (scroll up and down through the whole document).
 * The order is read from [ReorderViewModel.state]'s `pageOrder`, so dragging a
 * thumbnail in the strip reorders the list live, without rebuilding the PDF.
 *
 * Rendering stays lazy (only visible pages are composed and rendered, on IO,
 * serialized by the ViewModel) and items are keyed by the original page number,
 * so reordering never re-renders a page and memory stays bounded on large
 * documents. Tapping a thumbnail scrolls the preview to that page; tapping a
 * page opens it in the zoom dialog.
 */
@Composable
private fun PreviewSection(
    viewModel: ReorderViewModel,
    pageOrder: List<Int>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    var zoomedPageNumber by remember { mutableIntStateOf(-1) }
    var zoomBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0 && selectedIndex < pageOrder.size) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val targetWidth = constraints.maxWidth.coerceIn(100, 1000)
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(pageOrder, key = { _, original -> original }) { index, original ->
                PagePreviewItem(
                    viewModel = viewModel,
                    originalPageNumber = original,
                    position = index,
                    pageCount = pageOrder.size,
                    targetWidth = targetWidth,
                    isZoomed = zoomedPageNumber == original,
                    onZoom = {
                        zoomedPageNumber = original
                        zoomBitmap = it
                    },
                )
            }
        }
    }

    zoomBitmap?.let { page ->
        ZoomableBitmapDialog(bitmap = page, onDismiss = {
            zoomBitmap = null
            zoomedPageNumber = -1
        })
    }
}

/** One page of the full-document preview: label + rendered image (tap to zoom). */
@Composable
private fun PagePreviewItem(
    viewModel: ReorderViewModel,
    originalPageNumber: Int,
    position: Int,
    pageCount: Int,
    targetWidth: Int,
    isZoomed: Boolean,
    onZoom: (Bitmap) -> Unit,
) {
    var bitmap by remember(originalPageNumber) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(originalPageNumber) { mutableStateOf(false) }
    val zoomedNow by rememberUpdatedState(isZoomed)

    LaunchedEffect(originalPageNumber, targetWidth) {
        val rendered = viewModel.renderPageForPreview(originalPageNumber - 1, targetWidth)
        if (rendered == null) {
            failed = true
        } else {
            // Never recycle the bitmap while the zoom dialog is showing it.
            if (!zoomedNow) bitmap?.recycle()
            bitmap = rendered
        }
    }
    DisposableEffect(originalPageNumber) {
        onDispose {
            // Never recycle while the zoom dialog is showing this bitmap.
            if (!zoomedNow) bitmap?.recycle()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.ror_page_preview_label, position + 1, pageCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            val bmp = bitmap
            when {
                bmp != null -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.ror_page_preview_label, position + 1, pageCount),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.clickable { onZoom(bmp) },
                )

                failed -> Text(
                    text = stringResource(R.string.ror_preview_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )

                else -> CircularProgressIndicator(
                    modifier = Modifier.padding(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** Title + hint of the thumbnail strip, shown in the scrollable content. */
@Composable
private fun ThumbnailsInfo() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.ror_thumbnails_title))
        Text(
            text = stringResource(R.string.ror_thumbnails_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Pinned horizontal strip of page thumbnails (the bottom panel of the screen,
 * outside any scroll container - the same arrangement as the document editor's
 * `DocumentBar`). Drag-and-drop reuses the same mechanism as the editor
 * (sh.calvin.reorderable): long-press a thumbnail and drag it left/right; the
 * library animates the move, draws the insertion indicator and auto-scrolls at
 * the edges. Releasing the finger calls [ReorderViewModel.movePage], which
 * updates the observable order; the strip and the preview recompose
 * immediately.
 *
 * Items are keyed by original page number: reordering never re-renders an
 * already-cached thumbnail, and LazyRow only composes visible items, so memory
 * stays bounded on large documents.
 */
@Composable
private fun ThumbnailsStrip(
    viewModel: ReorderViewModel,
    pageOrder: List<Int>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(
            pageOrder,
            key = { _, original -> original },
        ) { index, original ->
            ReorderableItem(reorderableState, key = original) { isDragging ->
                ThumbnailItem(
                    viewModel = viewModel,
                    originalPageNumber = original,
                    position = index,
                    selected = index == selectedIndex,
                    isDragging = isDragging,
                    modifier = Modifier.longPressDraggableHandle(),
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun ThumbnailItem(
    viewModel: ReorderViewModel,
    originalPageNumber: Int,
    position: Int,
    selected: Boolean,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var bitmap by remember(originalPageNumber) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(originalPageNumber) { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier.width(THUMBNAIL_WIDTH_DP),
        contentAlignment = Alignment.Center,
    ) {
        val targetWidth = constraints.maxWidth.coerceAtLeast(60)

        LaunchedEffect(originalPageNumber, targetWidth) {
            val rendered = viewModel.renderPageForPreview(originalPageNumber - 1, targetWidth)
            if (rendered == null) {
                failed = true
            } else {
                bitmap?.recycle()
                bitmap = rendered
            }
        }
        DisposableEffect(originalPageNumber) {
            onDispose { bitmap?.recycle() }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val borderColor = when {
                isDragging -> MaterialTheme.colorScheme.primary
                selected -> MaterialTheme.colorScheme.primary
                else -> Color.LightGray
            }
            Card(
                elevation = CardDefaults.cardElevation(
                    defaultElevation = if (isDragging) 8.dp else 2.dp,
                ),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(if (selected || isDragging) 2.dp else 1.dp, borderColor),
                modifier = modifier
                    .clickable(onClick = onClick),
            ) {
                Box(
                    modifier = Modifier
                        .width(THUMBNAIL_WIDTH_DP)
                        .height(THUMBNAIL_HEIGHT_DP),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    when {
                        bmp != null -> Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )

                        failed -> Text(
                            text = stringResource(R.string.ror_preview_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(4.dp),
                        )

                        else -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.5f),
                        contentColor = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(4.dp),
                    ) {
                        Text(
                            text = "$originalPageNumber",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ror_position, position + 1),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

private val THUMBNAIL_WIDTH_DP = 110.dp
private val THUMBNAIL_HEIGHT_DP = 150.dp

@Composable
private fun SignatureNoteCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.ror_signatures_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SaveLocationCard(locationName: String?, onChange: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tools_save_location),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = locationName ?: stringResource(R.string.download_dirname),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onChange) {
                Text(stringResource(R.string.tools_change_folder))
            }
        }
    }
}

@Composable
private fun ExportControls(
    state: ReorderUiState,
    onNameChange: (String) -> Unit,
    onPasswordEnabledChange: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onGeneratePassword: () -> Unit,
    onToggleCloudUpload: () -> Unit,
    onExport: () -> Unit,
    context: Context,
    onShare: (List<ReorderResult>) -> Unit,
    onOpen: (List<ReorderResult>) -> Unit,
) {
    SectionTitle(stringResource(R.string.ror_output_name_label))
    OutlinedTextField(
        value = state.outputFileName,
        onValueChange = onNameChange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    CloudUploadCard(
        authenticated = state.isAuthenticated,
        checked = state.cloudUploadEnabled,
        onToggle = onToggleCloudUpload,
    )

    PasswordSection(
        enabled = state.passwordEnabled,
        onEnabledChange = onPasswordEnabledChange,
        password = state.password,
        onPasswordChange = onPasswordChange,
        onGenerate = onGeneratePassword,
    )

    Button(
        onClick = onExport,
        enabled = !state.isProcessing,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        if (state.isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ror_export))
        } else {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ror_export), fontWeight = FontWeight.SemiBold)
        }
    }

    if (state.isProcessing) {
        LinearProgressIndicator(
            progress = { state.progress ?: 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
        )
        state.progressLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (state.results.isNotEmpty()) {
        ResultCard(
            context = context,
            result = state.results.first(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = { onShare(state.results) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share), fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { onOpen(state.results) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.open), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MessageCard(message: String) {
    val container = MaterialTheme.colorScheme.errorContainer
    val content = MaterialTheme.colorScheme.onErrorContainer
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun ResultCard(
    context: Context,
    result: ReorderResult,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ror_success),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = result.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.pp_size, formatFileSize(result.sizeBytes, context)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (result.protected) {
                Text(
                    text = stringResource(R.string.tools_password_protected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            when (result.cloudUploadSuccess) {
                false -> Text(
                    text = result.cloudUploadError
                        ?: stringResource(R.string.cloud_error_upload),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                true -> Text(
                    text = stringResource(R.string.tools_cloud_uploaded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                null -> TODO()
            }
        }
    }
}

@Composable
private fun PasswordSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    onGenerate: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ror_password_toggle),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.ror_password_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                PasswordField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.ror_password_label),
                    show = reveal,
                    onToggleShow = { reveal = !reveal },
                    trailingGenerate = onGenerate,
                )
            }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    show: Boolean,
    onToggleShow: () -> Unit,
    trailingGenerate: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
        visualTransformation = if (show) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            Row {
                IconButton(onClick = onToggleShow) {
                    Icon(
                        imageVector = if (show) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = stringResource(
                            if (show) R.string.tools_hide_password
                            else R.string.tools_show_password
                        ),
                    )
                }
                if (trailingGenerate != null) {
                    IconButton(onClick = trailingGenerate) {
                        Icon(
                            imageVector = Icons.Default.Casino,
                            contentDescription = stringResource(R.string.tools_generate_password),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GeneratedPasswordDialog(
    password: String?,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.pp_generate_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pp_generate_dialog_desc))
                if (password != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = password,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUse) {
                Text(
                    stringResource(R.string.pp_generate_dialog_use),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun CloudUploadCard(
    authenticated: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.tools_upload_cloud),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = stringResource(R.string.ror_cloud_upload_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!authenticated) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Switch(checked = checked, onCheckedChange = null, enabled = authenticated)
        }
    }
}
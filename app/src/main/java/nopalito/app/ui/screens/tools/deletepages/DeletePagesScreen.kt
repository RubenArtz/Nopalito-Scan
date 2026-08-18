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

package nopalito.app.ui.screens.tools.deletepages

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
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

/**
 * Screen of the "Delete PDF pages" tool.
 *
 * Mirrors the other tools (Extract / PasswordProtect / Convert): same top bar,
 * file picker card, save-location card, message cards and share/open result
 * flow. The two distinctive sections carry the deletion workflow:
 *
 * - The main page preview shows every remaining page (scrollable, tap to zoom),
 *   updating live as pages are deleted.
 * - The pinned thumbnail strip at the bottom shows one thumbnail per page, each
 *   with its own trash button (delete that single page), plus a delete mode
 *   that marks several thumbnails at once (tap/long-press or "Seleccionar
 *   todas") so they can be removed in batch with "Eliminar N", a confirmation
 *   dialog and an undo bar. The "delete blank pages" analysis has its own
 *   progress and result dialogs.
 *
 * This tool only removes pages - it never reorders them; the original PDF is
 * never modified (each export produces a new file without the deleted pages).
 *
 * On wide screens (tablets / landscape) the preview is shown on the left and
 * the thumbnails + controls on the right, so pages are not shrunk.
 */
@Composable
fun DeletePagesScreen(
    viewModel: DeletePagesViewModel,
    onBack: () -> Unit,
    onShare: (List<DeletePagesResult>) -> Unit,
    onOpen: (List<DeletePagesResult>) -> Unit,
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
            title = stringResource(R.string.tools_delete_pages),
            subtitle = stringResource(R.string.tools_delete_pages_desc),
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
            // behind it. Measured dynamically because the strip contains an
            // action bar whose height changes with the delete mode / undo bar.
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
                                ThumbnailsInfo(deleteMode = state.deleteMode)
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

                            SectionTitle(stringResource(R.string.dpp_preview_title))
                            PreviewSection(
                                viewModel = viewModel,
                                pageOrder = state.pageOrder,
                                selectedIndex = state.selectedPreviewIndex,
                                modifier = Modifier.heightIn(max = previewMaxHeight),
                            )

                            SignatureNoteCard()

                            ThumbnailsInfo(deleteMode = state.deleteMode)

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
                        deleteMode = state.deleteMode,
                        markedForDeletion = state.markedForDeletion,
                        isAnalyzingBlanks = state.isAnalyzingBlanks,
                        canUndo = state.undoStack.isNotEmpty(),
                        undoCount = state.undoStack.lastOrNull()?.deletedCount ?: 0,
                        onSelect = viewModel::selectPage,
                        onToggleMark = viewModel::toggleMarkedForDeletion,
                        onDeletePage = viewModel::deletePage,
                        onSelectAll = viewModel::markAllForDeletion,
                        onClearAll = viewModel::clearMarkedForDeletion,
                        onToggleDeleteMode = viewModel::toggleDeleteMode,
                        onRequestDelete = viewModel::requestDeleteConfirmation,
                        onStartBlankAnalysis = viewModel::startBlankAnalysis,
                        onCancelBlankAnalysis = viewModel::cancelBlankAnalysis,
                        onUndo = viewModel::undoLastDeletion,
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

    if (state.deleteDialogVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text(stringResource(R.string.dpp_confirm_delete_title)) },
            text = {
                Text(stringResource(R.string.dpp_confirm_delete_text, state.markedForDeletion.size))
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDeleteMarked,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        stringResource(R.string.dpp_delete_confirm, state.markedForDeletion.size),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirmation) {
                    Text(stringResource(R.string.cancel))
                }
            },
            shape = RoundedCornerShape(28.dp),
        )
    }

    if (state.isAnalyzingBlanks) {
        BlankAnalysisDialog(
            progress = state.blankProgress ?: 0f,
            label = state.blankProgressLabel ?: "",
            onCancel = viewModel::cancelBlankAnalysis,
        )
    }

    state.blankResult?.let { result ->
        BlankResultDialog(
            viewModel = viewModel,
            result = result,
            onConfirm = viewModel::confirmBlankDeletion,
            onDismiss = viewModel::dismissBlankResult,
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
                        text = stringResource(R.string.dpp_select_pdf),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.dpp_files_hint),
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
                            if (isLoading) R.string.dpp_loading
                            else R.string.dpp_tap_to_change
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
                text = stringResource(R.string.dpp_loading),
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
                    text = stringResource(R.string.dpp_pages_total, pageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Full-document preview: every remaining page rendered as a vertical,
 * scrollable list (scroll up and down through the whole document). The order is
 * read from [DeletePagesViewModel.state]'s `pageOrder`, so deleting a page
 * updates the list live, without rebuilding the PDF.
 *
 * Rendering stays lazy (only visible pages are composed and rendered, on IO,
 * serialized by the ViewModel) and items are keyed by the original page number,
 * so memory stays bounded on large documents. Tapping a page opens it in the
 * zoom dialog.
 */
@Composable
private fun PreviewSection(
    viewModel: DeletePagesViewModel,
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
    viewModel: DeletePagesViewModel,
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
            text = stringResource(R.string.dpp_page_preview_label, position + 1, pageCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                val bmp = bitmap
                when {
                    bmp != null -> Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.dpp_page_preview_label, position + 1, pageCount),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.clickable { onZoom(bmp) },
                    )

                    failed -> Text(
                        text = stringResource(R.string.dpp_preview_failed),
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
}

/** Title + hint of the thumbnail strip, shown in the scrollable content. */
@Composable
private fun ThumbnailsInfo(deleteMode: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.dpp_thumbnails_title))
        Text(
            text = stringResource(
                if (deleteMode) R.string.dpp_delete_mode_hint
                else R.string.dpp_thumbnails_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Pinned horizontal strip of page thumbnails (the bottom panel of the screen,
 * outside any scroll container - the same arrangement as the document editor's
 * `DocumentBar`). A compact action row sits above the strip: it starts the
 * page-deletion mode (or confirms/cancels it) and the blank-page analysis, and
 * it offers an undo after a deletion.
 *
 * In normal mode tapping a thumbnail shows that page in the preview; in delete
 * mode tapping (or long-pressing) a thumbnail marks/unmarks it for deletion, so
 * several pages can be selected and deleted at once. The order never changes
 * here: deleting pages is the only purpose of this tool.
 *
 * Items are keyed by original page number: LazyRow only composes visible
 * items, so memory stays bounded on large documents.
 */
@Composable
private fun ThumbnailsStrip(
    viewModel: DeletePagesViewModel,
    pageOrder: List<Int>,
    selectedIndex: Int,
    deleteMode: Boolean,
    markedForDeletion: Set<Int>,
    isAnalyzingBlanks: Boolean,
    canUndo: Boolean,
    undoCount: Int,
    onSelect: (Int) -> Unit,
    onToggleMark: (Int) -> Unit,
    onDeletePage: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onToggleDeleteMode: () -> Unit,
    onRequestDelete: () -> Unit,
    onStartBlankAnalysis: () -> Unit,
    onCancelBlankAnalysis: () -> Unit,
    onUndo: () -> Unit,
) {
    Column {
        StripActions(
            deleteMode = deleteMode,
            markedCount = markedForDeletion.size,
            allSelected = pageOrder.isNotEmpty() && markedForDeletion.size == pageOrder.size,
            isAnalyzingBlanks = isAnalyzingBlanks,
            canUndo = canUndo,
            undoCount = undoCount,
            onToggleDeleteMode = onToggleDeleteMode,
            onRequestDelete = onRequestDelete,
            onSelectAll = onSelectAll,
            onClearAll = onClearAll,
            onStartBlankAnalysis = onStartBlankAnalysis,
            onCancelBlankAnalysis = onCancelBlankAnalysis,
            onUndo = onUndo,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(
                pageOrder,
                key = { _, original -> original },
            ) { index, original ->
                val marked = original in markedForDeletion
                ThumbnailItem(
                    viewModel = viewModel,
                    originalPageNumber = original,
                    position = index,
                    selected = if (deleteMode) marked else index == selectedIndex,
                    marked = marked,
                    onToggle = {
                        if (deleteMode) onToggleMark(original) else onSelect(index)
                    },
                    onDelete = { onDeletePage(original) },
                )
            }
        }
    }
}

/** Compact action bar above the thumbnail strip. */
@Composable
private fun StripActions(
    deleteMode: Boolean,
    markedCount: Int,
    allSelected: Boolean,
    isAnalyzingBlanks: Boolean,
    canUndo: Boolean,
    undoCount: Int,
    onToggleDeleteMode: () -> Unit,
    onRequestDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onStartBlankAnalysis: () -> Unit,
    onCancelBlankAnalysis: () -> Unit,
    onUndo: () -> Unit,
) {
    if (isAnalyzingBlanks) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancelBlankAnalysis) {
                Text(stringResource(R.string.dpp_blank_cancel))
            }
        }
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = if (deleteMode) 24.dp else 6.dp, bottom = 6.dp),
    ) {
        if (deleteMode) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = if (allSelected) onClearAll else onSelectAll) {
                        Text(
                            stringResource(
                                if (allSelected) R.string.dpp_unselect_all
                                else R.string.dpp_select_all
                            ),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TextButton(onClick = onToggleDeleteMode) {
                        Text(stringResource(R.string.dpp_cancel_delete))
                    }
                    Button(
                        onClick = onRequestDelete,
                        enabled = markedCount > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.dpp_delete_confirm, markedCount),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (markedCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = (-16).dp),
                    ) {
                        Text(
                            text = stringResource(R.string.dpp_marked_count, markedCount),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onToggleDeleteMode,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.dpp_delete_pages),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                OutlinedButton(
                    onClick = onStartBlankAnalysis,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.dpp_blank_pages),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (canUndo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.dpp_deleted_message, undoCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onUndo) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.dpp_undo),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailItem(
    viewModel: DeletePagesViewModel,
    originalPageNumber: Int,
    position: Int,
    selected: Boolean,
    marked: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
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
                selected && marked -> MaterialTheme.colorScheme.error
                selected -> MaterialTheme.colorScheme.primary
                else -> Color.LightGray
            }
            Box {
                Card(
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
                    modifier = Modifier.combinedClickable(
                        onClick = onToggle,
                        onLongClick = onToggle,
                    ),
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
                                text = stringResource(R.string.dpp_preview_failed),
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
                                .align(Alignment.BottomStart)
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
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp),
                        ) {
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(26.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.dpp_delete_page),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        if (marked) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dpp_position, position + 1),
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
                text = stringResource(R.string.dpp_signatures_note),
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
    state: DeletePagesUiState,
    onNameChange: (String) -> Unit,
    onPasswordEnabledChange: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onGeneratePassword: () -> Unit,
    onToggleCloudUpload: () -> Unit,
    onExport: () -> Unit,
    context: Context,
    onShare: (List<DeletePagesResult>) -> Unit,
    onOpen: (List<DeletePagesResult>) -> Unit,
) {
    SectionTitle(stringResource(R.string.dpp_output_name_label))
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
            Text(stringResource(R.string.dpp_export))
        } else {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.dpp_export), fontWeight = FontWeight.SemiBold)
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
    result: DeletePagesResult,
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
                    text = stringResource(R.string.dpp_success),
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
            when {
                result.cloudUploadSuccess == false -> Text(
                    text = result.cloudUploadError
                        ?: stringResource(R.string.cloud_error_upload),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                result.cloudUploadSuccess == true -> Text(
                    text = stringResource(R.string.tools_cloud_uploaded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
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
                        text = stringResource(R.string.dpp_password_toggle),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.dpp_password_hint),
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
                    label = stringResource(R.string.dpp_password_label),
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
                    text = stringResource(R.string.dpp_cloud_upload_desc),
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

/** Progress dialog shown while the blank-page analysis is running. */
@Composable
private fun BlankAnalysisDialog(
    progress: Float,
    label: String,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                stringResource(R.string.dpp_blank_pages),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(
                    stringResource(R.string.dpp_blank_cancel),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        shape = RoundedCornerShape(28.dp),
    )
}

/**
 * Result of the blank-page analysis: every detected page with a small
 * thumbnail, its status and a checkbox to keep or unmark it. The final action
 * "Eliminar seleccionadas" deletes exactly the still-checked pages.
 */
@Composable
private fun BlankResultDialog(
    viewModel: DeletePagesViewModel,
    result: List<BlankPageInfo>,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by remember(result) { mutableStateOf(result.map { it.originalPageNumber }.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.dpp_blank_result_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (result.isEmpty()) {
                    Text(stringResource(R.string.dpp_blank_none))
                } else {
                    Text(
                        text = stringResource(R.string.dpp_blank_result_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 340.dp),
                    ) {
                        items(result, key = { it.originalPageNumber }) { info ->
                            BlankResultRow(
                                viewModel = viewModel,
                                info = info,
                                checked = info.originalPageNumber in checked,
                                onToggle = {
                                    checked = if (info.originalPageNumber in checked) {
                                        checked - info.originalPageNumber
                                    } else {
                                        checked + info.originalPageNumber
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(checked) },
                enabled = result.isNotEmpty() && checked.isNotEmpty(),
            ) {
                Text(
                    stringResource(R.string.dpp_blank_delete_selected),
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

/** One detected page in the result dialog: checkbox + small thumbnail + status. */
@Composable
private fun BlankResultRow(
    viewModel: DeletePagesViewModel,
    info: BlankPageInfo,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(12.dp),
            ),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        BlankThumbnail(viewModel = viewModel, originalPageNumber = info.originalPageNumber)
        Spacer(Modifier.width(12.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(R.string.dpp_blank_page_label, info.originalPageNumber),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val isBlank = info.status == BlankPageStatus.BLANK
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isBlank) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                },
                contentColor = if (isBlank) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
            ) {
                Text(
                    text = stringResource(
                        if (isBlank) R.string.dpp_blank_status_blank
                        else R.string.dpp_blank_status_likely
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Small thumbnail rendered for one page inside the result dialog. */
@Composable
private fun BlankThumbnail(
    viewModel: DeletePagesViewModel,
    originalPageNumber: Int,
) {
    var bitmap by remember(originalPageNumber) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(originalPageNumber) { mutableStateOf(false) }

    LaunchedEffect(originalPageNumber) {
        val rendered = viewModel.renderPageForPreview(originalPageNumber - 1, 72)
        if (rendered == null) {
            failed = true
        } else {
            bitmap = rendered
        }
    }
    DisposableEffect(originalPageNumber) {
        onDispose { bitmap?.recycle() }
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 48.dp, height = 64.dp),
            )

            failed -> Box(
                modifier = Modifier.size(width = 48.dp, height = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.dpp_preview_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(4.dp),
                )
            }

            else -> Box(
                modifier = Modifier.size(width = 48.dp, height = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}
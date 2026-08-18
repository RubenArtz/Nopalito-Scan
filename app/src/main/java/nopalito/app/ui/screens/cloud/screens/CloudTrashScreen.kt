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

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nopalito.app.R
import nopalito.app.ui.*
import nopalito.app.ui.components.AnimatedCountdownSnackbar
import nopalito.app.ui.components.GradientHeroAction
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.screens.cloud.data.ApiException
import nopalito.app.ui.screens.cloud.data.CloudConversionRepository
import nopalito.app.ui.screens.cloud.model.CloudFile
import nopalito.app.ui.screens.cloud.viewmodel.CloudTrashViewModel
import java.io.File

@Composable
fun CloudTrashScreen(
    viewModel: CloudTrashViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Sync when the screen opens and every time the app returns to the
    // foreground. LifecycleResumeEffect also runs while RESUMED (first
    // composition) and never while backgrounded.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // Single adaptive countdown for the whole screen (one timer, not one per
    // file). When a local deadline crosses zero we just re-sync; the server is
    // the only one that permanently deletes.
    val deadlines = remember(state.files) {
        state.files.mapNotNull { file ->
            isoToEpochMillis(file.scheduledDeletionAt)?.let { file.id to it }
        }.toMap()
    }
    val now by rememberTrashNow(deadlines, onDeadlineReached = { viewModel.refresh() })

    // Full-screen preview overlay
    if (state.previewFile != null) {
        TrashFullScreenPreview(
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
        topBar = {
            if (inSelection) {
                GradientHeroHeader(
                    title = stringResource(R.string.cloud_n_selected, state.selectedIds.size),
                    onBack = { viewModel.clearSelection() },
                    actions = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GradientHeroAction(
                                icon = Icons.Default.SelectAll,
                                contentDescription = stringResource(R.string.cloud_select_all),
                                onClick = { viewModel.selectAll() },
                            )
                        }
                    },
                )
            } else {
                TrashHeader(
                    onBack = onBack,
                    onRefresh = { viewModel.refresh() },
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
                            onClick = { viewModel.batchRestore() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cloud_restore_n, state.selectedIds.size))
                        }
                        Button(
                            onClick = { viewModel.batchPermanentDelete() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cloud_delete_permanent_n, state.selectedIds.size))
                        }
                    }
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.errorMessage != null && state.files.isEmpty() -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline, null,
                            Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            state.errorMessage ?: stringResource(R.string.error_unknown),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.refresh() }) { Text(stringResource(R.string.cloud_retry)) }
                    }
                }

                state.files.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = null,
                                modifier = Modifier.size(42.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.cloud_trash_empty),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.cloud_trash_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.files, key = { it.id }) { file ->
                            TrashFileItem(
                                file = file,
                                now = now,
                                isSelected = file.id in state.selectedIds,
                                isRestoring = state.restoringId == file.id,
                                isDeleting = state.deletingPermanentId == file.id,
                                downloadForCache = viewModel::downloadForCache,
                                onClick = { viewModel.previewFile(file) },
                                onToggleSelection = { viewModel.toggleSelection(file.id) },
                                onRestore = { viewModel.restoreFile(file.id) },
                                onPermanentDelete = { viewModel.permanentlyDelete(file.id) },
                                onDownload = { viewModel.downloadFile(file) },
                                onRename = { viewModel.startRename(file) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Rename Dialog
    if (state.renameDialogFile != null) {
        TrashRenameDialog(
            currentName = state.renameName,
            isSaving = state.isRenaming,
            onNameChange = { viewModel.updateRenameName(it) },
            onConfirm = { viewModel.confirmRename() },
            onDismiss = { viewModel.cancelRename() }
        )
    }

    // Snackbar (custom pretty variant with a 10 s countdown bar; shown with
    // Indefinite so the countdown composable drives the dismissal timing).
    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Indefinite)
            viewModel.clearSnackbar()
        }
    }
}

// ─────────────────────────────────────────────
// TRASH FILE ITEM
// ─────────────────────────────────────────────

/** Small pill showing which trash the item belongs to (Cloud normal / QR). */
@Composable
fun TrashTypeBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TrashFileItem(
    file: CloudFile,
    now: Long,
    isSelected: Boolean,
    isRestoring: Boolean,
    isDeleting: Boolean,
    downloadForCache: suspend (CloudFile) -> Result<File>,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit
) {
    // Thumbnail bitmap
    var thumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loadingThumb by remember { mutableStateOf(false) }

    val canThumb = file.mimeType?.startsWith("image/") == true ||
            file.mimeType == "application/pdf" ||
            file.originalName.endsWith(".pdf")

    LaunchedEffect(file.id) {
        if (canThumb) {
            loadingThumb = true
            thumb = withContext(Dispatchers.IO) {
                try {
                    val cached = downloadForCache(file).getOrNull() ?: return@withContext null
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

                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
            loadingThumb = false
        }
    }

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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
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
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.size(40.dp)
            )

            // Clickable area for preview
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onClick() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Thumbnail
                Box(
                    Modifier
                        .size(56.dp)
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
                    when {
                        loadingThumb -> CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )

                        thumb != null -> Image(
                            bitmap = thumb!!.asImageBitmap(), null,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                        )

                        else -> Icon(
                            fallbackIcon, null, Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // File info
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        file.originalName, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                    // ── Trash metadata: type badge + info button (popup) ──
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrashTypeBadge(
                            label = if (file.trashType == "QR_PAPELERA") {
                                stringResource(R.string.cloud_trash_type_qr)
                            } else {
                                stringResource(R.string.cloud_trash_type_cloud)
                            }
                        )
                        TrashInfoButton(
                            now = now,
                            deadlineMillis = isoToEpochMillis(file.scheduledDeletionAt),
                            trashedAtMillis = isoToEpochMillis(file.trashedAt ?: file.deletedAt),
                            trashSource = file.trashSource,
                        )
                    }
                }
            }

            // Single overflow menu keeps the row compact; all actions are
            // one tap away instead of four always-visible buttons.
            Box {
                if (isRestoring || isDeleting) {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    var menuOpen by remember { mutableStateOf(false) }
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
                            text = { Text(stringResource(R.string.cloud_restore)) },
                            leadingIcon = { Icon(Icons.Default.Restore, null) },
                            onClick = { menuOpen = false; onRestore() },
                        )
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
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.cloud_delete_permanent),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DeleteForever, null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { menuOpen = false; onPermanentDelete() },
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// FULL-SCREEN PREVIEW (Trash version)
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashFullScreenPreview(
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
                    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                    LaunchedEffect(cachedFile) {
                        bitmap = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(
                                cachedFile.absolutePath,
                                BitmapFactory.Options().apply { inScaled = false })
                        }
                    }
                    bitmap?.let { TrashZoomableImage(bmp = it) }
                        ?: Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                }

                cachedFile != null && (file.mimeType == "application/pdf" || file.originalName.endsWith(
                    ".pdf"
                )) -> {
                    var pdfPages by remember { mutableStateOf<List<android.graphics.Bitmap>?>(null) }
                    var pdfEncrypted by remember { mutableStateOf(false) }
                    var zoomPage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                    LaunchedEffect(cachedFile) {
                        pdfEncrypted = withContext(Dispatchers.IO) { isPdfEncrypted(cachedFile) }
                        if (!pdfEncrypted) {
                            pdfPages = withContext(Dispatchers.IO) { renderPdfPreviewPages(cachedFile) }
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
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(pages) { _, page ->
                                Image(
                                    bitmap = page.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { zoomPage = page },
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                    zoomPage?.let { page ->
                        ZoomableBitmapDialog(
                            bitmap = page,
                            onDismiss = { zoomPage = null },
                        )
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
// PDF PREVIEW — bounded, scaled pages
// ─────────────────────────────────────────────

private const val MAX_PDF_PREVIEW_PAGES = 30
private const val MAX_PDF_PREVIEW_DIM = 1400f

/** Renders the first pages of a PDF scaled down, so a huge document never blocks the app. */
private fun renderPdfPreviewPages(cachedFile: File): List<android.graphics.Bitmap> = try {
    val pfd = ParcelFileDescriptor.open(cachedFile, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)
    val pages = (0 until minOf(renderer.pageCount, MAX_PDF_PREVIEW_PAGES)).map { index ->
        val page = renderer.openPage(index)
        val scale = minOf(1f, MAX_PDF_PREVIEW_DIM / maxOf(page.width, page.height))
        val bmp = createBitmap(
            maxOf(1, (page.width * scale).toInt()),
            maxOf(1, (page.height * scale).toInt())
        )
        bmp.eraseColor(android.graphics.Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        bmp
    }
    renderer.close()
    pfd.close()
    pages
} catch (_: Exception) {
    emptyList()
}

@Composable
private fun TrashZoomableImage(bmp: android.graphics.Bitmap) {
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
// TRASH HEADER — gradient banner
// ─────────────────────────────────────────────

@Composable
private fun TrashHeader(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary,
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrashHeaderAction(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                description = stringResource(R.string.back),
                onClick = onBack,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cloud_trash_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = stringResource(R.string.cloud_trash_subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            TrashHeaderAction(
                icon = Icons.Default.Refresh,
                description = stringResource(R.string.cloud_refresh),
                onClick = onRefresh,
            )
        }
    }
}

@Composable
private fun TrashHeaderAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────
// RENAME DIALOG
// ─────────────────────────────────────────────

@Composable
private fun TrashRenameDialog(
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
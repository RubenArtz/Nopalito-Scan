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

package nopalito.app.ui.screens.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nopalito.app.R
import nopalito.app.domain.ocr.TextExtractionState
import nopalito.app.ui.Navigation
import nopalito.app.ui.components.ConfirmationDialog
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.components.ImportedSignatureEditor
import nopalito.app.ui.components.MyScaffold
import nopalito.app.ui.components.SignatureMethodSheet
import nopalito.app.ui.components.SimpleSignatureDialog
import nopalito.app.ui.components.TopActionButtons
import nopalito.app.ui.components.rememberHapticManager
import nopalito.app.ui.state.DocumentUiModel
import nopalito.imageprocessing.ColorMode
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    uiState: DocumentUiState,
    navigation: Navigation,
    onExportClick: () -> Unit,
    onDeleteImage: () -> Unit,
    onRotateImage: (Boolean) -> Unit,
    onFilterSelected: (ColorMode) -> Unit = {},
    onCropClick: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onNewSession: () -> Unit = {},
    onRetakePage: () -> Unit = {},
    onExtractText: () -> Unit = {},
    onRetryTextExtraction: () -> Unit = {},
    onDismissExtractedText: () -> Unit = {},
    onPageOrderChanged: (List<String>) -> Unit = { _ -> },
    onUpdateSignature: (pageId: String, state: SignatureState, bitmap: Bitmap) -> Unit = { _, _, _ -> },
    onUpdateDateOverlay: (pageId: String, dateText: String, positionFraction: Offset) -> Unit = { _, _, _ -> },
    onUpdateSignaturePosition: (pageId: String, positionFraction: Offset) -> Unit = { _, _ -> },
    onUpdateDatePosition: (pageId: String, positionFraction: Offset) -> Unit = { _, _ -> },
    onUpdateSignatureScale: (pageId: String, scale: Float) -> Unit = { _, _ -> },
    onUpdateDateScale: (pageId: String, scale: Float) -> Unit = { _, _ -> },
    onUpdateSignatureRotation: (pageId: String, degrees: Float) -> Unit = { _, _ -> },
    onUpdateDateRotation: (pageId: String, degrees: Float) -> Unit = { _, _ -> },
    onUpdateDateStyle: (pageId: String, style: DateOverlayStyle) -> Unit = { _, _ -> },
    onDeleteSignatureOverlay: (pageId: String) -> Unit = {},
    onDeleteDateOverlay: (pageId: String) -> Unit = {},
) {
    val showDeletePageDialog = rememberSaveable { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var showSignatureMenu by remember { mutableStateOf(false) }
    var showImportedEditor by remember { mutableStateOf(false) }
    var importedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showDateEditorDialog by remember { mutableStateOf(false) }
    var selectedOverlayType by remember { mutableStateOf<OverlayType?>(null) }
    val selectedPageId = remember { mutableStateOf<String?>(null) }
    val pendingPageId = remember { mutableStateOf<String?>(null) }

    // Without this BackHandler the system back button/gesture would fall
    // through to the Activity and close the app instead of returning to the
    // camera home screen.
    BackHandler { navigation.back() }

    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext

    // Gallery picker for importing signature image
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = appContext.contentResolver.openInputStream(it)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (bmp != null) {
                importedBitmap = bmp
                showSignatureMenu = false
                showImportedEditor = true
            }
        }
    }

    // Camera capture for importing signature photo
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && tempCameraUri != null) {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = appContext.contentResolver.openInputStream(tempCameraUri!!)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (bmp != null) {
                importedBitmap = bmp
                showSignatureMenu = false
                showImportedEditor = true
            }
        }
    }

    MyScaffold(
        navigation = navigation,
        heroHeader = {
            GradientHeroHeader(
                title = stringResource(R.string.document_title),
                subtitle = stringResource(R.string.document_subtitle),
                onBack = { navigation.back() },
                actions = {
                    TopActionButtons(
                        navigation = navigation,
                        tint = Color.White,
                        circleColor = Color.White.copy(alpha = 0.22f),
                    )
                },
            )
        },
        bottomBar = {
            BottomBar(
                onExportClick = onExportClick,
                onAddPageClick = navigation.toCameraScreen,
                onNewSession = onNewSession,
            )
        },
    ) { modifier ->
        DocumentPreview(
            uiState = uiState,
            onDeleteImage = { showDeletePageDialog.value = true },
            onRotateImage = onRotateImage,
            onFilterSelected = onFilterSelected,
            onCropClick = onCropClick,
            onRetakePage = onRetakePage,
            onExtractText = onExtractText,
            onPageSelected = onPageSelected,
            onAddPageClick = navigation.toCameraScreen,
            onPageOrderChanged = onPageOrderChanged,
            onSignatureClick = { pageId ->
                pendingPageId.value = pageId
                showSignatureMenu = true
            },
            onReEditSignature = { pid, sigState ->
                if (sigState.source == SignatureSource.IMPORTED) {
                    importedBitmap = uiState.pageOverlays[pid]?.signatureBitmap?.copy(
                        Bitmap.Config.ARGB_8888,
                        false
                    )
                    pendingPageId.value = pid
                    showImportedEditor = true
                } else {
                    pendingPageId.value = pid
                    showSignatureDialog = true
                }
            },
            onDateOverlayClick = { pageId ->
                pendingPageId.value = pageId
                showDateEditorDialog = true
            },
            onSignatureMoved = { pageId, fraction ->
                onUpdateSignaturePosition(pageId, fraction)
            },
            onDateMoved = { pageId, fraction ->
                onUpdateDatePosition(pageId, fraction)
            },
            onSignatureScaleChanged = { pageId, scale ->
                onUpdateSignatureScale(pageId, scale)
            },
            onDateScaleChanged = { pageId, scale ->
                onUpdateDateScale(pageId, scale)
            },
            onSignatureRotationChanged = { pageId, degrees ->
                onUpdateSignatureRotation(pageId, degrees)
            },
            onDateRotationChanged = { pageId, degrees ->
                onUpdateDateRotation(pageId, degrees)
            },
            selectedOverlayType = selectedOverlayType,
            onOverlaySelected = { overlayType, pageId ->
                selectedOverlayType = overlayType
                selectedPageId.value = pageId
            },
            onOverlayDeselected = {
                selectedOverlayType = null
            },
            onDateStyleClick = { pageId ->
                pendingPageId.value = pageId
                selectedPageId.value = pageId
                showDateEditorDialog = true
            },
            onDeleteSignatureOverlay = { pid ->
                onDeleteSignatureOverlay(pid); selectedOverlayType = null
            },
            onDeleteDateOverlay = { pid -> onDeleteDateOverlay(pid); selectedOverlayType = null },
            modifier = modifier
        )
        if (showDeletePageDialog.value) {
            ConfirmationDialog(
                title = stringResource(R.string.delete_page),
                message = stringResource(R.string.delete_page_warning),
                showDialog = showDeletePageDialog
            ) { onDeleteImage() }
        }

        // Professional signature method bottom sheet
        if (showSignatureMenu && pendingPageId.value != null) {
            ModalBottomSheet(
                onDismissRequest = { showSignatureMenu = false },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                dragHandle = null,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                SignatureMethodSheet(
                    onDrawSignature = {
                        showSignatureMenu = false
                        showSignatureDialog = true
                    },
                    onImportFromGallery = {
                        showSignatureMenu = false
                        galleryLauncher.launch("image/*")
                    },
                    onTakePhoto = {
                        showSignatureMenu = false
                        val uri = nopalito.app.ui.createTempImageUri(appContext)
                        tempCameraUri = uri
                        cameraLauncher.launch(uri)
                    },
                    onDismiss = { showSignatureMenu = false },
                )
            }
        }

        // Signature simple dialog - draw only
        if (showSignatureDialog && pendingPageId.value != null) {
            val pid = pendingPageId.value!!
            val existingState = uiState.currentPage?.overlays?.signatureState
            SimpleSignatureDialog(
                pageId = pid,
                onDismiss = { showSignatureDialog = false },
                onConfirmSignature = { pageId, state, bitmap ->
                    val finalState = if (existingState == null) {
                        centeredSignatureState(uiState.currentPage?.bitmap, bitmap, state)
                    } else state
                    onUpdateSignature(pageId, finalState, bitmap)
                    selectedOverlayType = OverlayType.SIGNATURE
                    selectedPageId.value = pageId
                    showSignatureDialog = false
                },
                editingState = existingState,
            )
        }

        // Imported signature editor — full-screen bottom sheet
        if (showImportedEditor && importedBitmap != null && pendingPageId.value != null) {
            val pid = pendingPageId.value!!
            ModalBottomSheet(
                onDismissRequest = {
                    showImportedEditor = false
                    importedBitmap?.recycle()
                    importedBitmap = null
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                dragHandle = null,
                sheetGesturesEnabled = false,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                ImportedSignatureEditor(
                    sourceBitmap = importedBitmap!!,
                    initialState = uiState.pageOverlays[pid]?.signatureState,
                    onConfirm = { state, bitmap ->
                        val initial = uiState.pageOverlays[pid]?.signatureState
                        val finalState = if (initial == null) {
                            centeredSignatureState(uiState.currentPage?.bitmap, bitmap, state)
                        } else state
                        onUpdateSignature(pid, finalState, bitmap)
                        selectedOverlayType = OverlayType.SIGNATURE
                        selectedPageId.value = pid
                        showImportedEditor = false
                        importedBitmap = null
                    },
                    onBack = {
                        showImportedEditor = false
                        importedBitmap?.recycle()
                        importedBitmap = null
                    }
                )
            }
        }

        // Date editor dialog (unified picker + style)
        // ponytail: no auto-select after placing date overlay — avoids jarring selection frame
        if (showDateEditorDialog && pendingPageId.value != null) {
            DateEditorDialog(
                currentStyle = uiState.currentPage?.overlays?.dateStyle ?: DateOverlayStyle(),
                onDismiss = { showDateEditorDialog = false },
                onConfirm = { dateText, style ->
                    val pid = pendingPageId.value ?: return@DateEditorDialog
                    val currentPos = uiState.currentPage?.overlays?.datePositionFraction
                        ?: centeredDatePosition(uiState.currentPage?.bitmap, dateText, style)
                    onUpdateDateOverlay(pid, dateText, currentPos)
                    onUpdateDateStyle(pid, style)
                    showDateEditorDialog = false
                }
            )
        }

        // Extracted-text sheet: success (selectable text), empty and error
        // states. The scanning state renders as an overlay over the preview.
        val extraction = uiState.textExtraction
        if (extraction is TextExtractionState.Success ||
            extraction is TextExtractionState.NoText ||
            extraction is TextExtractionState.Error
        ) {
            ModalBottomSheet(
                onDismissRequest = { onDismissExtractedText() },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                ExtractedTextSheet(
                    state = extraction,
                    pageNumber = uiState.currentPageIndex + 1,
                    onRetry = onRetryTextExtraction,
                    onClose = onDismissExtractedText,
                )
            }
        }
    }
}

@Composable
private fun DocumentPreview(
    uiState: DocumentUiState,
    onDeleteImage: () -> Unit,
    onRotateImage: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onFilterSelected: (ColorMode) -> Unit = { _ -> },
    onCropClick: () -> Unit,
    onRetakePage: () -> Unit = {},
    onExtractText: () -> Unit = {},
    onPageSelected: (Int) -> Unit = {},
    onAddPageClick: () -> Unit = {},
    onPageOrderChanged: (List<String>) -> Unit = { _ -> },
    onSignatureClick: (pageId: String) -> Unit = { _ -> },
    onReEditSignature: (pageId: String, SignatureState) -> Unit = { _, _ -> },
    onDateOverlayClick: (pageId: String) -> Unit = { _ -> },
    onSignatureMoved: (pageId: String, fraction: Offset) -> Unit = { _, _ -> },
    onDateMoved: (pageId: String, fraction: Offset) -> Unit = { _, _ -> },
    onSignatureScaleChanged: (pageId: String, scale: Float) -> Unit = { _, _ -> },
    onDateScaleChanged: (pageId: String, scale: Float) -> Unit = { _, _ -> },
    onSignatureRotationChanged: (pageId: String, degrees: Float) -> Unit = { _, _ -> },
    onDateRotationChanged: (pageId: String, degrees: Float) -> Unit = { _, _ -> },
    selectedOverlayType: OverlayType? = null,
    onOverlaySelected: (OverlayType, String) -> Unit = { _, _ -> },
    onOverlayDeselected: () -> Unit = {},
    onDateStyleClick: (String) -> Unit = {},
    onDeleteSignatureOverlay: (String) -> Unit = {},
    onDeleteDateOverlay: (String) -> Unit = {},
) {
    val currentPageIndex = uiState.currentPageIndex
    val document = uiState.document
    val pageId = uiState.currentPage?.key?.pageId
    val pageKey = uiState.currentPage?.key
    // One-time swipe hint: reappears only when the page count changes (e.g. a
    // second photo is added and swiping becomes possible).
    var showSwipeHint by rememberSaveable(document.pageCount()) { mutableStateOf(true) }
    var showReorderOverlay by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        var imageContainerSize by remember { mutableStateOf(IntSize.Zero) }
        // Pages the user zoomed (by stable page id): while the visible page
        // is zoomed, the pager swipe locks so pan gestures don't flip pages.
        // Entries are removed when their item leaves composition, so the lock
        // can never get stuck on a disposed page.
        val zoomedPages = remember { mutableStateMapOf<String, Boolean>() }
        val isCurrentPageZoomed = pageId?.let { zoomedPages[it] } == true
        val pageCount = document.pageCount()
        // Extra trailing page hosts the "add page" tile.
        val pagerState = rememberPagerState(
            initialPage = currentPageIndex.coerceIn(0, pageCount.coerceAtLeast(0)),
            pageCount = { pageCount + 1 },
        )
        LaunchedEffect(currentPageIndex, pageCount) {
            val target = currentPageIndex.coerceIn(0, pageCount)
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        }
        // Latest selection/count for the long-lived swipe collector below:
        // it only restarts when the pager itself changes, so it must never
        // capture `currentPageIndex`/`pageCount` directly (stale closure).
        val latestPageIndex by rememberUpdatedState(currentPageIndex)
        val latestPageCount by rememberUpdatedState(pageCount)
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                // Without the `latest*` holders, swiping back (e.g. 2 -> 1)
                // would compare against the initially captured index and never
                // reach the ViewModel: the counter would stick at "2" and the
                // page would fall back to its blurry low-res thumbnail.
                if (page < latestPageCount && page != latestPageIndex) {
                    showSwipeHint = false
                    onPageSelected(page)
                }
            }
        }
        // Auto-hide the hint after a few seconds so it never nags.
        LaunchedEffect(showSwipeHint) {
            if (showSwipeHint) {
                delay(SWIPE_HINT_TIMEOUT_MS.milliseconds)
                showSwipeHint = false
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Swipe is disabled while an overlay is selected so overlay drags win,
                // and while the visible page is zoomed so pan gestures don't flip pages.
                userScrollEnabled = selectedOverlayType == null && !isCurrentPageZoomed,
                beyondViewportPageCount = 1,
                // Stable key by page id (never the position): after a reorder
                // each page keeps its own composition instead of inheriting
                // the previous occupant's remembered bitmaps/state.
                key = { page -> document.pages.getOrNull(page)?.key?.pageId ?: "add-page" },
            ) { index ->
                if (index == pageCount) {
                    AddPageTile(onClick = onAddPageClick)
                    return@HorizontalPager
                }
                // Full-resolution bitmap for the current page, thumbnail while swiping.
                // The thumbnail is resolved by stable page id (not by index) so a
                // reorder can never paint the previous occupant's image here.
                val fullBitmap =
                    if (index == currentPageIndex) uiState.currentPage?.bitmap else null
                val pageThumb = document.pages.getOrNull(index)
                val thumbBitmap = remember(pageThumb) { pageThumb?.thumbnail?.toBitmap() }
                val bitmap = fullBitmap ?: thumbBitmap
                // Pinch + double-tap zoom, kept per page (the pager is keyed by
                // page id, so each page's zoom follows it across reorders).
                // The whole frame (photo + overlays) scales together, so hit
                // testing maps back to layout coordinates and signature/date
                // drags keep working while zoomed.
                val pageIdAtIndex = pageThumb?.key?.pageId
                var zoomScale by remember { mutableFloatStateOf(1f) }
                var zoomOffset by remember { mutableStateOf(Offset.Zero) }
                DisposableEffect(pageIdAtIndex) {
                    onDispose { pageIdAtIndex?.let { zoomedPages.remove(it) } }
                }
                // Only the visible page is zoomable; neighbors stay swipeable.
                val zoomGesturesEnabled = index == currentPageIndex
                @Suppress("DEPRECATION") val zoomTransformState = rememberTransformableState { zoomChange, panChange, _ ->
                    val newScale = (zoomScale * zoomChange).coerceIn(1f, EDITOR_MAX_ZOOM)
                    zoomScale = newScale
                    zoomOffset = if (newScale <= 1f) {
                        Offset.Zero
                    } else {
                        (zoomOffset + panChange * newScale)
                            .coercedToBounds(imageContainerSize, newScale)
                    }
                    // Write only on zoomed/not-zoomed transitions to avoid
                    // recomposing the preview on every gesture frame.
                    if (pageIdAtIndex != null) {
                        val zoomed = newScale > 1f
                        if (zoomedPages[pageIdAtIndex] == true && !zoomed) {
                            zoomedPages.remove(pageIdAtIndex)
                        } else if (zoomed) {
                            zoomedPages[pageIdAtIndex] = true
                        }
                    }
                }
                if (bitmap != null && (index != currentPageIndex || pageKey != null)) {
                    val imageBitmap = bitmap.asImageBitmap()

                    Box(
                        modifier = Modifier
                            .fillMaxSize(0.95f)
                            .align(Alignment.Center)
                            .padding(8.dp)
                            .onSizeChanged { imageContainerSize = it }
                            .graphicsLayer(
                                scaleX = zoomScale,
                                scaleY = zoomScale,
                                translationX = zoomOffset.x,
                                translationY = zoomOffset.y,
                                clip = true, // keeps zoomed content (and overlays) inside the frame
                            )
                            // canPan only when zoomed: at 1x single-finger drags
                            // fall through to the pager swipe.
                            .transformable(
                                state = zoomTransformState,
                                canPan = { zoomScale > 1f },
                                enabled = zoomGesturesEnabled,
                            )
                            .pointerInput(pageIdAtIndex, zoomGesturesEnabled) {
                                detectTapGestures(
                                    onDoubleTap = { tap ->
                                        if (!zoomGesturesEnabled || pageIdAtIndex == null) {
                                            return@detectTapGestures
                                        }
                                        if (zoomScale > 1f) {
                                            zoomScale = 1f
                                            zoomOffset = Offset.Zero
                                            zoomedPages.remove(pageIdAtIndex)
                                        } else {
                                            val center = Offset(
                                                size.width / 2f,
                                                size.height / 2f,
                                            )
                                            zoomScale = EDITOR_DOUBLE_TAP_ZOOM
                                            zoomOffset = ((center - tap) * EDITOR_DOUBLE_TAP_ZOOM)
                                                .coercedToBounds(size, EDITOR_DOUBLE_TAP_ZOOM)
                                            zoomedPages[pageIdAtIndex] = true
                                        }
                                    }
                                )
                            }
                    ) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .align(Alignment.Center)
                        )
                        // Overlays are only interactive on the current page.
                        val currentPage = uiState.currentPage
                        if (index == currentPageIndex && currentPage != null) {
                            // Overlays
                            val overlays = currentPage.overlays
                            OverlayLayer(
                                overlays = overlays,
                                pageId = pageId,
                                containerSize = imageContainerSize,
                                imageSize = IntSize(bitmap.width, bitmap.height),
                                onSignatureMoved = onSignatureMoved,
                                onDateMoved = onDateMoved,
                                onSignatureScaleChanged = { pid, scale ->
                                    onSignatureScaleChanged(
                                        pid,
                                        scale
                                    )
                                },
                                onDateScaleChanged = { pid, scale ->
                                    onDateScaleChanged(
                                        pid,
                                        scale
                                    )
                                },
                                onSignatureRotationChanged = { pid, degrees ->
                                    onSignatureRotationChanged(
                                        pid,
                                        degrees
                                    )
                                },
                                onDateRotationChanged = { pid, degrees ->
                                    onDateRotationChanged(
                                        pid,
                                        degrees
                                    )
                                },
                                selectedOverlayType = selectedOverlayType,
                                onOverlaySelected = { type ->
                                    if (pageId != null) onOverlaySelected(
                                        type,
                                        pageId
                                    )
                                },
                                onOverlayDeselected = onOverlayDeselected,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Floating contextual toolbar
                            if (selectedOverlayType != null && pageId != null) {
                                val pid = pageId
                                // Nudges the selected overlay by a small fraction of
                                // the page, so it can be fine-tuned without dragging.
                                val nudgeStep = 0.02f
                                fun nudgeSelected(dx: Float, dy: Float) {
                                    val overlays = currentPage.overlays
                                    when (selectedOverlayType) {
                                        OverlayType.SIGNATURE -> {
                                            val position =
                                                overlays.signaturePositionFraction ?: return
                                            onSignatureMoved(
                                                pid,
                                                Offset(
                                                    (position.x + dx).coerceIn(0f, 0.98f),
                                                    (position.y + dy).coerceIn(0f, 0.98f),
                                                )
                                            )
                                        }

                                        OverlayType.DATE -> {
                                            val position = overlays.datePositionFraction ?: return
                                            onDateMoved(
                                                pid,
                                                Offset(
                                                    (position.x + dx).coerceIn(0f, 0.98f),
                                                    (position.y + dy).coerceIn(0f, 0.98f),
                                                )
                                            )
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 8.dp),
                                ) {
                                    FloatingOverlayToolbar(
                                        overlayType = selectedOverlayType,
                                        onDelete = {
                                            when (selectedOverlayType) {
                                                OverlayType.SIGNATURE -> onDeleteSignatureOverlay(
                                                    pid
                                                )

                                                OverlayType.DATE -> onDeleteDateOverlay(pid)
                                            }
                                            onOverlayDeselected()
                                        },
                                        onEditStyle = { if (pid.isNotEmpty()) onDateStyleClick(pid) },
                                        onEditSignature = {
                                            val sigState = currentPage.overlays.signatureState
                                            if (sigState != null) {
                                                onReEditSignature(pid, sigState)
                                            } else {
                                                onSignatureClick(pid)
                                            }
                                        },
                                        onZoomIn = {
                                            val overlays = currentPage.overlays
                                            if (selectedOverlayType == OverlayType.SIGNATURE) {
                                                onSignatureScaleChanged(
                                                    pid,
                                                    (overlays.signatureScale + 0.1f).coerceIn(
                                                        SignatureState.MIN_OVERLAY_SCALE,
                                                        SignatureState.MAX_OVERLAY_SCALE,
                                                    )
                                                )
                                            } else if (selectedOverlayType == OverlayType.DATE) {
                                                onDateScaleChanged(pid, overlays.dateScale + 0.1f)
                                            }
                                        },
                                        onZoomOut = {
                                            val overlays = currentPage.overlays
                                            if (selectedOverlayType == OverlayType.SIGNATURE) {
                                                onSignatureScaleChanged(
                                                    pid,
                                                    (overlays.signatureScale - 0.1f).coerceIn(
                                                        SignatureState.MIN_OVERLAY_SCALE,
                                                        SignatureState.MAX_OVERLAY_SCALE,
                                                    )
                                                )
                                            } else if (selectedOverlayType == OverlayType.DATE) {
                                                onDateScaleChanged(pid, overlays.dateScale - 0.1f)
                                            }
                                        },
                                        onMoveUp = { nudgeSelected(0f, -nudgeStep) },
                                        onMoveDown = { nudgeSelected(0f, nudgeStep) },
                                        onMoveLeft = { nudgeSelected(-nudgeStep, 0f) },
                                        onMoveRight = { nudgeSelected(nudgeStep, 0f) },
                                        onDone = { onOverlayDeselected() },
                                    )
                                }
                            }
                        }
                    }
                }
            } // HorizontalPager
            if (uiState.currentPage?.isLoading ?: false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            if (uiState.textExtraction is TextExtractionState.Scanning) {
                TextScanOverlay(modifier = Modifier.fillMaxSize())
            }
            if (pageCount > 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                ) {
                    PageCounter(
                        index = currentPageIndex,
                        count = pageCount,
                        onPrevious = { onPageSelected((currentPageIndex - 1).coerceAtLeast(0)) },
                        onNext = {
                            onPageSelected((currentPageIndex + 1).coerceAtMost(pageCount - 1))
                        },
                    )
                    // Subtle swipe hint: same pill style as the counter, gone on
                    // first swipe, tap, or timeout — and only with 2+ photos.
                    AnimatedVisibility(
                        visible = showSwipeHint && pageCount > 1,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.editor_swipe_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            maxLines = 1,
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .clickable { showSwipeHint = false }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        // Horizontal filter strip: each cell previews its own filter on top of
        // a NEUTRAL base rendering — never on top of the selected filter
        // (which would make every cell inherit it, e.g. all-gray after Magia).
        val currentBitmap = uiState.currentPage?.bitmap
        val previewBase = uiState.currentPage?.filterPreviewBase ?: currentBitmap
        if (previewBase != null) {
            FilterStrip(
                bitmap = previewBase,
                selected = uiState.currentPage?.colorMode ?: ColorMode.COLOR,
                onFilterSelected = onFilterSelected,
            )
        }
        // Single toolbar: every action in one scrollable row
        EditorToolBar(
            onRetake = onRetakePage,
            onRotateLeft = { onRotateImage(false) },
            onRotateRight = { onRotateImage(true) },
            onCrop = onCropClick,
            onSign = { pageId?.let { onSignatureClick(it) } },
            onDate = { pageId?.let { onDateOverlayClick(it) } },
            onExtractText = onExtractText,
            onReorder = { showReorderOverlay = true },
            onDelete = onDeleteImage,
            signEnabled = pageId != null,
        )
    }

    if (showReorderOverlay) {
        ReorderOverlay(
            document = document,
            onPageOrderChanged = onPageOrderChanged,
            onDismiss = { showReorderOverlay = false },
        )
    }
}

// Pager counter + add-page tile

/** How long the swipe hint stays visible before auto-hiding. */
private const val SWIPE_HINT_TIMEOUT_MS = 5000L

/** Maximum pinch-zoom on the editor photo. */
private const val EDITOR_MAX_ZOOM = 4f

/** Zoom level toggled by double-tap on the editor photo. */
private const val EDITOR_DOUBLE_TAP_ZOOM = 2.5f

/**
 * Clamps a zoom pan offset so the scaled photo cannot leave the frame:
 * at scale `s` the content overflows by `size * (s - 1) / 2` on each side.
 */
private fun Offset.coercedToBounds(container: IntSize, scale: Float): Offset {
    val maxX = (container.width * (scale - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (container.height * (scale - 1f) / 2f).coerceAtLeast(0f)
    return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
}

/** Page indicator with swipe arrows, like CamScanner ("‹ 1/3 ›"). */
@Composable
private fun PageCounter(
    index: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = index > 0,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
        Text(
            "${index + 1} / $count",
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelLarge,
        )
        IconButton(
            onClick = onNext,
            enabled = index < count - 1,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
    }
}

/** Trailing pager page with a dashed drop zone to capture another page. */
@Composable
private fun AddPageTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize(0.9f)
                .drawBehind {
                    val strokeWidth = 2.dp.toPx()
                    val inset = strokeWidth / 2f
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        cornerRadius = CornerRadius(16.dp.toPx()),
                        style = Stroke(
                            width = strokeWidth,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(12.dp.toPx(), 10.dp.toPx()),
                            ),
                        ),
                    )
                }
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(24.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(R.string.add_page),
                color = accent,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

// Filter strip + single toolbar

/** Model for each entry of the filter strip (mode + label). The thumbnail
 *  reuses the page bitmap with a ColorMatrix preview. */
private data class FilterItem(
    val mode: ColorMode,
    val labelRes: Int,
)

private fun filterItems() = listOf(
    FilterItem(ColorMode.ORIGINAL, R.string.color_mode_original),
    FilterItem(ColorMode.LIGHTEN, R.string.color_mode_lighten),
    FilterItem(ColorMode.COLOR, R.string.color_mode_enhance),
    FilterItem(ColorMode.GRAYSCALE, R.string.color_mode_magic),
    FilterItem(ColorMode.BW, R.string.color_mode_bw),
)

/** Instant preview via ColorMatrix (no reprocessing, visual approximation only). */
private fun previewMatrix(mode: ColorMode): ColorMatrix? = when (mode) {
    ColorMode.ORIGINAL -> null
    ColorMode.LIGHTEN -> ColorMatrix(
        floatArrayOf(
            1.1f, 0f, 0f, 0f, 18f,
            0f, 1.1f, 0f, 0f, 18f,
            0f, 0f, 1.1f, 0f, 18f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    ColorMode.COLOR -> ColorMatrix().apply { setToSaturation(1.35f) }
    ColorMode.GRAYSCALE -> ColorMatrix().apply { setToSaturation(0f) }
    ColorMode.BW -> ColorMatrix(
        floatArrayOf(
            1.8f, 1.8f, 1.8f, 0f, -260f,
            1.8f, 1.8f, 1.8f, 0f, -260f,
            1.8f, 1.8f, 1.8f, 0f, -260f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
}

@Composable
fun FilterStrip(
    bitmap: Bitmap,
    selected: ColorMode,
    onFilterSelected: (ColorMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(filterItems(), key = { it.mode }) { item ->
            val isSelected = item.mode == selected
            val accent = MaterialTheme.colorScheme.primary
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) accent
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .background(
                        if (isSelected) accent.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceContainerLow
                    )
                    .clickable { if (!isSelected) onFilterSelected(item.mode) }
                    .padding(bottom = 4.dp),
            ) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = previewMatrix(item.mode)?.let { ColorFilter.colorMatrix(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp)),
                )
                Text(
                    text = stringResource(item.labelRes),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val content = tint ?: MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = content.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = content.copy(alpha = if (enabled) 1f else 0.35f),
        )
    }
}

/**
 * Single horizontal toolbar with scroll (left ↔ right):
 * Retake · Rotate · Crop · Sign · Date · Extract text · Delete.
 */
@Composable
fun EditorToolBar(
    onRetake: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onCrop: () -> Unit,
    onSign: () -> Unit,
    onDate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onExtractText: () -> Unit = {},
    onReorder: () -> Unit = {},
    signEnabled: Boolean = true,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        LazyRow(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(vertical = 4.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                ToolAction(
                    icon = Icons.Default.CameraAlt,
                    label = stringResource(R.string.retake),
                    onClick = onRetake,
                )
            }
            item {
                ToolAction(
                    icon = Icons.AutoMirrored.Filled.RotateLeft,
                    label = stringResource(R.string.rotate_left),
                    onClick = onRotateLeft,
                )
            }
            item {
                ToolAction(
                    icon = Icons.AutoMirrored.Filled.RotateRight,
                    label = stringResource(R.string.rotate_right),
                    onClick = onRotateRight,
                )
            }
            item {
                ToolAction(
                    icon = Icons.Default.Crop,
                    label = stringResource(R.string.crop),
                    onClick = onCrop,
                )
            }
            item {
                ToolAction(
                    icon = Icons.Default.Edit,
                    label = stringResource(R.string.sign),
                    onClick = onSign,
                    enabled = signEnabled,
                )
            }
            item {
                ToolAction(
                    icon = Icons.Default.DateRange,
                    label = stringResource(R.string.date_label),
                    onClick = onDate,
                    enabled = signEnabled,
                )
            }
            item {
                ToolAction(
                    icon = Icons.Default.TextFields,
                    label = stringResource(R.string.ocr_extract_text),
                    onClick = onExtractText,
                    enabled = signEnabled,
                )
            }
            item {
                ToolAction(
                    icon = Icons.Default.SwapVert,
                    label = stringResource(R.string.editor_reorder),
                    onClick = onReorder,
                    enabled = signEnabled,
                )
            }
            item {
                ToolAction(
                    icon = Icons.Outlined.Delete,
                    label = stringResource(R.string.delete_page),
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * In-place reorder overlay (NOT a modal sheet): the editor dims behind a dark
 * scrim and the page thumbnails are projected in the center in a horizontal
 * strip. Long-press a thumbnail and drag it left/right to move it; the number
 * badges update live and every drop calls [onPageOrderChanged] with the
 * complete new id sequence.
 *
 * Same drag mechanism as the filmstrip (sh.calvin.reorderable). Items are
 * keyed by stable page id so thumbnails stay attached to their page while
 * dragging. The overlay sends the FULL order (not an index delta against a
 * possibly stale base), which makes rapid successive drags idempotent and
 * convergent no matter how the repository round-trips interleave.
 */
@Composable
private fun ReorderOverlay(
    document: DocumentUiModel,
    onPageOrderChanged: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val pageIds = remember(document) { document.pages.map { it.key.pageId } }
    // Local order mirrors the document but never clobbers an in-flight drag:
    // it resyncs only when the page membership changes (add/remove). A pure
    // reorder coming back from the repository already matches `order`, and a
    // mid-drag round-trip must not reset the positions the user just set.
    var order by remember { mutableStateOf(pageIds) }
    LaunchedEffect(pageIds) {
        if (pageIds.toSet() != order.toSet()) order = pageIds
    }
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val mutable = order.toMutableList()
        val moved = mutable.removeAt(from.index)
        mutable.add(to.index, moved)
        order = mutable
        onPageOrderChanged(mutable.toList())
    }
    // Back closes the overlay instead of leaving the editor (this handler is
    // composed after the screen-level one, so it wins while visible).
    BackHandler { onDismiss() }
    val scrimTap = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(
                interactionSource = scrimTap,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Swallow taps over the content so they don't dismiss.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.editor_reorder),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
            Text(
                text = stringResource(R.string.ror_thumbnails_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(16.dp))
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(
                    order,
                    key = { _, pageId -> pageId },
                ) { index, pageId ->
                    ReorderableItem(reorderableState, key = pageId) { isDragging ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.longPressDraggableHandle(),
                        ) {
                            Box {
                                // Thumbnail resolved by stable page id (never by position):
                                // only the visual order changes, the image follows its id.
                                // Remembered on the page's own thumbnail bytes, so an
                                // unrelated reorder never decodes a stale occupant's image.
                                val pageThumb = remember(pageId, document) {
                                    document.pages.firstOrNull { it.key.pageId == pageId }
                                }
                                val thumb = remember(pageThumb) {
                                    pageThumb?.thumbnail?.toBitmap()
                                }
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 104.dp, height = 140.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .border(
                                                width = if (isDragging) 3.dp else 1.dp,
                                                color = if (isDragging) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    Color.White.copy(alpha = 0.4f)
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                            ),
                                    )
                                }
                                // Live position number.
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 6.dp)
                                        .background(
                                            color = if (isDragging) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                Color.Black.copy(alpha = 0.55f)
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                        )
                                        .padding(horizontal = 10.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    onExportClick: () -> Unit,
    onAddPageClick: () -> Unit,
    onNewSession: () -> Unit,
) {
    val haptics = rememberHapticManager()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        OutlinedButton(
            onClick = onNewSession,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 34.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = stringResource(R.string.new_session),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        OutlinedButton(
            onClick = onAddPageClick,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 34.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = stringResource(R.string.add_page),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        Button(
            onClick = {
                haptics.click()
                onExportClick()
            },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 34.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 2.dp,
                pressedElevation = 4.dp,
                disabledElevation = 0.dp
            )
        ) {
            Icon(
                Icons.Default.Done,
                contentDescription = null,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = stringResource(R.string.export),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Centers a freshly inserted overlay: fraction of the top-left corner that
 * puts the [overlayW]×[overlayH] box in the middle of the [imageW]×[imageH]
 * page image (mirrors the clamp used by the editor and the export renderer).
 */
private fun centeredOverlayFraction(
    imageW: Float,
    imageH: Float,
    overlayW: Float,
    overlayH: Float,
): Offset {
    val maxFx = (1f - overlayW / imageW.coerceAtLeast(1f)).coerceAtLeast(0f)
    val maxFy = (1f - overlayH / imageH.coerceAtLeast(1f)).coerceAtLeast(0f)
    return Offset(maxFx / 2f, maxFy / 2f)
}

/** Centers a new signature on the page; keeps the state untouched when no page is loaded. */
private fun centeredSignatureState(
    pageBitmap: Bitmap?,
    signatureBitmap: Bitmap,
    state: SignatureState,
): SignatureState {
    if (pageBitmap == null) return state.copy(positionFractionX = 0.5f, positionFractionY = 0.5f)
    val maxW = pageBitmap.width * OverlayConstants.SIGNATURE_WIDTH_FRACTION
    val maxH = pageBitmap.height * OverlayConstants.SIGNATURE_HEIGHT_FRACTION
    val (baseW, baseH) = OverlayConstants.computeSignatureBaseSize(
        signatureBitmap.width, signatureBitmap.height, maxW, maxH
    )
    val (visW, visH) = OverlayConstants.rotatedVisualSize(
        baseW * state.overlayScale, baseH * state.overlayScale, 0f
    )
    val center = centeredOverlayFraction(
        pageBitmap.width.toFloat(), pageBitmap.height.toFloat(), visW, visH
    )
    return state.copy(positionFractionX = center.x, positionFractionY = center.y)
}

/** Centers a new date overlay on the page using the same metrics as the export renderer. */
private fun centeredDatePosition(
    pageBitmap: Bitmap?,
    dateText: String,
    style: DateOverlayStyle,
): Offset {
    if (pageBitmap == null) return Offset(0.5f, 0.5f)
    val fontSize = OverlayConstants.computeDateFontSizePx(
        pageBitmap.width.toFloat(), style.fontSize, 1f
    )
    val metrics = OverlayConstants.dateMetrics(dateText, fontSize, style.backgroundStyle)
    val (visW, visH) = OverlayConstants.rotatedVisualSize(metrics.widthPx, metrics.heightPx, 0f)
    return centeredOverlayFraction(
        pageBitmap.width.toFloat(), pageBitmap.height.toFloat(), visW, visH
    )
}
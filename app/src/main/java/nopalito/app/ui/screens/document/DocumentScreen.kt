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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import nopalito.app.R
import nopalito.app.ui.Navigation
import nopalito.app.ui.components.*
import nopalito.imageprocessing.ColorMode
import nopalito.imageprocessing.ColorMode.COLOR
import nopalito.imageprocessing.ColorMode.GRAYSCALE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    uiState: DocumentUiState,
    navigation: Navigation,
    onExportClick: () -> Unit,
    onDeleteImage: () -> Unit,
    onRotateImage: (Boolean) -> Unit,
    onToggleColorMode: () -> Unit,
    onCropClick: () -> Unit,
    onPageReorder: (String, Int) -> Unit,
    onPageSelected: (Int) -> Unit,
    onNewSession: () -> Unit = {},
    onRetakePage: () -> Unit = {},
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

    val document = uiState.document
    val currentPageIndex = uiState.currentPageIndex

    val listState = rememberLazyListState()
    LaunchedEffect(currentPageIndex) {
        listState.scrollToItem(currentPageIndex)
    }

    MyScaffold(
        navigation = navigation,
        pageListState = CommonPageListState(
            document,
            onPageClick = { index -> onPageSelected(index) },
            onPageReorder = onPageReorder,
            currentPageIndex = currentPageIndex,
            listState = listState,
            showPageNumbers = true,
            pageOverlays = uiState.pageOverlays,
        ),
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
            onToggleColorMode = onToggleColorMode,
            onCropClick = onCropClick,
            onRetakePage = onRetakePage,
            onSignatureClick = { pageId ->
                pendingPageId.value = pageId
                showSignatureMenu = true
            },
            onReEditSignature = { pid, sigState ->
                if (sigState.source == SignatureSource.IMPORTED) {
                    importedBitmap = uiState.pageOverlays[pid]?.signatureBitmap?.copy(Bitmap.Config.ARGB_8888, false)
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
            onDeleteSignatureOverlay = { pid -> onDeleteSignatureOverlay(pid); selectedOverlayType = null },
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
    }
}

@Composable
private fun DocumentPreview(
    uiState: DocumentUiState,
    onDeleteImage: () -> Unit,
    onRotateImage: (Boolean) -> Unit,
    onToggleColorMode: () -> Unit,
    onCropClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRetakePage: () -> Unit = {},
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
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        var imageContainerSize by remember { mutableStateOf(IntSize.Zero) }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            val bitmap = uiState.currentPage?.bitmap
            if (bitmap != null && pageKey != null) {
                val imageBitmap = bitmap.asImageBitmap()

                Box(
                    modifier = Modifier
                        .fillMaxSize(0.95f)
                        .align(Alignment.Center)
                        .padding(8.dp)
                        .onSizeChanged { imageContainerSize = it }
                        .graphicsLayer(clip = true)  // ← keeps signatures/overlays from overflowing the image
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .align(Alignment.Center)
                    )
                    // Overlays
                    val overlays = uiState.currentPage.overlays
                    OverlayLayer(
                        overlays = overlays,
                        pageId = pageId,
                        containerSize = imageContainerSize,
                        imageSize = IntSize(bitmap.width, bitmap.height),
                        onSignatureMoved = onSignatureMoved,
                        onDateMoved = onDateMoved,
                        onSignatureScaleChanged = { pid, scale -> onSignatureScaleChanged(pid, scale) },
                        onDateScaleChanged = { pid, scale -> onDateScaleChanged(pid, scale) },
                        onSignatureRotationChanged = { pid, degrees -> onSignatureRotationChanged(pid, degrees) },
                        onDateRotationChanged = { pid, degrees -> onDateRotationChanged(pid, degrees) },
                        selectedOverlayType = selectedOverlayType,
                        onOverlaySelected = { type -> if (pageId != null) onOverlaySelected(type, pageId) },
                        onOverlayDeselected = onOverlayDeselected,
                        modifier = Modifier.fillMaxSize()
                    )

                    // ── Floating contextual toolbar ──
                    if (selectedOverlayType != null && pageId != null) {
                        val pid = pageId
                        // Nudges the selected overlay by a small fraction of
                        // the page, so it can be fine-tuned without dragging.
                        val nudgeStep = 0.02f
                        fun nudgeSelected(dx: Float, dy: Float) {
                            val overlays = uiState.currentPage.overlays
                            when (selectedOverlayType) {
                                OverlayType.SIGNATURE -> {
                                    val position = overlays.signaturePositionFraction ?: return
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
                                null -> return
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
                                        OverlayType.SIGNATURE -> onDeleteSignatureOverlay(pid)
                                        OverlayType.DATE -> onDeleteDateOverlay(pid)
                                    }
                                    onOverlayDeselected()
                                },
                                onEditStyle = { if (pid.isNotEmpty()) onDateStyleClick(pid) },
                                onEditSignature = {
                                    val sigState = uiState.currentPage.overlays.signatureState
                                    if (sigState != null) {
                                        onReEditSignature(pid, sigState)
                                    } else {
                                        onSignatureClick(pid)
                                    }
                                },
                                onZoomIn = {
                                    val overlays = uiState.currentPage.overlays
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
                                    val overlays = uiState.currentPage.overlays
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
            // Page actions column (right side)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryActionButton(
                    icon = Icons.Default.CameraAlt,
                    contentDescription = stringResource(R.string.retake),
                    onClick = { onRetakePage() }
                )
                if (pageId != null) {
                    SecondaryActionButton(
                        icon = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.sign),
                        onClick = { onSignatureClick(pageId) }
                    )
                    SecondaryActionButton(
                        icon = Icons.Default.DateRange,
                        contentDescription = stringResource(R.string.date_label),
                        onClick = { onDateOverlayClick(pageId) }
                    )
                }
                SecondaryActionButton(
                    icon = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.delete_page),
                    onClick = { onDeleteImage() }
                )
            }
            EditButtons(
                uiState,
                onToggleColorMode,
                onCropClick,
                modifier = Modifier.align(Alignment.BottomStart)
            )
            RotationButtons(onRotateImage, Modifier.align(Alignment.BottomCenter))
            Text(
                "${currentPageIndex + 1} / ${document.pageCount()}",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(all = 16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────
// Bottom toolbar composables
// ─────────────────────────────────────────────

@Composable
fun RotationButtons(
    onRotateImage: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier = modifier.padding(8.dp)) {
            @Suppress("DEPRECATION")
            SecondaryActionButton(
                icon = Icons.Default.RotateLeft,
                contentDescription = stringResource(R.string.rotate_left),
                onClick = { onRotateImage(false) }
            )
            Spacer(Modifier.width(8.dp))
            @Suppress("DEPRECATION")
            SecondaryActionButton(
                icon = Icons.Default.RotateRight,
                contentDescription = stringResource(R.string.rotate_right),
                onClick = { onRotateImage(true) }
            )
        }
    }
}

@Composable
fun EditButtons(
    uiState: DocumentUiState,
    onToggleColorMode: () -> Unit,
    onCropClick: () -> Unit,
    modifier: Modifier
) {
    Row(modifier = modifier.padding(8.dp)) {
        uiState.currentPage?.colorMode?.let {
            ColorModeButton(
                currentColorMode = it,
                onToggle = { onToggleColorMode() },
            )
        }
        Spacer(Modifier.width(8.dp))
        if (uiState.currentPage?.canBeCropped ?: false) {
            SecondaryActionButton(
                icon = Icons.Default.Crop,
                contentDescription = stringResource(R.string.crop),
                onClick = onCropClick,
            )
        }
    }
}

@Composable
fun ColorModeButton(
    currentColorMode: ColorMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        SecondaryActionButton(
            icon = Icons.Default.AutoFixHigh,
            contentDescription = stringResource(R.string.color_mode),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.color_mode_color)) },
                leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                onClick = {
                    if (currentColorMode != COLOR) onToggle()
                    expanded = false
                },
                trailingIcon = {
                    if (currentColorMode == COLOR) {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.color_mode_grayscale)) },
                leadingIcon = { Icon(Icons.Default.Contrast, contentDescription = null) },
                onClick = {
                    if (currentColorMode != GRAYSCALE) onToggle()
                    expanded = false
                },
                trailingIcon = {
                    if (currentColorMode == GRAYSCALE) {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                }
            )
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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onNewSession,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) {
            Text(
                text = stringResource(R.string.new_session),
                style = MaterialTheme.typography.labelMedium
            )
        }

        OutlinedButton(
            onClick = onAddPageClick,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.add_page),
                style = MaterialTheme.typography.labelMedium
            )
        }

        Button(
            onClick = {
                haptics.click()
                onExportClick()
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
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
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.export),
                style = MaterialTheme.typography.labelMedium
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
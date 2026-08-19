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

package nopalito.app.ui.screens.camera

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nopalito.app.BuildConfig
import nopalito.app.MainViewModel
import nopalito.app.R
import nopalito.app.ui.Navigation
import nopalito.app.ui.Screen
import nopalito.app.ui.components.*
import nopalito.app.ui.screens.qr.QrDetected
import nopalito.app.ui.screens.qr.QrResultDialog
import nopalito.app.ui.screens.qr.rememberWifiConnect
import nopalito.app.ui.screens.settings.CaptureMode
import nopalito.imageprocessing.ImageSize
import nopalito.imageprocessing.QuadStabilityMonitor
import nopalito.imageprocessing.TrackMode
import nopalito.imageprocessing.isQuadAlignedWithFrame
import kotlin.time.Duration.Companion.milliseconds

const val CAPTURED_IMAGE_DISPLAY_DURATION = 1500L
const val ANIMATION_DURATION = 200

/** Hidden debug mode: number of quick taps on the preview required to toggle it. */
const val DEBUG_TAPS_REQUIRED = 7

/** Hidden debug mode: maximum gap (ms) between consecutive taps for the sequence. */
const val DEBUG_TAP_INTERVAL_MS = 500L

@SuppressLint("ContextCastToActivity")
@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    cameraViewModel: CameraViewModel,
    navigation: Navigation,
    liveAnalysisState: LiveAnalysisState,
    importState: ImportState,
    onImageAnalyzed: (ImageProxy) -> Unit,
    onFinalizePressed: () -> Unit,
    cameraPermission: CameraPermissionState,
    onImportClicked: () -> Unit,
    autoDetect: Boolean? = null,
    onAutoDetectChanged: ((Boolean) -> Unit)? = null,
    captureMode: CaptureMode? = null,
    onCaptureModeChanged: ((CaptureMode) -> Unit)? = null,
) {
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val document by viewModel.documentUiModel.collectAsStateWithLifecycle()
    val thumbnailCoords = remember { mutableStateOf(Offset.Zero) }
    var isDebugMode by remember { mutableStateOf(false) }
    val isTorchEnabled by cameraViewModel.isTorchEnabled.collectAsStateWithLifecycle()
    val qrScanMode by cameraViewModel.qrScanMode.collectAsStateWithLifecycle()
    val qrDetected by cameraViewModel.qrDetected.collectAsStateWithLifecycle()
    val boundCameraInfo by cameraViewModel.boundCameraInfo.collectAsStateWithLifecycle()
    var torchReapplied by remember { mutableStateOf(false) }

    // Styled snackbar for import failures (password-protected / unsupported files).
    val importErrorHost = remember { SnackbarHostState() }
    val okLabel = stringResource(R.string.ok)
    LaunchedEffect(Unit) {
        cameraViewModel.events.collect { event ->
            if (event is CameraEvent.ImportError) {
                importErrorHost.showSnackbar(
                    message = event.message,
                    actionLabel = okLabel,
                    duration = SnackbarDuration.Long,
                )
            }
        }
    }

    // QR result: connecting to a WiFi network requests the nearby/location permission
    // automatically on the first attempt (shared handler used by camera and history).
    val qrContext = LocalContext.current
    val onConnectWifi = rememberWifiConnect()

    val captureController = remember { CameraCaptureController() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(qrContext) }
    DisposableEffect(Unit) {
        onDispose {
            captureController.shutdown()
            torchReapplied = false
        }
    }
    LaunchedEffect(captureController.cameraControl, isTorchEnabled) {
        // The ultra-wide lens exposes no flash unit, so the camera bind
        // switches to the flash-capable main lens while the torch is requested
        // and back to 0.6x when it is turned off. Only the bound camera's own
        // torch is driven here.
        val control = captureController.cameraControl
        if (control == null || !captureController.cameraHasFlashUnit) return@LaunchedEffect
        if (BuildConfig.DEBUG) Log.d("Torch", "enableTorch($isTorchEnabled)")
        val future = control.enableTorch(isTorchEnabled)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess {
                        if (BuildConfig.DEBUG) Log.d("Torch", "enableTorch($isTorchEnabled) OK")
                    }
                    .onFailure { e ->
                        if (BuildConfig.DEBUG) {
                            val superseded =
                                e.cause is CameraControl.OperationCanceledException
                            if (superseded) {
                                Log.d(
                                    "Torch",
                                    "enableTorch($isTorchEnabled) superseded by rebind (expected)"
                                )
                            } else {
                                Log.e("Torch", "enableTorch($isTorchEnabled) failed", e)
                            }
                        }
                    }
            },
            mainExecutor
        )
    }

    val captureState by cameraViewModel.captureState.collectAsStateWithLifecycle()
    val currentCaptureMode by cameraViewModel.captureMode.collectAsStateWithLifecycle()
    // Sync persisted capture mode with CameraViewModel
    LaunchedEffect(captureMode) {
        if (captureMode != null) {
            cameraViewModel.setCaptureMode(captureMode)
        }
    }
    // Sync auto-detect state with CameraViewModel
    LaunchedEffect(autoDetect) {
        if (autoDetect != null) {
            cameraViewModel.setAutoDetectEnabled(autoDetect)
        }
    }
    // Block additional capture in Individual mode after the first photo
    var hasCapturedInIndividual by remember { mutableStateOf(false) }
    // Prevent navigating to editor multiple times for the same document
    var hasNavigatedToEditor by remember { mutableStateOf(false) }

    // --- INE mode: the credential capture overlay and its 2-shot front/back flow ---
    // State lives in the ViewModel so it survives navigation (editor â†’ camera).
    val ineMode by viewModel.ineMode.collectAsStateWithLifecycle()
    // Number of pages already in the document when this camera visit began. Initialized
    // with the current page count so re-entering from the editor (with 2 INE pages already
    // captured) doesn't count them as new captures and trigger an instant return to the
    // editor. Only shots taken during this visit advance the front/back guide.
    var ineBaselinePageCount by remember { mutableIntStateOf(document.pageCount()) }
    val ineCaptured = document.pageCount() - ineBaselinePageCount

    // After capturing in any mode, wait and add the processed image to the document
    LaunchedEffect(captureState, currentCaptureMode, ineMode) {
        if (captureState is CaptureState.CapturePreview) {
            if (currentCaptureMode == CaptureMode.INDIVIDUAL && !ineMode) {
                if (hasCapturedInIndividual) return@LaunchedEffect
                hasCapturedInIndividual = true
            }
            delay(CAPTURED_IMAGE_DISPLAY_DURATION.milliseconds)
            cameraViewModel.addProcessedImage()
        }
    }
    // When the document receives the processed page, navigate to editor in Individual mode
    LaunchedEffect(document, ineMode) {
        if (!ineMode &&
            hasCapturedInIndividual &&
            currentCaptureMode == CaptureMode.INDIVIDUAL &&
            !document.isEmpty() &&
            !hasNavigatedToEditor
        ) {
            hasNavigatedToEditor = true
            viewModel.navigateTo(Screen.Main.Document(0))
        }
        // When starting a new document (new session), reset the Individual lock
        if (document.isEmpty()) {
            hasCapturedInIndividual = false
            hasNavigatedToEditor = false
        }
    }

    var showDetectionError by remember { mutableStateOf(false) }
    LaunchedEffect(captureState) {
        if (captureState is CaptureState.CaptureError) {
            showDetectionError = true
            delay(1000.milliseconds)
            showDetectionError = false
            cameraViewModel.afterCaptureError()
        }
    }

    // Hidden easter egg: a little cactus that sprouts when debug mode is
    // unlocked with the secret tap sequence.
    var showNopalEgg by remember { mutableStateOf(false) }

    // --- INE mode: the credential capture overlay and its 2-shot front/back flow ---
    fun toggleIneMode() {
        val enable = !ineMode
        // QR scan and INE are mutually exclusive camera modes.
        if (enable) cameraViewModel.setQrScanMode(false)
        // Snapshot the baseline on activation so guides count only this session's shots.
        if (enable) {
            hasNavigatedToEditor = false
            ineBaselinePageCount = document.pageCount()
        }
        viewModel.setIneMode(enable)
    }

    // --- QR/barcode scan mode: dim overlay + frame + animated scan line on the live camera ---
    fun toggleQrScanMode() {
        val enable = !qrScanMode
        if (enable && ineMode) viewModel.exitIneMode()
        cameraViewModel.setQrScanMode(enable)
    }

    // QR and INE are mutually exclusive. QR wins on app restore so re-opening while
    // QR mode is active resumes scanning instead of a stale INE session.
    LaunchedEffect(qrScanMode) {
        if (qrScanMode && ineMode) viewModel.exitIneMode()
    }

    // When the camera is re-entered (editor â†’ camera) with INE still active and pages
    // already captured, re-anchor the baseline so we don't instantly auto-navigate back
    // to the editor. New shots taken now extend the current document.
    LaunchedEffect(Unit) {
        if (ineMode) {
            hasNavigatedToEditor = false
            ineBaselinePageCount = document.pageCount()
        }
    }

    // After both faces are captured, open the existing editor keeping the front/back as
    // two separate pages. They are merged into one sheet only at export time.
    LaunchedEffect(ineCaptured, ineMode) {
        if (ineMode && ineCaptured >= 2 && document.pageCount() >= 2 && !hasNavigatedToEditor) {
            hasNavigatedToEditor = true
            viewModel.completeIneCapture()
        }
    }

    LaunchedEffect(Unit) {
        cameraViewModel.resetLiveAnalysis()
    }

    // --- INE import: ask whether the picked photo is the front or the back so the
    // imported page lands in the right position of the credential document. ---
    var showIneImportDialog by remember { mutableStateOf(false) }
    var ineImportSide by remember { mutableStateOf<Boolean?>(null) } // true = front
    var ineImportBaseline by remember { mutableIntStateOf(0) }
    val handleImportClick: () -> Unit = {
        if (ineMode) {
            ineImportBaseline = document.pageCount()
            showIneImportDialog = true
        } else {
            onImportClicked()
        }
    }
    // After the import finishes, place the newly added page(s) on the chosen side.
    LaunchedEffect(importState, ineImportSide) {
        val side = ineImportSide
        if (side == null || importState !is ImportState.Idle) return@LaunchedEffect
        ineImportSide = null
        val newIds = document.pages
            .drop(ineImportBaseline)
            .map { it.key.pageId }
        if (side) {
            // Front goes to the top of the credential: move newest first so order holds.
            newIds.asReversed().forEach { id -> viewModel.movePage(id, 0) }
        } else {
            // Back goes below the front: it is already appended after existing pages.
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraViewModel.cancelImport()
        }
    }

    // Camera screen uses a dark container, so keep status-bar icons light and
    // restore the theme's appearance when the screen is left (edge-to-edge aware).
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val restoredStatusAppearance = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            if (restoredStatusAppearance != null) {
                controller.isAppearanceLightStatusBars = restoredStatusAppearance
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(document.pageCount()) {
        if (!document.isEmpty()) {
            listState.animateScrollToItem(document.lastIndex())
        }
    }

    fun handleCapture() {
        // In Individual mode, only allow one capture (if already captured, ignore)
        if (currentCaptureMode == CaptureMode.INDIVIDUAL && hasCapturedInIndividual) return
        // INE mode takes exactly two shots (front, then back).
        if (ineMode && ineCaptured >= 2) return
        previewView?.bitmap?.let {
            Log.i("FairScan", "Pressed <Capture>")
            cameraViewModel.onCapturePressed(it)
            captureController.takePicture(
                onImageCaptured = { imageProxy, opticalMeasures ->
                    cameraViewModel.onImageCaptured(imageProxy, opticalMeasures)
                }
            )
        }
    }

    val onCapture = { handleCapture() }
    LaunchedEffect(Unit) {
        cameraViewModel.volumeKeyEvent.collect {
            onCapture()
        }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // --- Auto-capture with stability timer ---
    // Requires the quad to be present and stable for STABILITY_DELAY_MS and a
    // minimum number of consecutive low-movement updates.
    // CamScanner-style: detect → visible overlay → wait for stability → capture.
    val stabilityMonitor = remember { QuadStabilityMonitor() }
    val currentQuad = liveAnalysisState.stableQuad

    // Evaluate auto-capture conditions on each quad update
    LaunchedEffect(currentQuad, ineMode, qrScanMode) {
        val quad = currentQuad
        if (quad == null) {
            stabilityMonitor.reset()
            return@LaunchedEffect
        }
        // Manual capture while the INE guide is active: the framed flow must be
        // user-paced so the front/back shots land in order.
        if (ineMode) return@LaunchedEffect
        // QR mode reroutes analysis to barcode detection, so no document quad.
        if (qrScanMode) return@LaunchedEffect

        val isEffectivelyEnabled = autoDetect ?: true
        if (!isEffectivelyEnabled) return@LaunchedEffect
        if (captureState !is CaptureState.Idle) return@LaunchedEffect
        if (importState !is ImportState.Idle) return@LaunchedEffect

        if (stabilityMonitor.update(quad, System.currentTimeMillis())) {
            Log.d("AutoCapture", "Triggering auto-capture after stable period")
            onCapture()
        }
    }
    Box {
        CameraScreenScaffold(
            cameraPreview = {
                CameraPreview(
                    captureController = captureController,
                    onPreviewViewReady = { view ->
                        previewView = view
                        captureController.previewView = view
                    },
                    cameraPermission = cameraPermission,
                    onError = { message, throwable -> cameraViewModel.logError(message, throwable) },
                    onImageAnalyzed = { imageProxy -> onImageAnalyzed(imageProxy) },
                    onCameraBound = { info -> cameraViewModel.setBoundCameraInfo(info) },
                    torchEnabled = isTorchEnabled,
                )
            },
            pageListState =
                CommonPageListState(
                    document = document,
                    onPageClick = { index -> viewModel.navigateTo(Screen.Main.Document(index)) },
                    onPageReorder = { id, index -> viewModel.movePage(id, index) },
                    listState = listState,
                    showPageNumbers = false,
                    onLastItemPosition = { offset -> thumbnailCoords.value = offset },
                ),
            cameraUiState = CameraUiState(
                document.pageCount(),
                liveAnalysisState,
                captureState,
                importState,
                showDetectionError,
                isLandscape = isLandscape,
                isDebugMode,
                isTorchEnabled,
                boundCameraInfo
            ),
            onCapture = onCapture,
            onFinalizePressed = onFinalizePressed,
            onDebugModeSwitched = {
                isDebugMode = !isDebugMode
                if (isDebugMode) showNopalEgg = true
            },
            onTorchSwitched = {
                cameraViewModel.setTorchEnabled(!isTorchEnabled)
            },
            onQrClicked = ::toggleQrScanMode,
            qrScanMode = qrScanMode,
            ineMode = ineMode,
            ineCaptured = ineCaptured,
            onIneSwitched = ::toggleIneMode,
            thumbnailCoords = thumbnailCoords,
            navigation = navigation,
            captureController = captureController,
            isCameraPermissionGranted = cameraPermission.isGranted,
            onRequestCameraPermission = { cameraPermission.request() },
            onImportClicked = handleImportClick,
            onNewSessionClicked = {
                viewModel.startNewDocument()
                navigation.toCameraScreen()
            },
            autoDetect = autoDetect,
            onAutoDetectChanged = onAutoDetectChanged,
            captureMode = captureMode,
            onCaptureModeChanged = onCaptureModeChanged,
        )
        SnackbarHost(
            hostState = importErrorHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
        ) { data ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = data.visuals.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    val actionLabel = data.visuals.actionLabel
                    if (actionLabel != null) {
                        TextButton(onClick = { data.dismiss() }) {
                            Text(
                                text = actionLabel,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showIneImportDialog) {
        AlertDialog(
            onDismissRequest = { showIneImportDialog = false },
            title = { Text(stringResource(R.string.ine_import_title)) },
            text = { Text(stringResource(R.string.ine_import_which_side)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showIneImportDialog = false
                        ineImportSide = true
                        onImportClicked()
                    }
                ) {
                    Text(stringResource(R.string.ine_front))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showIneImportDialog = false
                        ineImportSide = false
                        onImportClicked()
                    }
                ) {
                    Text(stringResource(R.string.ine_back))
                }
            },
        )
    }

    NopalEasterEgg(visible = showNopalEgg, onFinished = { showNopalEgg = false })

    qrDetected?.let { result ->
        QrResultDialog(
            detected = result,
            onCopy = {
                val clipboard =
                    qrContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("QR", result.content))
                Toast.makeText(qrContext, R.string.qr_copied, Toast.LENGTH_SHORT).show()
            },
            onShare = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, result.content)
                }
                runCatching { qrContext.startActivity(Intent.createChooser(send, null)) }
            },
            onOpen = {
                runCatching {
                    qrContext.startActivity(Intent(Intent.ACTION_VIEW, result.content.toUri()))
                }
            },
            onOpenMap = {
                val geo = (result.type as? QrDetected.Type.Geo) ?: return@QrResultDialog
                runCatching {
                    qrContext.startActivity(
                        Intent(Intent.ACTION_VIEW, "geo:${geo.lat},${geo.lng}?q=${geo.lat},${geo.lng}".toUri())
                    )
                }
            },
            onConnect = onConnectWifi,
            onClose = { cameraViewModel.dismissQrResult() },
        )
    }
}

@Composable
private fun CameraScreenScaffold(
    cameraPreview: @Composable () -> Unit,
    pageListState: CommonPageListState,
    cameraUiState: CameraUiState,
    onCapture: () -> Unit,
    onFinalizePressed: () -> Unit,
    onDebugModeSwitched: () -> Unit,
    onTorchSwitched: () -> Unit,
    onQrClicked: () -> Unit = {},
    qrScanMode: Boolean = false,
    ineMode: Boolean = false,
    ineCaptured: Int = 0,
    onIneSwitched: () -> Unit = {},
    thumbnailCoords: MutableState<Offset>,
    navigation: Navigation,
    captureController: CameraCaptureController,
    isCameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onImportClicked: () -> Unit,
    cameraViewModel: CameraViewModel? = null,
    onNewSessionClicked: () -> Unit = {},
    autoDetect: Boolean? = null,
    onAutoDetectChanged: ((Boolean) -> Unit)? = null,
    captureMode: CaptureMode? = null,
    onCaptureModeChanged: ((CaptureMode) -> Unit)? = null,
) {
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1000.milliseconds)
            focusPoint = null
        }
    }

    // Hidden debug toggle: 7 quick consecutive taps on the preview
    // (less than DEBUG_TAP_INTERVAL_MS between each one). Deliberately longer
    // than a casual triple tap so it is hard to trigger by accident.
    var tapCount by remember { mutableLongStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    val onPreviewTap = {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTapTime < DEBUG_TAP_INTERVAL_MS) {
            tapCount++
            if (tapCount >= DEBUG_TAPS_REQUIRED) {
                onDebugModeSwitched()
                tapCount = 0
            }
        } else {
            tapCount = 1
        }
        lastTapTime = currentTime
    }

    val effectiveAutoDetect =
        autoDetect ?: cameraViewModel?.autoDetectEnabled?.collectAsStateWithLifecycle()?.value
        ?: false
    val effectiveCaptureMode =
        captureMode ?: cameraViewModel?.captureMode?.collectAsStateWithLifecycle()?.value
        ?: CaptureMode.BATCH

    Box {
        MyScaffold(
            navigation = navigation,
            cameraMode = true,
            autoDetect = effectiveAutoDetect,
            onAutoDetectChanged = onAutoDetectChanged ?: { enabled ->
                cameraViewModel?.setAutoDetectEnabled(enabled)
                Unit
            },
            captureMode = effectiveCaptureMode,
            onCaptureModeChanged = onCaptureModeChanged ?: { mode ->
                cameraViewModel?.setCaptureMode(mode)
                Unit
            },
            pageListState = pageListState,
            bottomBar = {
                Bar(
                    pageCount = cameraUiState.pageCount,
                    onFinalizePressed = onFinalizePressed,
                    onNewSessionClicked = onNewSessionClicked,
                )
            },
            cameraControls = if (
                isCameraPermissionGranted &&
                cameraUiState.importState is ImportState.Idle &&
                !cameraUiState.isLandscape
            ) {
                {
                    CaptureDeck(
                        torchEnabled = cameraUiState.isTorchEnabled,
                        onTorchSwitched = onTorchSwitched,
                        onCapture = onCapture,
                        onImportClicked = onImportClicked,
                        onQrClicked = onQrClicked,
                        qrScanMode = qrScanMode,
                        ineMode = ineMode,
                        onIneSwitched = onIneSwitched,
                    )
                }
            } else null,
        ) { modifier ->
            if (cameraUiState.importState is ImportState.Selecting) {
                // display nothing: photo picker is active
            } else if (cameraUiState.importState is ImportState.Importing) {
                ImportInProgress(cameraUiState.importState, modifier)
            } else if (!isCameraPermissionGranted) {
                CameraPermissionRationale(
                    onRequestCameraPermission = onRequestCameraPermission,
                    onImportClicked = onImportClicked,
                    modifier = modifier,
                )
            } else {
                Box(modifier = modifier) {
                    CameraPreviewBox(
                        cameraPreview,
                        cameraUiState,
                        focusPoint,
                        onCapture,
                        onTorchSwitched,
                        onImportClicked,
                        onQrClicked,
                        ineMode,
                        onIneSwitched,
                        Modifier.pointerInput(Unit) {
                            detectTapGestures { offset ->
                                focusPoint = offset
                                captureController.tapToFocus(offset)
                                onPreviewTap()
                            }
                        }
                    )
                    if (ineMode) {
                        IneGuideOverlay(
                            showBack = ineCaptured >= 1,
                            liveAnalysisState = cameraUiState.liveAnalysisState,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else if (qrScanMode) {
                        QrScanOverlay(modifier = Modifier.matchParentSize())
                    }
                }
            }
        }
        if (cameraUiState.captureState is CaptureState.CapturePreview) {
            val page = cameraUiState.captureState.capturedPage.pageJpeg.toBitmap()
            CapturedImage(page.asImageBitmap(), thumbnailCoords)
        }
    }
}

@Composable
fun ImportInProgress(state: ImportState.Importing, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = LocalResources.current.getQuantityString(
                    R.plurals.importing_photos,
                    state.total,
                    state.total
                ),
                color = Color.White
            )

            Spacer(Modifier.height(16.dp))

            if (state.total > 1) {
                LinearProgressIndicator(
                    progress = { state.processed.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CameraPreviewBox(
    cameraPreview: @Composable (() -> Unit),
    cameraUiState: CameraUiState,
    focusPoint: Offset?,
    onCapture: () -> Unit,
    onTorchSwitched: () -> Unit,
    onImportClicked: () -> Unit,
    onQrClicked: () -> Unit = {},
    ineMode: Boolean = false,
    onIneSwitched: () -> Unit = {},
    modifier: Modifier,
) {
    Box(
        modifier = modifier
    ) {
        CameraPreviewWithOverlay(
            cameraPreview,
            cameraUiState,
            Modifier,
            ineMode = ineMode,
        )
        if (cameraUiState.isDebugMode) {
            MessageBox(
                cameraUiState.liveAnalysisState,
                cameraUiState.boundCameraInfo,
            )
        }
        FocusOverlay(focusPoint)
        if (cameraUiState.isLandscape) {
            CaptureDeck(
                torchEnabled = cameraUiState.isTorchEnabled,
                onTorchSwitched = onTorchSwitched,
                onCapture = onCapture,
                onImportClicked = onImportClicked,
                onQrClicked = onQrClicked,
                ineMode = ineMode,
                onIneSwitched = onIneSwitched,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
            )
        }
    }
}

@Composable
private fun CaptureDeck(
    torchEnabled: Boolean,
    onTorchSwitched: () -> Unit,
    onCapture: () -> Unit,
    onImportClicked: () -> Unit,
    modifier: Modifier = Modifier,
    onQrClicked: () -> Unit = {},
    qrScanMode: Boolean = false,
    ineMode: Boolean = false,
    onIneSwitched: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = Color.Black,
        shadowElevation = 10.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 340.dp)
        ) {
            Spacer(Modifier.width(0.dp))
            DeckSideButton(
                icon = Icons.Default.Highlight,
                label = stringResource(R.string.torch),
                contentDescription = stringResource(
                    if (torchEnabled) R.string.turn_off_torch else R.string.turn_on_torch
                ),
                containerColor =
                    if (torchEnabled) Color(0xFFFFE14D) else Color.Black.copy(alpha = 0.5f),
                contentColor = if (torchEnabled) Color(0xFF4A3A00) else Color.White,
                onClick = onTorchSwitched,
            )
            DeckSideButton(
                icon = Icons.Default.AddPhotoAlternate,
                label = stringResource(R.string.import_photos),
                contentDescription = stringResource(R.string.import_photos),
                containerColor = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White,
                onClick = onImportClicked,
            )
            CaptureButton(onClick = onCapture, modifier = Modifier.size(62.dp))
            DeckSideButton(
                icon = Icons.Default.QrCodeScanner,
                label = stringResource(R.string.qr_code),
                contentDescription = stringResource(R.string.qr_code),
                containerColor =
                    if (qrScanMode) Color(0xFF4CAF50) else Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White,
                onClick = onQrClicked,
            )
            DeckSideButton(
                icon = Icons.Default.Badge,
                label = stringResource(R.string.ine),
                contentDescription = stringResource(R.string.ine),
                containerColor =
                    if (ineMode) Color(0xFF4CAF50) else Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White,
                onClick = onIneSwitched,
            )
        }
    }
}

@Composable
private fun DeckSideButton(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(48.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            modifier = Modifier.size(40.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Semi-transparent dim with a centered transparent frame that guides the user to
 * align the INE credential, plus a brief "Escanea frente/reverso" label. The hole
 * is punched out of the dim via an even-odd path so the live camera stays visible.
 */
@Composable
private fun IneGuideOverlay(
    showBack: Boolean,
    liveAnalysisState: LiveAnalysisState,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(
        if (showBack) R.string.ine_scan_back else R.string.ine_scan_front
    )
    val hint = stringResource(R.string.ine_place_hint)

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val previewWidth = with(density) { maxWidth.toPx() }
        val previewHeight = with(density) { maxHeight.toPx() }

        // Landscape frame matching a debit/bank card: 8 cm wide x 5 cm high
        // (aspect ratio 1.6:1). Wider than tall, like the physical document.
        // Slightly above vertical center so it clears the bottom capture deck.
        val frameW = previewWidth * 0.85f
        val frameH = frameW * (5f / 8f)
        val left = (previewWidth - frameW) / 2f
        val top = (previewHeight - frameH) / 2f - previewHeight * 0.09f
        val corner = 28f
        val frameRect = Rect(left, top, left + frameW, top + frameH)

        // Turns green when the tracked card quad is detected inside the frame.
        val quad = liveAnalysisState.stableQuad
        val maskSize = liveAnalysisState.maskSize
        val detected = quad != null && maskSize != null && isQuadAlignedWithFrame(
            quad,
            maskSize,
            liveAnalysisState.analysisFrameSize ?: maskSize,
            liveAnalysisState.rotationDegrees,
            ImageSize(previewWidth.toDouble(), previewHeight.toDouble()),
            frameRect.left.toDouble(),
            frameRect.top.toDouble(),
            frameRect.right.toDouble(),
            frameRect.bottom.toDouble(),
        )
        val frameColor = if (detected) Color(0xFF0CAD55) else Color.White

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Dim the whole screen, leaving the frame transparent via EvenOdd fill.
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addRoundRect(
                    RoundRect(
                        Rect(left, top, left + frameW, top + frameH),
                        CornerRadius(corner),
                    )
                )
            }
            drawPath(path, Color.Black.copy(alpha = 0.68f))

            // Soft rounded border around the transparent frame (crisp inner edge,
            // faint outer glow) to read as a capture guide. Green once the card
            // is detected inside.
            drawRoundRect(
                color = frameColor.copy(alpha = 0.9f),
                topLeft = Offset(left, top),
                size = Size(frameW, frameH),
                cornerRadius = CornerRadius(corner),
                style = Stroke(width = if (detected) 4f else 3f),
            )
            drawRoundRect(
                color = frameColor.copy(alpha = if (detected) 0.45f else 0.18f),
                topLeft = Offset(left - 8f, top - 8f),
                size = Size(frameW + 16f, frameH + 16f),
                cornerRadius = CornerRadius(corner),
                style = Stroke(width = if (detected) 3f else 2f),
            )
        }

        // Guide strip pinned just above the transparent frame.
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = -232.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    text = label,
                    color = if (detected) Color(0xFF0CAD55) else Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hint,
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * QR scan mode overlay: dims everything except a centered square frame and sweeps
 * a green scan line through it to simulate scanning, same in-place pattern as the
 * INE guide (the live camera stays visible through the transparent hole).
 */
@Composable
private fun QrScanOverlay(modifier: Modifier = Modifier) {
    val hint = stringResource(R.string.qr_detect_hint)
    val transition = rememberInfiniteTransition(label = "qrScan")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
        ),
        label = "qrScanLine",
    )

    BoxWithConstraints(modifier = modifier) {
        val frameW = maxWidth * 0.72f
        val frameH = frameW
        // Raise the scan frame (and the hint above it) higher on screen.
        val frameUp = 84.dp
        val frameUpPx = with(LocalDensity.current) { frameUp.toPx() }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fw = frameW.toPx()
            val fh = frameH.toPx()
            val left = (size.width - fw) / 2f
            val top = (size.height - fh) / 2f - frameUpPx
            val corner = 28f

            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addRoundRect(
                    RoundRect(
                        Rect(left, top, left + fw, top + fh),
                        CornerRadius(corner),
                    )
                )
            }
            drawPath(path, Color.Black.copy(alpha = 0.68f))

            // Corner brackets in scanner green.
            val len = fw * 0.16f
            val strokeWidth = 6f
            val green = Color(0xFF0CAD55)
            fun cornerBracket(cx: Float, cy: Float, dx: Float, dy: Float) {
                drawLine(green, Offset(cx, cy), Offset(cx + dx * len, cy), strokeWidth)
                drawLine(green, Offset(cx, cy), Offset(cx, cy + dy * len), strokeWidth)
            }
            cornerBracket(left, top, 1f, 1f)
            cornerBracket(left + fw, top, -1f, 1f)
            cornerBracket(left, top + fh, 1f, -1f)
            cornerBracket(left + fw, top + fh, -1f, -1f)

            // Animated scan line sweeping top â†’ bottom through the frame. Fades in at
            // the top edge and out at the bottom edge so the loop restarts smoothly
            // (no visible teleport), while always reading as top â†’ bottom.
            val lineY = top + 10f + progress * (fh - 20f)
            val fade = minOf(1f, progress * 24f, (1f - progress) * 24f)
            val trailLen = fh * 0.06f
            val trailSteps = 5
            for (i in 0 until trailSteps) {
                val t = i.toFloat() / (trailSteps - 1)
                drawLine(
                    color = green.copy(alpha = fade * (1f - t) * 0.5f),
                    start = Offset(left + 12f, lineY - t * trailLen),
                    end = Offset(left + fw - 12f, lineY - t * trailLen),
                    strokeWidth = 4f,
                )
            }
            drawLine(
                color = green.copy(alpha = 0.95f * fade),
                start = Offset(left + 12f, lineY),
                end = Offset(left + fw - 12f, lineY),
                strokeWidth = 4f,
            )
        }

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (maxHeight - frameH) / 2f - 52.dp - frameUp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = Color(0xFF86E6B1),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = hint,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun CapturedImage(image: ImageBitmap, thumbnailCoords: MutableState<Offset>) {
    Surface(
        color = Color.Black.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxSize(),
    ) {}

    var isAnimating by remember { mutableStateOf(false) }
    LaunchedEffect(image) {
        delay((CAPTURED_IMAGE_DISPLAY_DURATION - ANIMATION_DURATION).milliseconds)
        isAnimating = true
    }
    var targetOffsetX by remember { mutableFloatStateOf(0f) }
    var targetOffsetY by remember { mutableFloatStateOf(0f) }

    val transition = updateTransition(targetState = isAnimating, label = "captureAnimation")
    val tween = tween<Float>(durationMillis = ANIMATION_DURATION)
    val offsetX by transition.animateFloat({ tween }, "offsetX") { if (it) targetOffsetX else 0f }
    val offsetY by transition.animateFloat({ tween }, "offsetY") { if (it) targetOffsetY else 0f }
    val scale by transition.animateFloat({ tween }, "scale") { if (it) 0.3f else 1f }

    val density = LocalDensity.current
    Box(
        contentAlignment = Alignment.BottomStart,
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val centerX = bounds.left + bounds.width / 2
                val centerY = bounds.top + bounds.height / 2
                with(density) {
                    targetOffsetX = thumbnailCoords.value.x - centerX
                    targetOffsetY = thumbnailCoords.value.y - centerY
                }
            }
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .graphicsLayer {
                    translationX = offsetX
                    translationY = offsetY
                    scaleX = scale
                    scaleY = scale
                }
        )
    }
}

@Composable
fun CaptureButton(onClick: () -> Unit, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(62.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .border(
                    width = 3.dp,
                    color = Color.White.copy(alpha = 0.9f),
                    shape = CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    color = color,
                    shape = CircleShape
                )
                .border(
                    width = 2.dp,
                    color = Color.White.copy(alpha = 0.35f),
                    shape = CircleShape
                )
        )
    }
}

@Composable
private fun CameraPreviewWithOverlay(
    cameraPreview: @Composable () -> Unit,
    cameraUiState: CameraUiState,
    modifier: Modifier,
    ineMode: Boolean = false,
) {
    val captureState = cameraUiState.captureState

    var showShutter by remember { mutableStateOf(false) }
    LaunchedEffect(captureState.frozenImage) {
        if (captureState.frozenImage != null) {
            showShutter = true
            delay(200.milliseconds)
            showShutter = false
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        cameraPreview()
        AnalysisOverlay(
            cameraUiState.liveAnalysisState,
            cameraUiState.isDebugMode,
            ineMode,
        )
        captureState.frozenImage?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
            )

        }
        if (showShutter) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.6f))
            )
        }
        if (cameraUiState.showCaptureError) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.error_occurred),
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun FocusOverlay(focusPoint: Offset?) {
    if (focusPoint == null) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val size = 80f
        drawRect(
            color = Color.White,
            topLeft = Offset(
                focusPoint.x - size / 2,
                focusPoint.y - size / 2
            ),
            size = Size(size, size),
            style = Stroke(width = 3f)
        )
    }
}

/**
 * Hidden easter egg: a smiling cactus that sprouts with a spring animation
 * when the secret debug sequence is entered, then fades away by itself.
 */
@Composable
private fun NopalEasterEgg(visible: Boolean, onFinished: () -> Unit) {
    if (!visible) return
    var dismissed by remember { mutableStateOf(false) }
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
        delay(2400.milliseconds)
        dismissed = true
        onFinished()
    }
    if (dismissed) return

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(bottom = 230.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        ) {
            Canvas(modifier = Modifier.size(110.dp, 130.dp)) {
                drawCactus()
            }
            Text(
                text = "¡Modo Nopalito! \uD83C\uDF35",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

private fun DrawScope.drawCactus() {
    val w = size.width
    val h = size.height
    val green = Color(0xFF2E8B57)
    val potBrown = Color(0xFF8B5A2B)

    // Pot
    drawPath(
        Path().apply {
            moveTo(w * 0.24f, h * 0.76f)
            lineTo(w * 0.3f, h * 0.97f)
            lineTo(w * 0.7f, h * 0.97f)
            lineTo(w * 0.76f, h * 0.76f)
            close()
        },
        potBrown,
    )

    // Trunk
    drawRoundRect(
        color = green,
        topLeft = Offset(w * 0.37f, h * 0.26f),
        size = Size(w * 0.26f, h * 0.56f),
        cornerRadius = CornerRadius(w * 0.13f),
    )

    // Left arm
    drawRoundRect(
        color = green,
        topLeft = Offset(w * 0.11f, h * 0.36f),
        size = Size(w * 0.26f, h * 0.14f),
        cornerRadius = CornerRadius(w * 0.07f),
    )
    drawRoundRect(
        color = green,
        topLeft = Offset(w * 0.11f, h * 0.36f),
        size = Size(w * 0.12f, h * 0.24f),
        cornerRadius = CornerRadius(w * 0.06f),
    )

    // Right arm
    drawRoundRect(
        color = green,
        topLeft = Offset(w * 0.63f, h * 0.42f),
        size = Size(w * 0.26f, h * 0.14f),
        cornerRadius = CornerRadius(w * 0.07f),
    )
    drawRoundRect(
        color = green,
        topLeft = Offset(w * 0.77f, h * 0.42f),
        size = Size(w * 0.12f, h * 0.22f),
        cornerRadius = CornerRadius(w * 0.06f),
    )

    // Face: eyes + smile
    drawCircle(Color.White, radius = w * 0.035f, center = Offset(w * 0.44f, h * 0.38f))
    drawCircle(Color.White, radius = w * 0.035f, center = Offset(w * 0.56f, h * 0.38f))
    drawArc(
        color = Color.White,
        startAngle = 25f,
        sweepAngle = 130f,
        useCenter = false,
        topLeft = Offset(w * 0.44f, h * 0.40f),
        size = Size(w * 0.12f, h * 0.08f),
        style = Stroke(width = w * 0.02f),
    )
}

@Composable
fun MessageBox(
    liveAnalysisState: LiveAnalysisState,
    boundCameraInfo: String?,
) {
    if (liveAnalysisState.inferenceTime == 0L && liveAnalysisState.analysisTimeMs == 0L) return
    val modeLabel = when (liveAnalysisState.detectionMode) {
        TrackMode.FULL_DETECTION -> "DET"
        TrackMode.OPTICAL_FLOW -> "TRK"
        null -> "-"
    }
    val maskLabel = liveAnalysisState.maskSize?.let { "${it.width.toInt()}x${it.height.toInt()}" } ?: "-"
    val frameLabel =
        liveAnalysisState.analysisFrameSize?.let { "${it.width.toInt()}x${it.height.toInt()}" } ?: "-"
    Text(
        text = stringResource(R.string.segmentation_time, liveAnalysisState.inferenceTime) +
                " · ${liveAnalysisState.analysisTimeMs} ms" +
                " · $modeLabel" +
                " · %.1f fps".format(liveAnalysisState.analysisFps) +
                " · mask=$maskLabel frame=$frameLabel rot=${liveAnalysisState.rotationDegrees}" +
                (boundCameraInfo?.let { " · $it" } ?: ""),
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        color = Color.Gray,
    )
}

@Composable
private fun Bar(
    pageCount: Int,
    onFinalizePressed: () -> Unit,
    onNewSessionClicked: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        TextButton(
            onClick = onNewSessionClicked,
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.new_session),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        MainActionButton(
            onClick = onFinalizePressed,
            enabled = pageCount > 0,
            text = pageCountText(pageCount),
            icon = Icons.Default.Done,
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
}

@Composable
private fun CameraPermissionRationale(
    onRequestCameraPermission: () -> Unit,
    onImportClicked: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(24.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.camera_permission_rationale),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onRequestCameraPermission,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        stringResource(R.string.grant_permission),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onImportClicked,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.import_photos),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
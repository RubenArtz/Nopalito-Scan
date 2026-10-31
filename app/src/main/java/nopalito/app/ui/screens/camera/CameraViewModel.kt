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

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import nopalito.app.AppContainer
import nopalito.app.BuildConfig
import nopalito.app.R
import nopalito.app.domain.CapturedPage
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.platform.extractDocumentFromBitmap
import nopalito.app.ui.isEncryptedOle2
import nopalito.app.ui.isPdfEncrypted
import nopalito.app.ui.screens.cloud.data.CloudConversionRepository
import nopalito.app.ui.screens.cloud.data.PcLinkRepository
import nopalito.app.ui.screens.cloud.model.PcLinkError
import nopalito.app.ui.screens.cloud.model.QrLinkPayload
import nopalito.app.ui.screens.qr.QrAnalyzer
import nopalito.app.ui.screens.qr.QrCandidate
import nopalito.app.ui.screens.qr.QrDetected
import nopalito.app.ui.screens.qr.QrDetectionState
import nopalito.app.ui.screens.qr.QrDetectionTracker
import nopalito.app.ui.screens.qr.QrFrame
import nopalito.app.ui.screens.qr.QrScanEntity
import nopalito.app.ui.screens.qr.QrSheetItem
import nopalito.app.ui.screens.qr.StillQrResult
import nopalito.app.ui.screens.qr.encodeQrType
import nopalito.app.ui.screens.settings.DefaultColorMode
import nopalito.app.ui.screens.tools.queryDisplayName
import nopalito.app.ui.screens.tools.shared.PdfPreviewRenderer
import nopalito.imageprocessing.ImageSize
import nopalito.imageprocessing.Mode
import nopalito.imageprocessing.OpticalMeasures
import nopalito.imageprocessing.detectDocumentQuad
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** File types offered by the camera import picker in normal mode (images, PDF and Word). */
val CAMERA_IMPORT_MIME_TYPES: Array<String> = arrayOf(
    "image/*",
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.oasis.opendocument.text",
    "application/rtf",
    "text/rtf",
    "text/plain",
)

/** Document extensions handled by the cloud-conversion import path. */
private val IMPORTABLE_DOCUMENT_EXTENSIONS = listOf(".doc", ".docx", ".odt", ".rtf", ".txt")

/** Image extensions accepted when the MIME type is missing or generic. */
private val IMPORTABLE_IMAGE_EXTENSIONS =
    listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".heic", ".heif")

/**
 * Single compatibility check shared by the cloud import browser (to hide
 * non-importable files) and the final pre-download validation. Mirrors the
 * routing in [CameraViewModel.importPickedFile]: images, PDF, and
 * Word-compatible documents handled through cloud conversion. Anything else
 * (including extension-less or unknown-MIME items) returns false and is never
 * imported automatically. The system picker itself is filtered by
 * [CAMERA_IMPORT_MIME_TYPES], which a contract predicate cannot express.
 */
internal fun isImportableFile(name: String, mimeType: String?): Boolean {
    val mime = mimeType.orEmpty()
    if (mime.startsWith("image/") || mime == "application/pdf") return true
    if (mime in CAMERA_IMPORT_MIME_TYPES) return true
    val lower = name.lowercase()
    if (lower.endsWith(".pdf")) return true
    if (IMPORTABLE_IMAGE_EXTENSIONS.any { lower.endsWith(it) }) return true
    return IMPORTABLE_DOCUMENT_EXTENSIONS.any { lower.endsWith(it) }
}

/** Pixel width used to render imported PDF pages before adding them to the document. */
private const val IMPORT_PDF_TARGET_WIDTH_PX = 1600

sealed interface CameraEvent {
    data class ImageCaptured(val page: CapturedPage) : CameraEvent

    /** A picked file could not be imported ([message] is already localized). */
    data class ImportError(val message: String) : CameraEvent
}

/** User-visible file-capture failures. ORIGINAL never downgrades silently. */
sealed interface CaptureFileError {
    data class InsufficientStorage(
        val requiredBytes: Long,
        val freeBytes: Long,
        val tier: nopalito.app.domain.CaptureTier
    ) : CaptureFileError

    data class ProcessingFailed(val cause: String?) : CaptureFileError
    data object Cancelled : CaptureFileError
}

class CameraViewModel(appContainer: AppContainer) : ViewModel() {

    private val imageSegmentationService = appContainer.imageSegmentationService
    private val settingsRepository = appContainer.settingsRepository
    private val imageLoader = appContainer.imageLoader
    private val logger = appContainer.logger
    private val analyticsTracker = appContainer.analyticsTracker
    private val applicationContext = appContainer.applicationContext
    private val cloudConversionRepository = CloudConversionRepository(applicationContext)
    private val statsRepository = appContainer.statsRepository

    // Approves Cloud Link pairing intents found in scanned QR codes.
    private val pcLinkRepository = PcLinkRepository(applicationContext)

    private val _autoDetectEnabled = MutableStateFlow(true)
    val autoDetectEnabled: StateFlow<Boolean> = _autoDetectEnabled.asStateFlow()

    private val _captureMode =
        MutableStateFlow(nopalito.app.ui.screens.settings.CaptureMode.BATCH)
    val captureMode: StateFlow<nopalito.app.ui.screens.settings.CaptureMode> =
        _captureMode.asStateFlow()

    /** Human-readable summary of the bound camera, e.g. "0.6x · HD" (debug only). */
    private val _boundCameraInfo = MutableStateFlow<String?>(null)
    val boundCameraInfo: StateFlow<String?> = _boundCameraInfo.asStateFlow()

    fun setAutoDetectEnabled(enabled: Boolean) {
        _autoDetectEnabled.value = enabled
    }

    fun setCaptureMode(mode: nopalito.app.ui.screens.settings.CaptureMode) {
        _captureMode.value = mode
    }

    /** Called by the camera binding with the lens/quality actually in use. */
    fun setBoundCameraInfo(info: String?) {
        _boundCameraInfo.value = info
    }

    private val _events = MutableSharedFlow<CameraEvent>()
    val events = _events.asSharedFlow()

    private var _liveAnalysisState = MutableStateFlow(LiveAnalysisState())
    val liveAnalysisState: StateFlow<LiveAnalysisState> = _liveAnalysisState.asStateFlow()
    private val liveAnalyzer = LiveDocumentAnalyzer(imageSegmentationService)

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState
    private var importJob: Job? = null

    private val _isTorchEnabled = MutableStateFlow(false)
    val isTorchEnabled: StateFlow<Boolean> = _isTorchEnabled

    // QR / barcode detection runs always-on over the live camera: no mode
    // toggle, brackets and the compact popup appear whenever codes are in
    // view, next to the normal document pipeline.
    private val _qrDetectionState = MutableStateFlow(QrDetectionState())
    val qrDetectionState: StateFlow<QrDetectionState> = _qrDetectionState.asStateFlow()

    /** QR codes found in imported photos; non-null opens the info sheet. */
    private val _stillQrResults = MutableStateFlow<List<StillQrResult>?>(null)
    val stillQrResults: StateFlow<List<StillQrResult>?> = _stillQrResults.asStateFlow()

    /** Closes the imported-photo QR sheet. */
    fun dismissStillQr() {
        _stillQrResults.value = null
    }

    /**
     * Imports one QR-carrying photo as a document page anyway (a sheet with
     * a QR on it is both things at once). Removes it from the pending sheet
     * list; the rest stay open.
     */
    fun importStillAsPage(item: QrSheetItem) {
        val results = _stillQrResults.value ?: return
        val target = results.firstOrNull { it.item == item } ?: return
        _stillQrResults.value = (results - target).ifEmpty { null }
        viewModelScope.launch {
            _importState.value = ImportState.Importing(0, 1)
            try {
                importPhotoAsPage(target.sourceUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Import", "Failed to import QR photo as page: ${target.sourceUri}", e)
                _events.emit(
                    CameraEvent.ImportError(
                        applicationContext.stringFor(
                            R.string.import_error,
                            AppLocaleOverride.locale
                        )
                    )
                )
            }
            _importState.value = ImportState.Idle
        }
    }

    private val qrTracker = QrDetectionTracker()
    private val savedQrContents = ConcurrentHashMap.newKeySet<String>()
    private var qrTickerJob: Job? = null

    // Cloud Link: a scanned QR that authorizes a web session, not a scan
    // Payload parsed from the QR content; non-null shows the approval dialog.
    private val _linkQrDetected = MutableStateFlow<QrLinkPayload?>(null)
    val linkQrDetected: StateFlow<QrLinkPayload?> = _linkQrDetected.asStateFlow()

    private val _linkApproving = MutableStateFlow(false)
    val linkApproving: StateFlow<Boolean> = _linkApproving.asStateFlow()

    /** Set once the backend approves the intent (drives the success dialog). */
    private val _linkApproved = MutableStateFlow(false)
    val linkApproved: StateFlow<Boolean> = _linkApproved.asStateFlow()

    private val _linkApprovalError = MutableStateFlow<PcLinkError?>(null)
    val linkApprovalError: StateFlow<PcLinkError?> = _linkApprovalError.asStateFlow()

    private var linkApproveJob: Job? = null

    private val qrAnalyzer = QrAnalyzer(
        shouldCaptureBitmap = { candidate ->
            QrLinkPayload.parse(candidate.content) == null &&
                    candidate.content !in savedQrContents
        },
        onFrame = { frame, bitmap ->
            // Heavy per-frame work (link scan over N candidates, tracker
            // matching/scoring) runs on Default: 10 codes must never block
            // the main thread. StateFlow emission is thread-safe.
            viewModelScope.launch(Dispatchers.Default) { onQrFrame(frame, bitmap) }
        },
        onError = { error ->
            logger.e("Camera", "QR analysis failed", error)
        },
    )
    private val qrScansDir = File(appContainer.applicationContext.filesDir, "qr_scans")
    private val qrScanRepository = appContainer.qrScanRepository
    private val cloudScanUploader = appContainer.cloudScanUploader

    /** Clears every live QR track, e.g. when rotating the screen. */
    fun resetQrDetection() {
        qrTracker.reset()
        _qrDetectionState.value = QrDetectionState()
        qrTickerJob?.cancel()
        qrTickerJob = null
    }

    /** Closes the Cloud Link approval dialog (blocked while a request is in flight). */
    fun dismissLinkQr() {
        if (_linkApproving.value) return
        _linkQrDetected.value = null
        _linkApprovalError.value = null
        _linkApproved.value = false
    }

    /**
     * Approves the Cloud Link session contained in the detected QR payload.
     * Mirrors PcLinkViewModel.onQrDetected: expiry check locally, signature
     * verification server-side, pairing receipt persisted on success.
     */
    fun approveLinkQr() {
        val payload = _linkQrDetected.value ?: return
        if (_linkApproving.value || _linkApproved.value) return
        if (payload.isExpired()) {
            _linkApprovalError.value = PcLinkError.IntentExpired()
            return
        }
        linkApproveJob?.cancel()
        linkApproveJob = viewModelScope.launch {
            _linkApproving.value = true
            _linkApprovalError.value = null
            pcLinkRepository.approveQr(payload.intentId, payload.nonce).fold(
                onSuccess = {
                    pcLinkRepository.saveLinkSession(payload.intentId, payload.nonce)
                    _linkApproved.value = true
                },
                onFailure = { e ->
                    _linkApprovalError.value = (e as? PcLinkError) ?: PcLinkError.Unknown(null)
                }
            )
            _linkApproving.value = false
        }
    }

    // --- Phase 1 file capture state (declared before init: init launches
    // collectors that touch these flows) ---

    private val captureGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private var fileCaptureJob: Job? = null
    private val pendingTempFiles = java.util.Collections.synchronizedMap(mutableMapOf<Long, File>())

    private val _captureTier =
        MutableStateFlow(nopalito.app.domain.CaptureTier.BALANCED)
    val captureTier: StateFlow<nopalito.app.domain.CaptureTier> = _captureTier.asStateFlow()

    private val _captureError = MutableStateFlow<CaptureFileError?>(null)
    val captureError: StateFlow<CaptureFileError?> = _captureError.asStateFlow()

    private val _pipelineFlags =
        MutableStateFlow(nopalito.app.domain.ScanPipelineFlags.Phase1Defaults)
    val pipelineFlags: StateFlow<nopalito.app.domain.ScanPipelineFlags> =
        _pipelineFlags.asStateFlow()

    private val pendingRequestedAt =
        java.util.Collections.synchronizedMap(mutableMapOf<Long, Long>())

    private val _lastCaptureDiag = MutableStateFlow<String?>(null)
    val lastCaptureDiag: StateFlow<String?> = _lastCaptureDiag.asStateFlow()

    init {
        viewModelScope.launch {
            _captureTier.value = settingsRepository.captureTier.first()
        }
        viewModelScope.launch {
            settingsRepository.pipelineFlags.collect { _pipelineFlags.value = it }
        }
    }

    private val _volumeKeyEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val volumeKeyEvent = _volumeKeyEvent.asSharedFlow()

    fun onVolumeKeyPressed() {
        _volumeKeyEvent.tryEmit(Unit)
    }

    fun resetLiveAnalysis() {
        liveAnalyzer.reset()
        _liveAnalysisState.value = LiveAnalysisState()
    }

    fun onCapturePressed(frozenImage: Bitmap) {
        _captureState.value = CaptureState.Capturing(frozenImage)
        resetLiveAnalysis()
    }


    fun setCaptureTier(tier: nopalito.app.domain.CaptureTier) {
        _captureTier.value = tier
        viewModelScope.launch { settingsRepository.setCaptureTier(tier) }
    }

    fun setKeepOriginal(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepOriginal(enabled) }
    }

    fun setHighQualityCapture(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHighQualityCapture(enabled) }
    }

    fun dismissCaptureError() {
        _captureError.value = null
    }

    fun reportInsufficientStorage(
        required: Long,
        free: Long,
        tier: nopalito.app.domain.CaptureTier
    ) {
        _captureError.value = CaptureFileError.InsufficientStorage(required, free, tier)
    }

    /**
     * Starts a file capture. Returns the generation id. CameraX file capture
     * itself is not cancellable, so [cancelFileCapture] cancels post
     * processing and marks late callbacks stale via the generation check in
     * [onFileSaved]/[onFileError]: stale callbacks clean only their own temp
     * file and never publish a page nor delete another capture's original.
     */
    fun beginFileCapture(
        frozenImage: Bitmap,
        tier: nopalito.app.domain.CaptureTier,
        tempFile: File,
    ): Long {
        fileCaptureJob?.cancel()
        val id = captureGeneration.incrementAndGet()
        pendingTempFiles[id] = tempFile
        pendingRequestedAt[id] = android.os.SystemClock.elapsedRealtime()
        onCapturePressed(frozenImage)
        return id
    }

    fun cancelFileCapture() {
        fileCaptureJob?.cancel()
        fileCaptureJob = null
        // Invalidate pending generations so late CameraX callbacks go stale.
        captureGeneration.incrementAndGet()
        if (_captureState.value is CaptureState.Capturing) {
            _captureState.value = CaptureState.Idle
        }
    }

    fun onFileSaved(
        captureId: Long,
        file: File,
        opticalMeasures: OpticalMeasures?,
        tier: nopalito.app.domain.CaptureTier,
        cameraId: String?,
        keepOriginal: Boolean,
        frame: CameraCaptureController.FrameSnapshot? = null,
    ) {
        pendingTempFiles.remove(captureId)
        val requestedAt = pendingRequestedAt.remove(captureId)
        if (captureId != captureGeneration.get()) {
            // Stale callback after cancel/new capture: clean only its file.
            runCatching { if (file.name.startsWith(".tmp-capture-")) file.delete() }
            return
        }
        fileCaptureJob?.cancel()
        fileCaptureJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val tCaptureMs = requestedAt?.let { android.os.SystemClock.elapsedRealtime() - it }
                val tProc0 = android.os.SystemClock.elapsedRealtime()
                val page = processFileCapture(
                    file, opticalMeasures, tier, cameraId, keepOriginal,
                    captureId, frame, tCaptureMs,
                    requestedResolutionFor(tier),
                )
                val tProcMs = android.os.SystemClock.elapsedRealtime() - tProc0
                page.captureDiag?.let { diag ->
                    _lastCaptureDiag.value = diag.copy(processMs = tProcMs).toDebugLine()
                    Log.i("CaptureDiag", diag.copy(processMs = tProcMs).toLogLine())
                }
                ensureActive()
                if (captureId != captureGeneration.get()) {
                    // Cancelled while processing: keep moved original, drop preview.
                    return@launch
                }
                onCaptureProcessed(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Camera", "File capture processing failed", e)
                // Original already moved is preserved; only preview fails.
                _captureError.value = CaptureFileError.ProcessingFailed(e.message)
                onCaptureProcessed(null)
            }
        }
    }

    fun onFileError(captureId: Long, tempFile: File?, error: Throwable) {
        pendingTempFiles.remove(captureId)
        if (captureId != captureGeneration.get()) {
            tempFile?.let { runCatching { if (it.name.startsWith(".tmp-capture-")) it.delete() } }
            return
        }
        logger.e("Camera", "File capture failed", error)
        tempFile?.let { runCatching { if (it.name.startsWith(".tmp-capture-")) it.delete() } }
        onCaptureProcessed(null)
    }

    private fun afStateNameOf(af: Int): String = when (af) {
        android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
        android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
        android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
        android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
        android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUS_LOCKED"
        android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
        android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
        else -> "UNKNOWN($af)"
    }

    private fun requestedResolutionFor(tier: nopalito.app.domain.CaptureTier): String =
        when (tier) {
            nopalito.app.domain.CaptureTier.LOW -> "1920x1440"
            nopalito.app.domain.CaptureTier.BALANCED -> "3264x2448"
            nopalito.app.domain.CaptureTier.HIGH -> "4032x3024"
            nopalito.app.domain.CaptureTier.ORIGINAL -> "4032x3024"
        }

    private suspend fun processFileCapture(
        file: File,
        opticalMeasures: OpticalMeasures?,
        tier: nopalito.app.domain.CaptureTier,
        cameraId: String?,
        keepOriginal: Boolean,
        captureId: Long,
        frame: CameraCaptureController.FrameSnapshot?,
        tCaptureMs: Long?,
        requestedResolution: String,
    ): CapturedPage {
        currentCoroutineContext().ensureActive()
        val bounds = nopalito.app.platform.FileCapturePipeline.decodeBounds(file)
            ?: throw IOException("invalid capture file")
        currentCoroutineContext().ensureActive()
        val exif = nopalito.app.platform.FileCapturePipeline.readExif(file, null)
        val effective = nopalito.app.domain.resolveEffectiveOrientation(
            0, exif.orientation, bounds.width, bounds.height,
        )
        val workingLong = tier.workingLongSidePx
        val sample = nopalito.app.platform.FileCapturePipeline.sampleSizeForLongSide(
            nopalito.app.platform.FileCapturePipeline.Bounds(bounds.width, bounds.height),
            workingLong,
        )
        val workingOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val working = android.graphics.BitmapFactory.decodeFile(file.absolutePath, workingOpts)
            ?: throw IOException("decode failed")
        try {
            currentCoroutineContext().ensureActive()
            val segInput = if (maxOf(working.width, working.height) > workingLong) {
                val s = workingLong.toFloat() / maxOf(working.width, working.height)
                working.scale((working.width * s).toInt(), (working.height * s).toInt())
            } else working
            val workingQuality =
                nopalito.app.platform.FileCapturePipeline.measureWorkingQuality(segInput)
            val workingW = segInput.width
            val workingH = segInput.height
            val segmentation = try {
                imageSegmentationService.runSegmentationAndReturn(segInput)
            } finally {
                if (segInput !== working) segInput.recycle()
            }
            currentCoroutineContext().ensureActive()
            val mask = segmentation?.segmentation
            // Exactly one full-res Bitmap is alive at a time: working is
            // recycled before the full decode below.
            if (!working.isRecycled) working.recycle()
            currentCoroutineContext().ensureActive()
            val fullOpts = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val full = android.graphics.BitmapFactory.decodeFile(file.absolutePath, fullOpts)
                ?: throw IOException("full decode failed")
            try {
                currentCoroutineContext().ensureActive()
                val originalSize = ImageSize(full.width, full.height)
                val quad = mask?.let { detectDocumentQuad(it, originalSize, Mode.CAPTURE) }
                val defaultColorMode = settingsRepository.defaultColorMode.first()
                // Same values as the legacy path (BALANCED 2 MP, same warp and
                // enhance): only the input source changed from memory to file.
                val fileResult = withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    nopalito.app.platform.extractPageFromBitmapNoCopy(
                        full, quad, effective.degrees, mask, defaultColorMode, opticalMeasures,
                    )
                }
                currentCoroutineContext().ensureActive()
                val sha = runCatching {
                    nopalito.app.domain.OriginalIntegrity.sha256(file)
                }.getOrNull()
                val afName = frame?.afState?.let { afStateNameOf(it) } ?: "unavailable"
                val diag = nopalito.app.domain.CaptureDiag(
                    captureId = captureId,
                    captureTier = tier.name,
                    cameraId = cameraId,
                    focalLengthMm = frame?.focalMm
                        ?: opticalMeasures?.cameraIntrinsics?.focalLength,
                    zoomRatio = frame?.zoomRatio,
                    requestedResolution = requestedResolution,
                    deliveredResolution = "${bounds.width}x${bounds.height}",
                    iso = frame?.iso,
                    exposureNs = frame?.exposureNs,
                    focusDistanceDiopters = frame?.focusDiopters,
                    afState = afName,
                    aeState = frame?.aeState?.let { "ae($it)" } ?: "unavailable",
                    flashMode = "OFF",
                    cropRegion = frame?.crop,
                    rotationDegrees = effective.degrees,
                    exifOrientation = exif.orientation,
                    effectiveOrientation = effective.name,
                    jpegBytes = runCatching { file.length() }.getOrNull(),
                    sha12 = sha?.take(12),
                    captureMs = tCaptureMs,
                    processMs = null,
                    laplacianVar = workingQuality?.laplacianVar,
                    meanLuma = workingQuality?.meanLuma,
                    saturatedPct = workingQuality?.saturatedPct,
                )
                val shouldKeep = keepOriginal || tier == nopalito.app.domain.CaptureTier.ORIGINAL
                val deferredSource = kotlinx.coroutines.CompletableDeferred(
                    nopalito.app.domain.Jpeg(file.readBytes())
                )
                return CapturedPage(
                    pageJpeg = fileResult.pageJpeg,
                    sourceJpeg = deferredSource,
                    metadata = fileResult.metadata,
                    colorMode = fileResult.colorMode,
                    originalFile = if (shouldKeep) file else null,
                    originalSha256 = sha,
                    captureTier = tier,
                    processingStatus = nopalito.app.domain.ProcessingStatus.PROCESSED,
                    capturedWidth = bounds.width,
                    capturedHeight = bounds.height,
                    workingWidth = workingW,
                    workingHeight = workingH,
                    processedWidth = fileResult.outputWidth,
                    processedHeight = fileResult.outputHeight,
                    captureDiag = diag,
                    cameraId = cameraId,
                    exifOrientation = exif.orientation,
                )
            } finally {
                if (!full.isRecycled) full.recycle()
            }
        } finally {
            runCatching { if (!working.isRecycled) working.recycle() }
        }
    }

    override fun onCleared() {
        fileCaptureJob?.cancel()
        qrAnalyzer.close()
        liveAnalyzer.release()
    }

    private fun onCaptureProcessed(captured: CapturedPage?) {
        val current = _captureState.value
        _captureState.value = when {
            current is CaptureState.Capturing && captured != null ->
                CaptureState.CapturePreview(current.frozenImage, captured)

            current is CaptureState.Capturing ->
                CaptureState.CaptureError(current.frozenImage)

            else -> CaptureState.Idle
        }
    }

    /**
     * Runs on the dedicated ImageAnalysis executor. One frame feeds both
     * pipelines: the QR analyzer borrows the pixels first (detached bitmap
     * copy, never closes), then the document analyzer runs synchronously and
     * always closes the proxy, so CameraX `STRATEGY_KEEP_ONLY_LATEST`
     * coalesces frames while this one is processed.
     */
    fun liveAnalysis(imageProxy: ImageProxy) {
        if (_captureState.value !is CaptureState.Idle || _importState.value !is ImportState.Idle) {
            imageProxy.close()
            return
        }
        if (_linkQrDetected.value == null && !_linkApproving.value) {
            qrAnalyzer.analyze(imageProxy)
        }
        _liveAnalysisState.value = liveAnalyzer.analyzeFrame(imageProxy)
    }

    /**
     * Applies one live analysis frame to the tracker. Runs on Default, never
     * on the main thread (see [qrAnalyzer] onFrame): StateFlow writes and the
     * concurrent saved-set are thread-safe, and the UI reads snapshots.
     * A Cloud Link QR short-circuits the normal scan flow; otherwise the
     * primary tracked code is persisted once per content.
     */
    private fun onQrFrame(frame: QrFrame, bitmap: Bitmap?) {
        if (_linkQrDetected.value != null ||
            _linkApproving.value
        ) {
            bitmap?.recycle()
            return
        }

        val link = frame.candidates.firstNotNullOfOrNull { candidate ->
            QrLinkPayload.parse(candidate.content)
        }
        if (link != null) {
            if (_linkQrDetected.value == null) {
                _linkApprovalError.value = null
                _linkQrDetected.value = link
            }
            resetQrDetection()
            bitmap?.recycle()
            return
        }

        val state = qrTracker.update(frame)
        _qrDetectionState.value = state
        ensureQrTicker()

        val primary = state.primary
        if (primary == null || primary.content in savedQrContents) {
            bitmap?.recycle()
            return
        }
        if (bitmap == null) return
        savedQrContents.add(primary.content)
        saveQrScan(QrDetected(primary.content, primary.format, bitmap, primary.type))
    }

    /**
     * Keeps age-dependent state fresh (primary selection, fade/removal) while
     * no new camera frame arrives. 5 Hz is plenty: the overlay springs
     * interpolate to display rate and fades are Compose-animated, so ticking
     * faster only burns composition with many codes.
     */
    private fun ensureQrTicker() {
        if (qrTickerJob?.isActive == true) return
        qrTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(200)
                val state = qrTracker.snapshot(android.os.SystemClock.elapsedRealtime())
                _qrDetectionState.value = state
                if (state.tracks.isEmpty()) break
            }
            qrTickerJob = null
        }
    }

    private fun saveQrScan(detected: QrDetected) {
        viewModelScope.launch(Dispatchers.IO) {
            val imagePath = try {
                qrScansDir.mkdirs()
                val file = File(qrScansDir, "scan_${System.currentTimeMillis()}.jpg")
                file.outputStream()
                    .use { detected.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                file.absolutePath
            } catch (_: Exception) {
                null
            }
            qrScanRepository.insert(
                QrScanEntity(
                    content = detected.content,
                    dateTime = System.currentTimeMillis(),
                    format = detected.format,
                    imagePath = imagePath,
                    typeData = encodeQrType(detected.type),
                )
            )
            // Also push to the cloud history when the user is authenticated.
            cloudScanUploader.upload(detected, imagePath)
        }
    }

    /**
     * QR-first import: an imported photo is checked for codes before anything
     * else. A Cloud Link payload opens the approval dialog; plain codes open
     * the info sheet directly ([stillQrItems]) and are saved to history.
     * Anything without codes keeps the old path into the document editor.
     */
    private suspend fun detectQrInBitmap(bitmap: Bitmap): List<QrCandidate> =
        suspendCancellableCoroutine { cont ->
            qrAnalyzer.analyzeStill(bitmap) { candidates ->
                if (!cont.isCompleted) cont.resume(candidates) { cause, _, _ -> }
            }
        }

    private fun handleStillQr(candidates: List<QrCandidate>, bitmap: Bitmap, uri: Uri) {
        if (_linkQrDetected.value != null || _linkApproving.value) return
        val link = candidates.firstNotNullOfOrNull { candidate ->
            QrLinkPayload.parse(candidate.content)
        }
        if (link != null) {
            if (_linkQrDetected.value == null) {
                _linkApprovalError.value = null
                _linkQrDetected.value = link
            }
            return
        }
        val items = candidates
            .filter { it.content.isNotBlank() }
            .distinctBy { it.content }
            .map { QrSheetItem(it.content, it.format, it.type) }
        if (items.isEmpty()) return
        _stillQrResults.value = (
                _stillQrResults.value.orEmpty() +
                        items.map { StillQrResult(it, uri) }
                ).distinctBy { it.item.content }
        val primary = items.first()
        if (savedQrContents.add(primary.content)) {
            saveQrScan(QrDetected(primary.content, primary.format, bitmap, primary.type))
        }
    }

    fun onImageCaptured(imageProxy: ImageProxy?, opticalMeasures: OpticalMeasures?) {
        if (imageProxy != null) {
            viewModelScope.launch {
                try {
                    val source = imageProxy.toBitmap()
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val captureStart = System.currentTimeMillis()
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "Capture",
                            "capture: ${imageProxy.width}x${imageProxy.height} format=${imageProxy.format} rot=${rotationDegrees} camera=${_boundCameraInfo.value ?: "?"}"
                        )
                    }
                    val page =
                        processCapturedImage(source, rotationDegrees, opticalMeasures, Mode.CAPTURE)
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "Capture",
                            "processed: ${page.pageJpeg.bytes.size} bytes in ${System.currentTimeMillis() - captureStart}ms"
                        )
                    }
                    onCaptureProcessed(page)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e("Camera", "Failed to process captured image", e)
                    onCaptureProcessed(null)
                } finally {
                    runCatching { imageProxy.close() }
                }
            }
        } else {
            onCaptureProcessed(null)
        }
    }

    private suspend fun processCapturedImage(
        source: Bitmap,
        rotationDegrees: Int,
        opticalMeasures: OpticalMeasures?,
        mode: Mode,
    ): CapturedPage = withContext(Dispatchers.IO) {
        // Downscale for segmentation to keep shutter-to-preview under ~300ms.
        // The final extract still uses the full-res source for quality.
        val segmentationInput = if (source.width > 1280 || source.height > 1280) {
            val scale = 1280f / maxOf(source.width, source.height)
            source.scale((source.width * scale).toInt(), (source.height * scale).toInt())
        } else source
        val segmentation = imageSegmentationService.runSegmentationAndReturn(segmentationInput)
        if (segmentationInput !== source) segmentationInput.recycle()
        val mask = segmentation?.segmentation
        val originalSize = ImageSize(source.width, source.height)
        val quad = mask?.let { detectDocumentQuad(mask, originalSize, mode) }
        val defaultColorMode = settingsRepository.defaultColorMode.first()
        val result = extractDocumentFromBitmap(
            source, quad, rotationDegrees, mask, viewModelScope, defaultColorMode, opticalMeasures
        )
        return@withContext result
    }

    fun addProcessedImage() {
        val current = _captureState.value
        if (current is CaptureState.CapturePreview) {
            viewModelScope.launch {
                _events.emit(CameraEvent.ImageCaptured(current.capturedPage))
                runCatching { statsRepository.logPhotoCaptured(source = "camera") }
                analyticsTracker.scanCompleted(source = "camera", pages = 1)
            }
        }
        _captureState.value = CaptureState.Idle
    }

    fun afterCaptureError() {
        _captureState.value = CaptureState.Idle
    }

    fun logError(message: String, throwable: Throwable) {
        viewModelScope.launch {
            logger.e("Camera", message, throwable)
        }
    }

    fun setTorchEnabled(enabled: Boolean) {
        _isTorchEnabled.value = enabled
    }

    fun importPhotos(uris: List<Uri>) {
        importJob?.cancel()
        if (uris.isEmpty()) {
            _importState.value = ImportState.Idle
            return
        }
        importJob = viewModelScope.launch {
            _importState.value = ImportState.Importing(0, uris.size)
            uris.forEachIndexed { index, uri ->
                ensureActive()
                try {
                    importPickedFile(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e("Import", "Failed to import file: $uri", e)
                    _events.emit(
                        CameraEvent.ImportError(
                            applicationContext.stringFor(
                                R.string.import_error,
                                AppLocaleOverride.locale
                            )
                        )
                    )
                }
                _importState.value = ImportState.Importing(index + 1, uris.size)
            }
            _importState.value = ImportState.Idle
        }
    }

    /** Routes a picked file to the matching import path (photo, PDF or Word). */
    private suspend fun importPickedFile(uri: Uri) {
        val name = queryDisplayName(applicationContext, uri) ?: ""
        val mime = applicationContext.contentResolver.getType(uri) ?: ""
        when {
            isImageFile(mime, name) -> importImage(uri)
            isPdfFile(mime, name) -> importPdf(uri)
            else -> importDocument(uri, name)
        }
    }

    private fun isImageFile(mime: String, name: String): Boolean =
        mime.startsWith("image/") ||
                listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".heic", ".heif")
                    .any { name.endsWith(it, ignoreCase = true) }

    private fun isPdfFile(mime: String, name: String): Boolean =
        mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)

    private suspend fun importImage(uri: Uri) {
        val photoToImport = imageLoader.load(uri)
        currentCoroutineContext().ensureActive()
        // QR-first: a photo with codes opens the QR info sheet instead of
        // landing in the document; anything else is imported as a page.
        val candidates = detectQrInBitmap(photoToImport)
        currentCoroutineContext().ensureActive()
        if (candidates.isNotEmpty()) {
            handleStillQr(candidates, photoToImport, uri)
        } else {
            importPhotoAsPage(uri, photoToImport)
        }
    }

    /** Shared document-page import path (used with a pre-loaded bitmap). */
    private suspend fun importPhotoAsPage(uri: Uri, preloaded: Bitmap? = null) {
        val photo = preloaded ?: imageLoader.load(uri)
        currentCoroutineContext().ensureActive()
        val page = processCapturedImage(photo, 0, null, Mode.IMPORT)
        currentCoroutineContext().ensureActive()
        _events.emit(CameraEvent.ImageCaptured(page))
        runCatching { statsRepository.logPhotoCaptured(source = "import") }
    }

    /** Imports a PDF as document pages (one rendered bitmap per PDF page). */
    private suspend fun importPdf(uri: Uri) {
        val pdf = copyUriToCache(uri, "import_pdf")
        try {
            if (isPdfEncrypted(pdf)) {
                _events.emit(
                    CameraEvent.ImportError(
                        applicationContext.stringFor(
                            R.string.import_pdf_protected,
                            AppLocaleOverride.locale
                        )
                    )
                )
                return
            }
            importPdfPages(pdf)
        } finally {
            pdf.delete()
        }
    }

    /**
     * Imports a Word document: converts it to PDF through the shared cloud
     * endpoint (same one used by the Convert tool) and imports the pages.
     */
    private suspend fun importDocument(uri: Uri, name: String) {
        val file = copyUriToCache(uri, "import_doc")
        try {
            if (isEncryptedOle2(file)) {
                _events.emit(
                    CameraEvent.ImportError(
                        applicationContext.stringFor(
                            R.string.import_document_protected,
                            AppLocaleOverride.locale
                        )
                    )
                )
                return
            }
            cloudConversionRepository.previewToPdf(file, name).onSuccess { pdf ->
                try {
                    importPdfPages(pdf)
                } finally {
                    pdf.delete()
                }
            }.onFailure { e ->
                logger.e("Import", "Word to PDF conversion failed: $name", e)
                _events.emit(
                    CameraEvent.ImportError(
                        applicationContext.stringFor(
                            R.string.import_document_failed,
                            AppLocaleOverride.locale
                        )
                    )
                )
            }
        } finally {
            file.delete()
        }
    }

    /** Renders every page of [pdf] and adds it to the document. */
    private suspend fun importPdfPages(pdf: File) = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                for (pageIndex in 0 until renderer.pageCount) {
                    currentCoroutineContext().ensureActive()
                    val pageBitmap = PdfPreviewRenderer.renderPage(
                        renderer, pageIndex, IMPORT_PDF_TARGET_WIDTH_PX
                    ) ?: throw IOException("PDF page $pageIndex could not be rendered")
                    // extractDocumentFromBitmap reads the bitmap asynchronously
                    // (source JPEG), so it must not be recycled: give it a copy.
                    val importBitmap =
                        pageBitmap.copy(Bitmap.Config.ARGB_8888, false) ?: pageBitmap
                    if (importBitmap !== pageBitmap) pageBitmap.recycle()
                    val captured = extractDocumentFromBitmap(
                        importBitmap, null, 0, null, viewModelScope, DefaultColorMode.AUTO, null
                    )
                    _events.emit(CameraEvent.ImageCaptured(captured))
                    runCatching { statsRepository.logPhotoCaptured(source = "pdf_import") }
                }
            }
        }
    }

    /** Copies the content of [uri] to a fresh file in the app cache. */
    private suspend fun copyUriToCache(uri: Uri, prefix: String): File =
        withContext(Dispatchers.IO) {
            val input = applicationContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open $uri")
            val target =
                File(applicationContext.cacheDir, "${prefix}_${System.currentTimeMillis()}")
            input.use { ins -> target.outputStream().use { out -> ins.copyTo(out) } }
            target
        }

    fun onImportClicked() {
        _importState.value = ImportState.Selecting
        resetLiveAnalysis()
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _importState.value = ImportState.Idle
    }
}

sealed class CaptureState {
    open val frozenImage: Bitmap? = null

    object Idle : CaptureState()
    data class Capturing(override val frozenImage: Bitmap) : CaptureState()
    data class CaptureError(override val frozenImage: Bitmap) : CaptureState()
    data class CapturePreview(
        override val frozenImage: Bitmap,
        val capturedPage: CapturedPage,
    ) : CaptureState()
}

fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(angle)
    return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true)
}
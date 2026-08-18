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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import nopalito.app.AppContainer
import nopalito.app.R
import nopalito.app.domain.CapturedPage
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.platform.extractDocumentFromBitmap
import nopalito.app.ui.isEncryptedOle2
import nopalito.app.ui.isPdfEncrypted
import nopalito.app.ui.screens.cloud.data.CloudConversionRepository
import nopalito.app.ui.screens.qr.QrDetected
import nopalito.app.ui.screens.qr.QrScanEntity
import nopalito.app.ui.screens.qr.encodeQrType
import nopalito.app.ui.screens.qr.formatName
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

/** Pixel width used to render imported PDF pages before adding them to the document. */
private const val IMPORT_PDF_TARGET_WIDTH_PX = 1600

sealed interface CameraEvent {
    data class ImageCaptured(val page: CapturedPage) : CameraEvent

    /** A picked file could not be imported ([message] is already localized). */
    data class ImportError(val message: String) : CameraEvent
}

class CameraViewModel(appContainer: AppContainer) : ViewModel() {

    private val imageSegmentationService = appContainer.imageSegmentationService
    private val settingsRepository = appContainer.settingsRepository
    private val imageLoader = appContainer.imageLoader
    private val logger = appContainer.logger
    private val applicationContext = appContainer.applicationContext
    private val cloudConversionRepository = CloudConversionRepository(applicationContext)

    private val _autoDetectEnabled = MutableStateFlow(true)
    val autoDetectEnabled: StateFlow<Boolean> = _autoDetectEnabled.asStateFlow()

    private val _captureMode =
        MutableStateFlow(nopalito.app.ui.screens.settings.CaptureMode.BATCH)
    val captureMode: StateFlow<nopalito.app.ui.screens.settings.CaptureMode> =
        _captureMode.asStateFlow()

    fun setAutoDetectEnabled(enabled: Boolean) {
        _autoDetectEnabled.value = enabled
    }

    fun setCaptureMode(mode: nopalito.app.ui.screens.settings.CaptureMode) {
        _captureMode.value = mode
    }

    private val _events = MutableSharedFlow<CameraEvent>()
    val events = _events.asSharedFlow()

    private var _liveAnalysisState = MutableStateFlow(LiveAnalysisState())
    val liveAnalysisState: StateFlow<LiveAnalysisState> = _liveAnalysisState.asStateFlow()
    private var quadStabilizer = QuadStabilizer()

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState
    private var importJob: Job? = null

    private val _isTorchEnabled = MutableStateFlow(false)
    val isTorchEnabled: StateFlow<Boolean> = _isTorchEnabled

    // ── QR / barcode scan mode: in-place on the home camera (like INE mode) ──
    private val _qrScanMode = MutableStateFlow(false)
    val qrScanMode: StateFlow<Boolean> = _qrScanMode.asStateFlow()

    private val _qrDetected = MutableStateFlow<QrDetected?>(null)
    val qrDetected: StateFlow<QrDetected?> = _qrDetected.asStateFlow()

    private val qrScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
            ).build()
    )
    private val qrScansDir = File(appContainer.applicationContext.filesDir, "qr_scans")
    private val qrScanRepository = appContainer.qrScanRepository
    private val cloudScanUploader = appContainer.cloudScanUploader

    fun setQrScanMode(enabled: Boolean) {
        _qrScanMode.value = enabled
        if (enabled) resetLiveAnalysis()
        if (!enabled) _qrDetected.value = null
        viewModelScope.launch {
            settingsRepository.setQrScanModeEnabled(enabled)
        }
    }

    fun dismissQrResult() {
        _qrDetected.value = null
    }

    init {
        viewModelScope.launch {
            _qrScanMode.value = settingsRepository.qrScanModeEnabled.first()
        }
    }

    private val _volumeKeyEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val volumeKeyEvent = _volumeKeyEvent.asSharedFlow()

    fun onVolumeKeyPressed() {
        _volumeKeyEvent.tryEmit(Unit)
    }

    fun resetLiveAnalysis() {
        quadStabilizer = QuadStabilizer()
        _liveAnalysisState.value = LiveAnalysisState()
    }

    fun onCapturePressed(frozenImage: Bitmap) {
        _captureState.value = CaptureState.Capturing(frozenImage)
        resetLiveAnalysis()
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

    fun liveAnalysis(imageProxy: ImageProxy) {
        if (_captureState.value !is CaptureState.Idle || _importState.value !is ImportState.Idle) {
            imageProxy.close()
            return
        }
        if (_qrScanMode.value) {
            analyzeQrFrame(imageProxy)
            return
        }

        viewModelScope.launch {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val startTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                imageSegmentationService.runSegmentationAndReturn(imageProxy.toBitmap())
            }
            val segTime = System.currentTimeMillis() - startTime

            result?.let {
                val segmentation = result.segmentation
                val maskSize = segmentation.maskSize()
                val originalSize = ImageSize(imageProxy.width, imageProxy.height)
                Log.d(
                    "CameraVM",
                    "Segmentation completed in ${segTime}ms, maskSize=${maskSize}, originalSize=${originalSize}, rotationDegrees=${rotationDegrees}"
                )

                val rawQuad = withContext(Dispatchers.Default) {
                    val quad = detectDocumentQuad(segmentation, originalSize, Mode.LIVE_ANALYSIS)
                    Log.d(
                        "CameraVM",
                        "detectDocumentQuad returned: ${if (quad != null) "Quad(${quad.topLeft}, ${quad.topRight}, ${quad.bottomRight}, ${quad.bottomLeft})" else "null"}"
                    )
                    quad?.rotate90(rotationDegrees / 90, maskSize)
                }
                Log.d(
                    "CameraVM",
                    "rawQuad after rotate90: ${if (rawQuad != null) "Quad(${rawQuad.topLeft}, ${rawQuad.topRight}, ${rawQuad.bottomRight}, ${rawQuad.bottomLeft})" else "null"}"
                )

                val binaryMaskProvider = {
                    var binaryMask: Bitmap = segmentation.toBinaryMask()
                    if (rotationDegrees != 0) {
                        binaryMask = rotateBitmap(binaryMask, rotationDegrees.toFloat())
                    }
                    binaryMask
                }
                val stableQuad =
                    quadStabilizer.update(rawQuad, maskSize.width.toInt(), maskSize.height.toInt())
                Log.d(
                    "CameraVM",
                    "stableQuad: ${if (stableQuad != null) "PRESENT" else "null"}, autoDetect=${_autoDetectEnabled.value}"
                )

                _liveAnalysisState.value = LiveAnalysisState(
                    inferenceTime = result.inferenceTime,
                    binaryMaskProvider = binaryMaskProvider,
                    maskSize = maskSize,
                    stableQuad = stableQuad,
                )
            }

            imageProxy.close()
        }
    }

    private fun analyzeQrFrame(imageProxy: ImageProxy) {
        if (_qrDetected.value != null) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        qrScanner.process(input)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull() ?: return@addOnSuccessListener
                val content = barcode.rawValue ?: barcode.displayValue ?: return@addOnSuccessListener
                if (_qrDetected.value == null) {
                    val detected = toDetected(barcode, content, imageProxy.toBitmap())
                    _qrDetected.value = detected
                    saveQrScan(detected)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun toDetected(barcode: Barcode, content: String, bitmap: Bitmap): QrDetected {
        val wifi = barcode.wifi
        val url = barcode.url
        val email = barcode.email
        val phone = barcode.phone
        val sms = barcode.sms
        val geo = barcode.geoPoint
        val type = when {
            wifi != null -> QrDetected.Type.Wifi(
                ssid = wifi.ssid,
                password = wifi.password,
                security = when (wifi.encryptionType) {
                    Barcode.WiFi.TYPE_WPA -> "WPA"
                    Barcode.WiFi.TYPE_WEP -> "WEP"
                    else -> "Abierta"
                },
            )

            url != null -> QrDetected.Type.Url(url.url ?: content)
            email != null -> QrDetected.Type.Email(
                address = email.address ?: content,
                subject = email.subject,
                body = email.body,
            )

            phone != null -> QrDetected.Type.Phone(phone.number ?: content)
            sms != null -> QrDetected.Type.Sms(
                number = sms.phoneNumber ?: content,
                message = sms.message,
            )

            geo != null -> QrDetected.Type.Geo(geo.lat, geo.lng)
            else -> QrDetected.Type.Text
        }
        return QrDetected(content, formatName(barcode.format), bitmap, type)
    }

    private fun saveQrScan(detected: QrDetected) {
        viewModelScope.launch(Dispatchers.IO) {
            val imagePath = try {
                qrScansDir.mkdirs()
                val file = File(qrScansDir, "scan_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { detected.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
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

    /** QR detection on a still bitmap (imported image), same save + result flow as live frames. */
    private fun detectQrFromImage(bitmap: Bitmap) {
        if (_qrDetected.value != null) return
        val input = InputImage.fromBitmap(bitmap, 0)
        qrScanner.process(input)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull() ?: return@addOnSuccessListener
                val content = barcode.rawValue ?: barcode.displayValue ?: return@addOnSuccessListener
                if (_qrDetected.value == null) {
                    val detected = toDetected(barcode, content, bitmap)
                    _qrDetected.value = detected
                    saveQrScan(detected)
                }
            }
    }

    override fun onCleared() {
        qrScanner.close()
        super.onCleared()
    }

    fun onImageCaptured(imageProxy: ImageProxy?, opticalMeasures: OpticalMeasures?) {
        if (imageProxy != null) {
            viewModelScope.launch {
                try {
                    val source = imageProxy.toBitmap()
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val page =
                        processCapturedImage(source, rotationDegrees, opticalMeasures, Mode.CAPTURE)
                    imageProxy.close()
                    onCaptureProcessed(page)
                } catch (e: RuntimeException) {
                    logger.e("Camera", "Failed to process captured image", e)
                    onCaptureProcessed(null)
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
        val segmentation = imageSegmentationService.runSegmentationAndReturn(source)
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
                            applicationContext.stringFor(R.string.import_error, AppLocaleOverride.locale)
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
            _qrScanMode.value || isImageFile(mime, name) -> importImage(uri)
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
        if (_qrScanMode.value) {
            // In QR mode, imported images are QR-detected instead of
            // added to the document.
            detectQrFromImage(photoToImport)
        } else {
            val page = processCapturedImage(photoToImport, 0, null, Mode.IMPORT)
            currentCoroutineContext().ensureActive()
            _events.emit(CameraEvent.ImageCaptured(page))
        }
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
                }
            }
        }
    }

    /** Copies the content of [uri] to a fresh file in the app cache. */
    private suspend fun copyUriToCache(uri: Uri, prefix: String): File =
        withContext(Dispatchers.IO) {
            val input = applicationContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open $uri")
            val target = File(applicationContext.cacheDir, "${prefix}_${System.currentTimeMillis()}")
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
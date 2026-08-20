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

package nopalito.app.ui.screens.export

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import nopalito.app.AppContainer
import nopalito.app.R
import nopalito.app.data.ExportNames
import nopalito.app.data.FileManager
import nopalito.app.data.ImageRepository
import nopalito.app.domain.*
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.data.CloudSessionState
import nopalito.app.ui.screens.cloud.model.CloudFile
import nopalito.app.ui.screens.document.toPageExportOverlays
import nopalito.app.ui.screens.history.ExportHistoryEntity
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

sealed interface ExportEvent {
    data object RequestSave : ExportEvent
    data class Share(val result: ExportResult) : ExportEvent
}

class ExportViewModel(container: AppContainer, val imageRepository: ImageRepository) : ViewModel() {

    @SuppressLint("StaticFieldLeak")
    private val context = container.applicationContext

    /** Overlays to compose onto each page during export. Set before calling prepareExportIfNeeded. */
    var pageOverlays: Map<String, nopalito.app.ui.screens.document.PageOverlays> = emptyMap()

    /**
     * When true, the first two pages are INE front/back captures and must be merged into a
     * single composite sheet for export. Set by the activity before export when the current
     * document was created in INE mode.
     */
    fun setIneDocument(enabled: Boolean) {
        _uiState.update { it.copy(isIneDocument = enabled) }
    }

    /** Sets how large the composed INE appears on the sheet (200% vs normal). */
    fun setIneExportScale(scale: IneExportScale) {
        _uiState.update { it.copy(ineExportScale = scale) }
        prepareExportIfNeeded()
    }

    private val preparationDir = container.preparationDir
    private val exportsBackupDir = container.exportsBackupDir
    private val fileManager = container.fileManager
    private val settingsRepository = container.settingsRepository
    private val ocrService = container.ocrService
    private val logger = container.logger
    private val historyRepository = container.historyRepository
    private val cloudRepository = CloudRepository(context)
    private val cloudSessionManager = container.cloudSessionManager

    private val _uiState = MutableStateFlow(
        // Synchronous initial value: if the user is already logged in, the
        // cloud upload section must not show the "login" hint while the
        // reactive collection below has not emitted yet.
        ExportUiState(isCloudAuthAvailable = cloudSessionManager.isAuthenticated())
    )
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    init {
        // Collect CloudSessionManager state reactively and update UI state.
        // This ensures isCloudAuthAvailable is always in sync with the actual session.
        viewModelScope.launch {
            cloudSessionManager.state.collect { sessionState ->
                _uiState.update {
                    it.copy(
                        isCloudAuthAvailable = sessionState == CloudSessionState.Authenticated
                    )
                }
            }
        }
    }

    private val _events = MutableSharedFlow<ExportEvent>()
    val events = _events.asSharedFlow()

    private suspend fun pageToExportsWithOverlays(
        exportQuality: ExportQuality,
    ): List<PageToExport> {
        val exports = pagesToExport(imageRepository, exportQuality).map { pte ->
            val pageId = pte.page.id
            val overlays = pageOverlays[pageId]
            if (overlays != null) {
                pte.copy(overlays = toExportOverlays(overlays))
            } else pte
        }
        val ine = _uiState.value
        if (!ine.isIneDocument || exports.size < 2) return exports
        // INE: merge front (page 0) on top of back (page 1) into a single sheet. Each
        // face keeps its overlays (signature/date) and its color/rotation, so editor
        // edits are preserved. A synthetic page without physical dimensions makes the
        // writer size the sheet by the composite's vertical aspect ratio.
        val front = exports[0]
        val back = exports[1]
        val rest = exports.drop(2)
        val mergedPage = ScanPage(
            id = "ine-composite",
            manualRotation = Rotation.R0,
            colorMode = null,
            quadVersion = 0,
            metadata = null,
        )
        return listOf(
            PageToExport(mergedPage) {
                val frontBmp = front.jpeg.get().toBitmap()
                val backBmp = back.jpeg.get().toBitmap()
                try {
                    val frontComposed = nopalito.app.platform.composeOverlaysOnBitmap(
                        frontBmp, front.overlays
                    ) ?: frontBmp
                    val backComposed = nopalito.app.platform.composeOverlaysOnBitmap(
                        backBmp, back.overlays
                    ) ?: backBmp
                    mergeIneBitmaps(
                        frontComposed,
                        backComposed,
                        fillFraction = ine.ineExportScale.fillFraction,
                    )
                } finally {
                    frontBmp.recycle()
                    backBmp.recycle()
                }
            }
        ) + rest
    }

    private suspend fun generatePdf(
        exportQuality: ExportQuality,
        disableOcr: Boolean,
        password: String?,
        onProgress: (Int) -> Unit,
    ): ExportResult.Pdf = withContext(Dispatchers.IO) {
        val pageToExports = pageToExportsWithOverlays(exportQuality)
        val pdf = fileManager.generatePdf(pageToExports, disableOcr, password, onProgress)
        return@withContext ExportResult.Pdf(pdf.file, pdf.sizeInBytes, pdf.pageCount)
    }

    private suspend fun generateWord(
        exportQuality: ExportQuality,
        disableOcr: Boolean,
        password: String?,
        onProgress: (Int) -> Unit,
    ): ExportResult.Word = withContext(Dispatchers.IO) {
        val pageToExports = pageToExportsWithOverlays(exportQuality)
        val word = fileManager.generateDocx(pageToExports, disableOcr, password, onProgress)
        return@withContext ExportResult.Word(word.file, word.sizeInBytes, word.pageCount)
    }

    suspend fun generatePdfForExternalCall(): ExportResult.Pdf {
        val pdf = generatePdf(ExportQuality.BALANCED, true, null) {}
        val sourceFile = pdf.file
        val targetFile = File(sourceFile.parentFile, defaultFilename() + ".pdf")
        if (sourceFile.absolutePath == targetFile.absolutePath) return pdf
        if (targetFile.exists() || !sourceFile.renameTo(targetFile)) return pdf
        return pdf.copy(file = targetFile)
    }

    private var lastPreparationKey: ExportPreparationKey? = null
    private var preparationJob: Job? = null

    // Incremented each time a new preparation starts; only the current
    // generation may publish result/error/isGenerating, so a stale cancelled
    // job can never clobber the UI state of the job that replaced it.
    private var preparationSequence = 0

    fun setFormat(format: ExportFormat) {
        _uiState.update {
            it.copy(format = format)
        }
        prepareExportIfNeeded()
    }

    fun setQuality(quality: ExportQuality) {
        _uiState.update {
            it.copy(quality = quality)
        }
        prepareExportIfNeeded()
    }

    fun setFilename(name: String) {
        _uiState.update {
            it.copy(filename = name)
        }
    }

    fun setProtectWithPassword(enabled: Boolean) {
        _uiState.update { it.copy(protectWithPassword = enabled) }
        prepareExportIfNeeded()
    }

    private var passwordDebounceJob: Job? = null

    fun setPassword(password: String) {
        _uiState.update { it.copy(password = password) }
        passwordDebounceJob?.cancel()
        // Debounce so typing stays responsive: preparation/OCR is only re-run
        // once the user pauses, instead of on every keystroke.
        passwordDebounceJob = viewModelScope.launch {
            delay(600.milliseconds)
            prepareExportIfNeeded()
        }
    }

    fun generatePassword() {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"
        val random = java.security.SecureRandom()
        val password = buildString { repeat(16) { append(chars[random.nextInt(chars.length)]) } }
        _uiState.update { it.copy(protectWithPassword = true, password = password) }
        prepareExportIfNeeded()
    }

    fun resetFilename() {
        _uiState.update {
            it.copy(filename = "")
        }
    }

    private fun defaultFilename(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
        return "Scan $timestamp"
    }

    private fun ensureValidFilename() {
        _uiState.update {
            val normalized = it.filename.trim().ifEmpty { defaultFilename() }
            if (normalized != it.filename) {
                it.copy(filename = normalized)
            } else it
        }
    }

    private suspend fun currentPageKeys(): ImmutableList<PageViewKey> =
        imageRepository.pages().map { it.key() }.toImmutableList()

    fun cancelPreparationJob() {
        preparationJob?.let {
            // keep result if job is not active
            if (it.isActive) {
                _uiState.update { ExportUiState() }
            }
            it.cancel()
        }
    }

    fun prepareExportIfNeeded() {
        ensureValidFilename()

        // Show the "Preparing export…" progress synchronously: the preparation key below
        // reads every signature bitmap's pixels off the main thread, so the
        // indicator must be visible immediately or the screen looks frozen and
        // generation never seems to start.
        _uiState.update { it.copy(isGenerating = true, error = null) }

        viewModelScope.launch {
            val currentState = _uiState.value
            val exportQuality = currentState.quality
            val exportFormat = currentState.format
            val ocrLanguageString = ocrService.languageString()
            val exportPassword = currentState.password
                .takeIf { currentState.protectWithPassword && it.isNotBlank() }

            val key: ExportPreparationKey
            val pageCount: Int
            try {
                val currentPageKeys = currentPageKeys()
                pageCount = currentPageKeys.size
                val fingerprint = withContext(Dispatchers.Default) { overlaysFingerprint(pageOverlays) }
                key = ExportPreparationKey(
                    pages = currentPageKeys,
                    format = exportFormat,
                    quality = exportQuality,
                    ocrLanguageString = ocrLanguageString,
                    overlaysFingerprint = fingerprint,
                    password = exportPassword,
                    isIneDocument = currentState.isIneDocument,
                    ineExportScale = currentState.ineExportScale,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        error = ExportError.OnPrepareOrShare(
                            context.stringFor(
                                R.string.error_prepare_export,
                                AppLocaleOverride.locale,
                                exportFormat.displayName
                            ),
                            e,
                        ),
                    )
                }
                return@launch
            }

            if (key == lastPreparationKey && _uiState.value.result != null) {
                _uiState.update { it.copy(isGenerating = false) }
                return@launch
            }

            lastPreparationKey = key
            preparationJob?.cancel()
            // Token captured by the new job: once a newer preparation takes
            // over, this generation must stop publishing to the UI state.
            val generation = ++preparationSequence

            preparationJob = launch {
                val ocrActivation =
                    if (exportFormat == ExportFormat.PDF)
                        ocrLanguageString.isNotEmpty() else null
                if (generation == preparationSequence) {
                    _uiState.update {
                        ExportUiState(
                            filename = it.filename,
                            format = exportFormat,
                            quality = exportQuality,
                            protectWithPassword = it.protectWithPassword,
                            password = it.password,
                            isGenerating = true,
                            progress = ExportProgress(0, pageCount),
                            ocrActivation = ocrActivation,
                            isIneDocument = it.isIneDocument,
                            ineExportScale = it.ineExportScale,
                        )
                    }
                }
                val onProgress: (Int) -> Unit = { completedPages ->
                    if (generation == preparationSequence) {
                        _uiState.update {
                            it.copy(progress = ExportProgress(completedPages, pageCount))
                        }
                    }
                }
                try {
                    val t1 = System.currentTimeMillis()
                    val result = when (exportFormat) {
                        ExportFormat.JPEG -> generateJpegs(exportQuality, onProgress)
                        ExportFormat.WORD -> generateWord(exportQuality, true, exportPassword, onProgress)
                        ExportFormat.PDF -> generatePdf(exportQuality, false, exportPassword, onProgress)
                    }
                    if (generation == preparationSequence) {
                        _uiState.update { it.copy(result = result) }
                    }
                    val t2 = System.currentTimeMillis()
                    Log.i("Export", "Generation: $pageCount pages, $exportQuality, ${t2 - t1} ms")
                } catch (e: CancellationException) {
                    // Preparation cancelled: do nothing
                    throw e
                } catch (e: Exception) {
                    if (generation == preparationSequence) {
                        val message = context.stringFor(
                            R.string.error_prepare_export,
                            AppLocaleOverride.locale,
                            exportFormat.displayName
                        )
                        logger.e("NopalitoScan", message, e)
                        _uiState.update {
                            it.copy(error = ExportError.OnPrepareOrShare(message, e))
                        }
                    }
                } finally {
                    if (generation == preparationSequence) {
                        _uiState.update { it.copy(isGenerating = false) }
                    }
                }
            }
        }
    }

    private fun toExportOverlays(
        overlays: nopalito.app.ui.screens.document.PageOverlays
    ): PageToExport.PageExportOverlays? = overlays.toPageExportOverlays()

    private fun overlaysFingerprint(
        overlays: Map<String, nopalito.app.ui.screens.document.PageOverlays>,
    ): Int {
        var result = 1
        overlays.toSortedMap().forEach { (pageId, pageOverlays) ->
            result = 31 * result + pageId.hashCode()
            result = 31 * result + (pageOverlays.signatureState?.hashCode() ?: 0)
            result = 31 * result + pageOverlays.signatureSource.hashCode()
            result = 31 * result + (pageOverlays.signaturePositionFraction?.hashCode() ?: 0)
            result = 31 * result + pageOverlays.signatureScale.hashCode()
            result = 31 * result + (pageOverlays.signatureBitmap?.let(::bitmapFingerprint) ?: 0)
            result = 31 * result + pageOverlays.signatureRotationDegrees.hashCode()
            result = 31 * result + (pageOverlays.dateText?.hashCode() ?: 0)
            result = 31 * result + (pageOverlays.datePositionFraction?.hashCode() ?: 0)
            result = 31 * result + pageOverlays.dateScale.hashCode()
            result = 31 * result + pageOverlays.dateRotationDegrees.hashCode()
            result = 31 * result + pageOverlays.dateStyle.hashCode()
        }
        return result
    }

    private fun bitmapFingerprint(bitmap: android.graphics.Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var result = 31 * bitmap.width + bitmap.height
        result = 31 * result + pixels.contentHashCode()
        return result
    }

    private suspend fun generateJpegs(
        exportQuality: ExportQuality,
        onProgress: (Int) -> Unit,
    ): ExportResult.Jpeg = withContext(Dispatchers.IO) {
        val pageToExports = pageToExportsWithOverlays(exportQuality)
        val multi = pageToExports.size > 1
        val targetDir = if (multi) ExportNames.uniqueSubfolder(preparationDir) else preparationDir
        targetDir.mkdirs()
        val timestamp = System.currentTimeMillis()
        val files = pageToExports.mapIndexed { index, page ->
            val fileName = if (multi) "$timestamp-${index + 1}.jpg" else "$timestamp.jpg"
            val file = File(targetDir, fileName)
            val baseBitmap = page.jpeg.get().toBitmap()
            val composed = nopalito.app.platform.composeOverlaysOnBitmap(baseBitmap, page.overlays)
            if (composed != null) {
                val bos = java.io.ByteArrayOutputStream()
                composed.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, bos)
                file.writeBytes(bos.toByteArray())
                composed.recycle()
            } else {
                file.writeBytes(page.jpeg.get().bytes)
            }
            baseBitmap.recycle()
            onProgress(index + 1)
            file
        }.toList()
        val sizeInBytes = files.sumOf { it.length() }
        ExportResult.Jpeg(files, sizeInBytes, directory = targetDir.takeIf { multi })
    }

    private fun renameFile(source: File, target: File) {
        if (source.absolutePath == target.absolutePath) return
        if (target.exists() && !target.delete()) {
            throw IOException("Cannot delete existing file ${target.absolutePath}")
        }
        if (!source.renameTo(target)) {
            throw IOException("Failed to rename ${source.name} to ${target.name}")
        }
    }

    private fun applyRenaming(): ExportResult {
        val result = _uiState.value.result
            ?: throw IllegalStateException("Export result missing")
        val filename = ExportNames.sanitizeFileName(_uiState.value.filename, defaultFilename())
        _uiState.update { it.copy(filename = filename) }
        val updated = when (result) {
            is ExportResult.Pdf -> {
                val fileName = FileManager.addPdfExtensionIfMissing(filename)
                val newFile = ExportNames.availableFile(result.file.parentFile ?: preparationDir, fileName)
                renameFile(result.file, newFile)
                ExportResult.Pdf(newFile, result.sizeInBytes, result.pageCount)
            }

            is ExportResult.Word -> {
                val fileName = FileManager.addDocxExtensionIfMissing(filename)
                val newFile = ExportNames.availableFile(result.file.parentFile ?: preparationDir, fileName)
                renameFile(result.file, newFile)
                ExportResult.Word(newFile, result.sizeInBytes, result.pageCount)
            }

            is ExportResult.Jpeg -> {
                val base = filename.removeSuffix(".jpg")
                val files = result.files
                val renamedFiles = files.mapIndexed { index, file ->
                    val indexSuffix = if (files.size == 1) "" else "_${index + 1}"
                    val newFile = File(file.parentFile, "${base}${indexSuffix}.jpg")
                    renameFile(file, newFile)
                    newFile
                }
                result.copy(jpegFiles = renamedFiles)
            }
        }
        _uiState.update { it.copy(result = updated) }
        return updated
    }

    /**
     * Checks if the user has an active cloud session and updates isCloudAuthAvailable.
     * Now delegates to CloudSessionManager for consistency with the rest of the app.
     */
    fun checkCloudAuth() {
        _uiState.update { it.copy(isCloudAuthAvailable = cloudSessionManager.isAuthenticated()) }
    }

    fun autoUploadIfNeeded() {
        val state = _uiState.value
        if (state.cloudUploadSuccess == null && state.savedBundle != null && cloudSessionManager.isAuthenticated()) {
            uploadToCloud()
        }
    }

    /**
     * Uploads the exported file(s) to the cloud.
     * Must be called after a successful save (savedBundle is set).
     * Groups the export under a single exportId (reuses the one generated in
     * onRequestSave): a multiple export also creates a root row
     * item_type='folder' in the backend.
     */
    fun uploadToCloud() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.isUploadingToCloud) return@launch
            val bundle = state.savedBundle ?: return@launch
            val exportId = state.cloudExportId ?: UUID.randomUUID().toString()
            _uiState.update { it.copy(cloudExportId = exportId) }
            if (!cloudSessionManager.isAuthenticated()) {
                _uiState.update {
                    it.copy(
                        cloudUploadError = context.stringFor(R.string.cloud_no_session, AppLocaleOverride.locale)
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isUploadingToCloud = true,
                    cloudUploadSuccess = null,
                    cloudUploadError = null
                )
            }

            val isFolder = bundle.isFolderExport
            var uploadedCount = 0
            var lastError: String? = null

            if (isFolder) {
                // 1) Upload the cover first to get its id and use it as the folder cover.
                val coverUri = bundle.items.first().uri
                val coverResult = cloudRepository.uploadFile(
                    fileUri = coverUri,
                    category = "exported",
                    exportId = exportId,
                    itemType = "file",
                    outputFormat = state.format.name,
                    itemCount = bundle.items.size,
                    isCover = true,
                )
                val coverFileId = coverResult.getOrNull()?.id
                coverResult.onFailure { error ->
                    lastError = CloudErrorPresenter.message(context, error, R.string.error_unknown_upload)
                    logger.e("CloudUpload", "Failed to upload cover $coverUri", error)
                }

                // 2) Create the folder row with the cover.
                cloudRepository.createExportGroup(
                    exportId = exportId,
                    name = bundle.folderName!!,
                    format = state.format,
                    itemCount = bundle.items.size,
                    coverFileId = coverFileId,
                ).onFailure { error ->
                    lastError = CloudErrorPresenter.message(context, error, R.string.error_unknown_upload)
                    logger.e("CloudUpload", "Failed to create export folder", error)
                }

                // 3) Upload the remaining children (without cover).
                for (index in 1 until bundle.items.size) {
                    val item = bundle.items[index]
                    uploadToCloudChild(item, exportId, state) { errorMsg, error ->
                        lastError = errorMsg
                        logger.e("CloudUpload", "Failed to upload ${item.uri}", error)
                    }.onSuccess { uploadedCount++ }
                }
            } else {
                for (item in bundle.items) {
                    uploadToCloudChild(item, exportId, state) { errorMsg, error ->
                        lastError = errorMsg
                        logger.e("CloudUpload", "Failed to upload ${item.uri}", error)
                    }.onSuccess { uploadedCount++ }
                }
            }

            _uiState.update {
                it.copy(
                    isUploadingToCloud = false,
                    cloudUploadSuccess = if (lastError == null) true else null,
                    cloudUploadError = lastError
                )
            }
        }
    }

    private suspend fun uploadToCloudChild(
        item: SavedItem,
        exportId: String,
        state: ExportUiState,
        onFailure: (String, Throwable) -> Unit,
    ): Result<CloudFile> = cloudRepository.uploadFile(
        fileUri = item.uri,
        category = "exported",
        exportId = exportId,
        itemType = "file",
        outputFormat = state.format.name,
        itemCount = state.savedBundle?.items?.size ?: 1,
        isCover = false,
    ).onFailure { error ->
        onFailure(CloudErrorPresenter.message(context, error, R.string.error_unknown_upload), error)
    }

    fun onShareClicked() {
        viewModelScope.launch {
            try {
                val result = applyRenaming()
                recordExportInHistory()
                _events.emit(ExportEvent.Share(result))
                _uiState.update { it.copy(hasShared = true) }
            } catch (e: Exception) {
                val message = context.stringFor(R.string.error_prepare_share, AppLocaleOverride.locale)
                logger.e("FairScan", message, e)
                _uiState.update { it.copy(error = ExportError.OnPrepareOrShare(message, e)) }
            }
        }
    }

    fun onSaveClicked() {
        viewModelScope.launch {
            _events.emit(ExportEvent.RequestSave)
        }
    }

    fun onRequestSave(context: Context) {
        viewModelScope.launch {
            val exportId = UUID.randomUUID().toString()
            _uiState.update {
                it.copy(
                    isSaving = true,
                    error = null,
                    savedBundle = null,
                    cloudUploadSuccess = null,
                    cloudUploadError = null,
                    cloudExportId = exportId
                )
            }


            val exportFormat = uiState.value.format
            val saveDir = saveDir(context)
            try {
                withContext(Dispatchers.IO) {
                    save(context, saveDir, exportFormat)
                }
                recordExportInHistory()
                autoUploadIfNeeded()
            } catch (e: MissingExportDirPermissionException) {
                logger.e("FairScan", "Missing export dir permission", e)
                _uiState.update {
                    it.copy(
                        error =
                            ExportError.OnSave(R.string.error_export_dir_permission_lost, saveDir)
                    )
                }
            } catch (e: Exception) {
                logger.e("FairScan", "Failed to save PDF", e)
                _uiState.update {
                    it.copy(error = ExportError.OnSave(R.string.error_save, saveDir, e))
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private suspend fun saveDir(context: Context): SaveDir? {
        val uri = settingsRepository.exportDirUri.first()?.toUri() ?: return null
        val name = resolveExportDirName(context, uri)
        return SaveDir(uri, name)
    }

    private suspend fun save(context: Context, saveDir: SaveDir?, exportFormat: ExportFormat) {
        val result = applyRenaming()
        val isFolder = result is ExportResult.Jpeg && result.files.size > 1
        val folderName = if (isFolder) ExportNames.folderName() else null

        val savedItems = mutableListOf<SavedItem>()
        val filesForMediaScan = mutableListOf<File>()
        // On Android <10 the folder is created as a real File; it is kept to open it.
        var legacyFolderFile: File? = null

        for (file in result.files) {
            val saved = if (saveDir == null) {
                // No export dir defined -> save to Downloads
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+: use MediaStore API
                    val uri = saveViaMediaStore(context, file, exportFormat, folderName)
                    SavedItem(uri, file.name, exportFormat)
                } else {
                    // Android 8 and 9: use File API
                    // (MediaStore doesn't allow to choose Downloads for Android<10)
                    val out = if (folderName != null) {
                        val folder = File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                            ),
                            folderName
                        )
                        folder.mkdirs()
                        legacyFolderFile = folder
                        file.copyTo(File(folder, file.name))
                    } else {
                        fileManager.copyToExternalDir(file)
                    }
                    filesForMediaScan.add(out)
                    SavedItem(out.toUri(), out.name, exportFormat)
                }
            } else {
                // Use Storage Access Framework to save to the chosen directory
                if (!context.contentResolver.persistedUriPermissions.any { perm ->
                        perm.uri == saveDir.uri && perm.isWritePermission
                    }) {
                    throw MissingExportDirPermissionException(saveDir.uri)
                }
                val safFile = saveViaSaf(context, file, saveDir.uri, exportFormat, folderName)
                SavedItem(safFile.uri, safFile.name ?: file.name, exportFormat)
            }
            savedItems += saved
        }

        val bundle = SavedBundle(
            items = savedItems,
            saveDir = saveDir,
            folderName = folderName,
            folderUri = resolveFolderUri(context, saveDir, folderName, legacyFolderFile),
        )
        _uiState.update {
            it.copy(
                savedBundle = bundle,
                cloudUploadSuccess = null,
                cloudUploadError = null
            )
        }



        filesForMediaScan.forEach { f -> mediaScan(context, f, exportFormat.mimeType) }
    }

    /**
     * URI of the container folder for the "Open folder" action:
     * - SAF: uri of the DocumentFile created inside the configured tree.
     * - MediaStore (Q+): document uri of Downloads via DocumentsContract,
     *   opened by DocumentsUI/file managers.
     * - <Q: Uri.fromFile of the real folder.
     */
    private fun resolveFolderUri(
        context: Context,
        saveDir: SaveDir?,
        folderName: String?,
        legacyFolderFile: File?,
    ): Uri? {
        if (folderName == null) return null
        return when {
            saveDir != null -> folderUri(context, saveDir.uri, folderName)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> mediaStoreFolderUri(folderName)
            else -> legacyFolderFile?.toUri()
        }
    }

    private fun mediaStoreFolderUri(folderName: String): Uri? = try {
        val relative = Environment.DIRECTORY_DOWNLOADS + "/" + folderName
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:$relative"
        )
    } catch (_: Exception) {
        null
    }

    private suspend fun mediaScan(
        context: Context,
        file: File,
        mimeType: String
    ): Uri? = suspendCancellableCoroutine { cont ->
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mimeType)
        ) { _, uri ->
            cont.resume(uri)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        source: File,
        format: ExportFormat,
        folderName: String?,
    ): Uri {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                folderName?.let { Environment.DIRECTORY_DOWNLOADS + "/" + it }
                    ?: Environment.DIRECTORY_DOWNLOADS
            )
        }

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore entry")

        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { input ->
                input.copyTo(out)
            }
        } ?: throw IOException("Failed to open output stream")

        return uri
    }

    private fun saveViaSaf(
        context: Context,
        source: File,
        exportDirUri: Uri,
        exportFormat: ExportFormat,
        folderName: String?,
    ): DocumentFile {
        val resolver = context.contentResolver

        val tree = DocumentFile.fromTreeUri(context, exportDirUri)
            ?: throw IllegalStateException("Invalid SAF directory")

        val parent = if (folderName != null) {
            tree.findFile(folderName) ?: tree.createDirectory(folderName)
            ?: throw IllegalStateException("Unable to create SAF folder")
        } else {
            tree
        }

        // Name collisions are handled automatically by SAF provider
        val target = parent.createFile(exportFormat.mimeType, source.name)
            ?: throw IllegalStateException("Unable to create SAF file")

        resolver.openOutputStream(target.uri)?.use { output ->
            FileInputStream(source).use { input ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Failed to open SAF output stream")

        return target
    }

    private fun folderUri(context: Context, exportDirUri: Uri, folderName: String?): Uri? {
        if (folderName == null) return null
        return DocumentFile.fromTreeUri(context, exportDirUri)?.findFile(folderName)?.uri
    }

    fun cleanUpOldPreparedFiles(thresholdInMillis: Int) {
        fileManager.cleanUpOldFiles(thresholdInMillis)
    }

    private fun resolveExportDirName(context: Context, exportDirUri: Uri?): String? {
        return if (exportDirUri == null) {
            null
        } else {
            DocumentFile.fromTreeUri(context, exportDirUri)?.name
        }
    }

    private fun recordExportInHistory() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val result = state.result ?: return@launch
                val bundle = state.savedBundle
                val filename = state.filename.ifBlank { defaultFilename() }
                val format = when (result) {
                    is ExportResult.Pdf -> "PDF"
                    is ExportResult.Jpeg -> "JPEG"
                    is ExportResult.Word -> "DOCX"
                }
                val quality = state.quality.name

                // Save the actual source file path (not the internal ID)
                val originalDocPath = try {
                    val firstPage = imageRepository.pages().firstOrNull()
                    if (firstPage != null) {
                        imageRepository.sourceFilePath(firstPage.id)
                    } else null
                } catch (_: Exception) {
                    null
                }

                // Save the real saved destination (MediaStore/SAF uri), not the cache path
                val exportedUri = bundle?.items?.firstOrNull()?.uri?.toString()

                val thumbnailPath = try {
                    val firstPage = imageRepository.pages().firstOrNull()
                    if (firstPage != null) {
                        val key = firstPage.key()
                        imageRepository.getThumbnail(key, pageOverlays[firstPage.id])?.let { jpeg ->
                            // Stored next to the backup (app-private) so the history
                            // thumbnail survives the cache cleanup.
                            val thumbFile =
                                File(exportsBackupDir, "thumb_${System.currentTimeMillis()}.jpg")
                            thumbFile.writeBytes(jpeg.bytes)
                            thumbFile.absolutePath
                        }
                    } else null
                } catch (_: Exception) {
                    null
                }

                val isFolder = result is ExportResult.Jpeg && result.files.size > 1

                // Keep an app-private backup copy of the exported file/folder so
                // the history has its own record and can preview/restore it even
                // after the file is deleted from Downloads.
                val backup = withContext(Dispatchers.IO) {
                    runCatching {
                        exportsBackupDir.mkdirs()
                        if (isFolder) {
                            val dir = File(exportsBackupDir, "export_${System.currentTimeMillis()}")
                            dir.mkdirs()
                            result.files.forEach { file -> file.copyTo(File(dir, file.name), overwrite = true) }
                            dir.absolutePath
                        } else {
                            val file = result.files.firstOrNull()
                            file?.copyTo(File(exportsBackupDir, file.name), overwrite = true)?.absolutePath
                        }
                    }.getOrNull()
                }

                historyRepository.insert(
                    ExportHistoryEntity(
                        documentName = filename,
                        dateTime = System.currentTimeMillis(),
                        pageCount = result.pageCount,
                        format = format,
                        quality = quality,
                        fileSizeBytes = result.sizeInBytes,
                        exportCount = 1,
                        thumbnailPath = thumbnailPath,
                        originalDocumentPath = originalDocPath,
                        exportedFilePath = exportedUri,
                        status = ExportHistoryEntity.STATUS_AVAILABLE,
                        resultType = if (isFolder) ExportHistoryEntity.RESULT_TYPE_FOLDER
                        else ExportHistoryEntity.RESULT_TYPE_FILE,
                        exportedFolderUri = bundle?.folderUri?.toString(),
                        exportedItemCount = bundle?.items?.size ?: result.files.size,
                        childrenUris = bundle?.items?.joinToString("\n") { it.uri.toString() },
                        exportId = state.cloudExportId,
                        backupPath = if (isFolder) null else backup,
                        backupDirPath = if (isFolder) backup else null,
                    )
                )
            } catch (e: Exception) {
                logger.e("ExportHistory", "Failed to record export history", e)
            }
        }
    }
}

data class ExportPreparationKey(
    val pages: ImmutableList<PageViewKey>,
    val format: ExportFormat,
    val quality: ExportQuality,
    val ocrLanguageString: String,
    val overlaysFingerprint: Int,
    val password: String?,
    val isIneDocument: Boolean = false,
    val ineExportScale: IneExportScale = IneExportScale.DOUBLE_200,
)

sealed class ExportResult {
    abstract val files: List<File>
    abstract val sizeInBytes: Long
    abstract val pageCount: Int
    abstract val format: ExportFormat

    /** Container subfolder of the multiple export (JPEG only), if it exists. */
    open val directory: File? get() = null

    data class Pdf(
        val file: File,
        override val sizeInBytes: Long,
        override val pageCount: Int,
    ) : ExportResult() {
        override val files get() = listOf(file)
        override val format: ExportFormat = ExportFormat.PDF
    }

    data class Word(
        val file: File,
        override val sizeInBytes: Long,
        override val pageCount: Int,
    ) : ExportResult() {
        override val files get() = listOf(file)
        override val format: ExportFormat = ExportFormat.WORD
    }

    data class Jpeg(
        val jpegFiles: List<File>,
        override val sizeInBytes: Long,
        /** Container subfolder, only when there is more than one image. */
        override val directory: File? = null,
    ) : ExportResult() {
        override val files get() = jpegFiles
        override val pageCount get() = jpegFiles.size
        override val format: ExportFormat = ExportFormat.JPEG
    }
}

data class ExportActions(
    val prepareExportIfNeeded: () -> Unit,
    val setFilename: (String) -> Unit,
    val setFormat: (ExportFormat) -> Unit,
    val setQuality: (ExportQuality) -> Unit,
    val setProtectWithPassword: (Boolean) -> Unit,
    val setPassword: (String) -> Unit,
    val generatePassword: () -> Unit,
    val share: () -> Unit,
    val save: () -> Unit,
    val open: (ExportArtifact) -> Unit,
    val cancelPreparationJob: () -> Unit,
    val checkCloudAuth: () -> Unit = {},
    val uploadToCloud: () -> Unit = {},
    val setIneExportScale: (IneExportScale) -> Unit = {},
)

class MissingExportDirPermissionException(
    val uri: Uri
) : IllegalStateException(
    "Missing persisted write permission for export dir: $uri"
)

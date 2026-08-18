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

package nopalito.app.ui.screens.tools.extract

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nopalito.app.AppContainer
import nopalito.app.R
import nopalito.app.data.ExportNames
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.isPdfEncrypted
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.data.CloudSessionManager
import nopalito.app.ui.screens.cloud.data.CloudSessionState
import nopalito.app.ui.screens.history.ExportHistoryEntity
import nopalito.app.ui.screens.tools.BatchFolder
import nopalito.app.ui.screens.tools.PickedFile
import nopalito.app.ui.screens.tools.shared.PasswordGenerator
import nopalito.app.ui.screens.tools.shared.PdfPreviewRenderer
import nopalito.app.ui.screens.tools.shared.ToolOutputSaver
import nopalito.app.ui.uriForFile
import java.io.File

/**
 * ViewModel of the "Extract PDF pages" tool.
 *
 * One PDF at a time: the picked file is cached locally and a native
 * [PdfRenderer] is held open for the lazy page preview (rendered on demand on
 * the IO dispatcher, one page at a time, so large multi-page files never load
 * everything into memory at once). Export re-opens the cached file, copies the
 * selected pages with [ExtractEngine] (PDFBox, preserving the original text)
 * and writes the result through [ToolOutputSaver] into the destination
 * configured in Settings - the same source of truth as every other tool.
 */
class ExtractViewModel(container: AppContainer) : ViewModel() {

    @SuppressLint("StaticFieldLeak")
    private val context: Context = container.applicationContext
    private val settingsRepository = container.settingsRepository
    private val cloudSessionManager: CloudSessionManager = container.cloudSessionManager
    private val cloudRepository = CloudRepository(context)
    private val historyRepository = container.historyRepository
    private val logger = container.logger
    private val exportsBackupDir = container.exportsBackupDir
    private val saver = ToolOutputSaver(context)
    private val toolsDir: File = File(context.cacheDir, "tools").apply { mkdirs() }

    /** Watchdog: a stuck load (slow provider, native renderer hang) fails instead of spinning forever. */
    private val LOAD_TIMEOUT_MS = 20_000L

    /** Render scale for PNG image exports (PDF points to pixels). */
    private val imageDpi = 200

    private val _state = MutableStateFlow(ExtractUiState())
    val state: StateFlow<ExtractUiState> = _state.asStateFlow()

    /** Native renderer kept open for the lazy preview (closed on load/new/clear). */
    private var currentRenderer: PdfRenderer? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentFile: File? = null

    /** Serializes preview renders (see [renderPageForPreview]). */
    private val previewRenderMutex = Mutex()

    init {
        viewModelScope.launch {
            val uri = settingsRepository.exportDirUri.first()
            val name = uri?.let { settingsRepository.resolveExportDirName(it) }
            _state.update { it.copy(saveLocationUri = uri, saveLocationName = name) }
        }
        viewModelScope.launch {
            cloudSessionManager.state.collect { sessionState ->
                _state.update {
                    it.copy(isAuthenticated = sessionState is CloudSessionState.Authenticated)
                }
            }
        }
    }

    override fun onCleared() {
        closeCurrentPdf()
        super.onCleared()
    }

    /** Opens a picked PDF: caches it, counts its pages and keeps it for preview. */
    fun addFile(file: PickedFile) {
        closeCurrentPdf()
        _state.update {
            it.copy(
                files = listOf(file),
                isLoaded = false,
                isLoading = true,
                pageCount = 0,
                rangeInput = "",
                parse = PageRangeResult.Empty,
                exportMode = ExtractExportMode.PDF,
                passwordEnabled = false,
                password = "",
                generatedPassword = null,
                generateDialogVisible = false,
                outputFileName = "",
                results = emptyList(),
                errorMessage = null,
                infoMessages = emptyList(),
                progress = null,
            )
        }
        viewModelScope.launch {
            try {
                withTimeout(LOAD_TIMEOUT_MS) {
                    val cached = withContext(Dispatchers.IO) { cacheInput(file) }
                    Log.i("ExtractLoad", "cached ${cached.length()} bytes")
                    val encrypted = withContext(Dispatchers.IO) { isPdfEncrypted(cached) }
                    Log.i("ExtractLoad", "isPdfEncrypted=$encrypted")
                    if (encrypted) {
                        // /Encrypt dictionary present: the renderer would refuse the
                        // file (or open it silently when only the owner password is
                        // set). Report it as protected right away.
                        closeCurrentPdf()
                        cached.delete()
                        _state.update {
                            it.copy(
                                isLoading = false,
                                isLoaded = false,
                                errorMessage = context.stringFor(R.string.ep_error_protected, AppLocaleOverride.locale)
                            )
                        }
                        return@withTimeout
                    }
                    val (renderer, pfd) = withContext(Dispatchers.IO) {
                        val descriptor = ParcelFileDescriptor.open(
                            cached,
                            ParcelFileDescriptor.MODE_READ_ONLY,
                        )
                        try {
                            PdfRenderer(descriptor) to descriptor
                        } catch (e: Exception) {
                            descriptor.close()
                            throw e
                        }
                    }
                    Log.i("ExtractLoad", "renderer opened")
                    closeCurrentPdf()
                    currentRenderer = renderer
                    currentPfd = pfd
                    currentFile = cached
                    val count = renderer.pageCount
                    Log.i("ExtractLoad", "pageCount=$count")
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isLoaded = true,
                            pageCount = count,
                            fileName = file.name,
                            outputFileName = defaultOutputName(file.name),
                        )
                    }
                }
            } catch (_: SecurityException) {
                // Native renderer rejects password-protected PDFs.
                Log.w("ExtractLoad", "SecurityException (protected PDF)")
                closeCurrentPdf()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isLoaded = false,
                        errorMessage = context.stringFor(R.string.ep_error_protected, AppLocaleOverride.locale)
                    )
                }
            } catch (_: TimeoutCancellationException) {
                Log.w("ExtractLoad", "load timed out after ${LOAD_TIMEOUT_MS}ms")
                closeCurrentPdf()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isLoaded = false,
                        errorMessage = context.stringFor(R.string.ep_error_invalid, AppLocaleOverride.locale)
                    )
                }
            } catch (e: Exception) {
                logger.e("Extract", "Cannot open PDF", e)
                closeCurrentPdf()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isLoaded = false,
                        errorMessage = context.stringFor(R.string.ep_error_invalid, AppLocaleOverride.locale)
                    )
                }
            }
        }
    }

    fun setRangeInput(input: String) {
        _state.update { it.copy(rangeInput = input, results = emptyList(), errorMessage = null) }
        reparse()
    }

    /** Reports that a picked file is not a PDF. */
    fun reportInvalidFileType() {
        _state.update {
            it.copy(
                errorMessage = context.stringFor(R.string.tools_invalid_file_type, AppLocaleOverride.locale, "PDF"),
                results = emptyList(),
            )
        }
    }

    fun setPasswordEnabled(enabled: Boolean) {
        _state.update { it.copy(passwordEnabled = enabled, results = emptyList(), errorMessage = null) }
    }

    fun setPassword(password: String) {
        _state.update { it.copy(password = password, results = emptyList(), errorMessage = null) }
    }

    /** Selects the export format (the password option only applies to PDF). */
    fun setExportMode(mode: ExtractExportMode) {
        _state.update { it.copy(exportMode = mode, results = emptyList(), errorMessage = null) }
    }

    /** Generates a strong suggested password and asks the user to confirm. */
    fun generatePassword() {
        _state.update {
            it.copy(generatedPassword = PasswordGenerator.generate(), generateDialogVisible = true)
        }
    }

    /** The user accepted the suggested password: it fills the password field. */
    fun acceptGeneratedPassword() {
        _state.update {
            it.copy(
                password = it.generatedPassword ?: it.password,
                generatedPassword = null,
                generateDialogVisible = false,
                errorMessage = null,
            )
        }
    }

    fun dismissGenerateDialog() {
        _state.update { it.copy(generateDialogVisible = false) }
    }

    fun setOutputFileName(name: String) {
        _state.update { it.copy(outputFileName = name, results = emptyList(), errorMessage = null) }
    }

    /**
     * Cloud upload is gated on having an active cloud session (the only real
     * entitlement available today). ponytail: when a real Premium/Billing
     * entitlement exists, combine it here without UI changes.
     */
    fun toggleCloudUpload() {
        if (cloudUploadAllowed()) {
            _state.update { it.copy(cloudUploadEnabled = !it.cloudUploadEnabled) }
        } else {
            _state.update { it.copy(premiumDialogVisible = true) }
        }
    }

    fun dismissPremiumDialog() {
        _state.update { it.copy(premiumDialogVisible = false) }
    }

    private fun cloudUploadAllowed(): Boolean =
        _state.value.isAuthenticated

    /** Persists the output destination in Settings (same source of truth as the other tools). */
    fun setSaveLocation(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setExportDirUri(uri)
            val name = uri?.let { settingsRepository.resolveExportDirName(it) }
            _state.update {
                it.copy(saveLocationUri = uri, saveLocationName = name, results = emptyList())
            }
        }
    }

    /**
     * Renders one page for the preview at the given pixel width, off the main
     * thread. Renders are serialized so that at most one page bitmap is in
     * memory at a time (a LazyColumn otherwise composes several pages at once
     * and several parallel renders spike the heap on big documents). Uses the
     * native [PdfRenderer], like the History/Cloud previews.
     */
    suspend fun renderPageForPreview(pageIndex: Int, targetWidthPx: Int): Bitmap? =
        previewRenderMutex.withLock {
            withContext(Dispatchers.IO) {
                val renderer = currentRenderer ?: return@withContext null
                val rendered = PdfPreviewRenderer.renderWithOomRetry(renderer, pageIndex, targetWidthPx)
                if (rendered != null && !coroutineContext.isActive) {
                    // The caller was cancelled while the (non-interruptible) render was
                    // running — e.g. the item scrolled away or rotated. Release the bitmap
                    // so it is never leaked.
                    rendered.recycle()
                    null
                } else {
                    rendered
                }
            }
        }

    /** Exports the selected pages as a single PDF (password-protected when enabled). */
    fun exportPdf() {
        val st = _state.value
        if (!st.isLoaded || st.isProcessing) return
        val pages = validPages(st) ?: run {
            _state.update { it.copy(errorMessage = rangeMessage(st)) }
            return
        }
        if (st.passwordEnabled && st.password.isBlank()) {
            _state.update {
                it.copy(
                    errorMessage = context.stringFor(
                        R.string.ep_password_required,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }
        if (!destinationAvailable(st.saveLocationUri)) {
            _state.update {
                it.copy(
                    errorMessage = context.stringFor(
                        R.string.ep_destination_unavailable,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }

        _state.update {
            it.copy(
                isProcessing = true,
                progress = null,
                progressLabel = context.stringFor(R.string.ep_progress_pdf, AppLocaleOverride.locale),
                results = emptyList(),
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { exportPdfOnIo(st, pages) }
                recordInHistory(listOf(outcome.first), null, listOf(outcome.second to outcome.first.fileName))
                _state.update {
                    it.copy(
                        isProcessing = false,
                        progress = null,
                        progressLabel = null,
                        results = listOf(outcome.first)
                    )
                }
            } catch (e: Exception) {
                logger.e("Extract", "PDF export failed", e)
                _state.update {
                    it.copy(isProcessing = false, progress = null, progressLabel = null, errorMessage = mapError())
                }
            }
        }
    }

    /** Exports the selected pages as one PNG per page, into a new folder. */
    fun exportImages() {
        val st = _state.value
        if (!st.isLoaded || st.isProcessing) return
        val pages = validPages(st) ?: run {
            _state.update { it.copy(errorMessage = rangeMessage(st)) }
            return
        }
        if (!destinationAvailable(st.saveLocationUri)) {
            _state.update {
                it.copy(
                    errorMessage = context.stringFor(
                        R.string.ep_destination_unavailable,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }

        _state.update {
            it.copy(
                isProcessing = true,
                progress = 0f,
                progressLabel = context.stringFor(R.string.ep_progress_images, AppLocaleOverride.locale),
                results = emptyList(),
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { exportImagesOnIo(st, pages) }
                recordInHistory(outcome.results, outcome.batchFolder, outcome.backupItems)
                _state.update {
                    it.copy(isProcessing = false, progress = null, progressLabel = null, results = outcome.results)
                }
            } catch (e: Exception) {
                logger.e("Extract", "Image export failed", e)
                _state.update {
                    it.copy(isProcessing = false, progress = null, progressLabel = null, errorMessage = mapError())
                }
            }
        }
    }

    private fun reparse() {
        val st = _state.value
        val parsed = PageRangeParser.parse(st.rangeInput, st.pageCount)
        val messages = (parsed as? PageRangeResult.Valid)?.warnings?.map { warning ->
            context.stringFor(
                R.string.ep_range_explicit_mismatch,
                AppLocaleOverride.locale,
                warning.actualTotal,
                warning.statedTotal
            )
        }.orEmpty()
        _state.update { it.copy(parse = parsed, infoMessages = messages) }
    }

    private fun validPages(st: ExtractUiState): List<Int>? =
        (st.parse as? PageRangeResult.Valid)?.pages?.takeIf { it.isNotEmpty() }

    private fun rangeMessage(st: ExtractUiState): String = when (val parse = st.parse) {
        is PageRangeResult.Empty -> context.stringFor(R.string.ep_range_required, AppLocaleOverride.locale)
        is PageRangeResult.Invalid -> {
            val e = parse.error
            when (e.kind) {
                PageRangeErrorKind.SYNTAX -> context.stringFor(
                    R.string.ep_range_invalid,
                    AppLocaleOverride.locale,
                    e.token
                )

                PageRangeErrorKind.NOT_POSITIVE -> context.stringFor(R.string.ep_range_zero, AppLocaleOverride.locale)
                PageRangeErrorKind.DESCENDING -> context.stringFor(
                    R.string.ep_range_descending,
                    AppLocaleOverride.locale,
                    e.a,
                    e.b
                )

                PageRangeErrorKind.OUT_OF_BOUNDS -> context.stringFor(
                    R.string.ep_range_out_of_bounds,
                    AppLocaleOverride.locale,
                    e.a,
                    e.totalPages
                )
            }
        }

        is PageRangeResult.Valid -> context.stringFor(R.string.ep_range_required, AppLocaleOverride.locale)
    }

    private suspend fun exportPdfOnIo(
        st: ExtractUiState,
        pages: List<Int>
    ): Pair<ExtractResult, ToolOutputSaver.SaveRef> {
        val input = currentFile ?: throw IllegalStateException("No PDF loaded")
        val extracted = File(toolsDir, "extracted_${System.currentTimeMillis()}.pdf")
        val password = st.password.takeIf { st.passwordEnabled && it.isNotBlank() }
        val uploadToCloud = st.cloudUploadEnabled && cloudUploadAllowed()
        try {
            ExtractEngine.extractPdf(input, pages.map { it - 1 }, extracted, password)
            val safeName = ExportNames.sanitizeFileName(
                st.outputFileName.trim().ifBlank { defaultOutputName(st.fileName) },
                defaultOutputName(st.fileName),
            )
            val name = uniqueNameInSaveLocation(st.saveLocationUri, safeName)
            val ref = saver.save(name, extracted, st.saveLocationUri)
            val finalName = (ref as? ToolOutputSaver.SaveRef.FileRef)?.file?.name ?: name
            val cloud = if (uploadToCloud) {
                val uploadUri = uriForFile(context, extracted)
                val uploadResult = cloudRepository.uploadFile(uploadUri, category = null)
                if (uploadResult.isSuccess) {
                    true to null
                } else {
                    val message = CloudErrorPresenter.message(
                        context,
                        uploadResult.exceptionOrNull(),
                        R.string.cloud_error_upload,
                    )
                    false to message
                }
            } else {
                null to null
            }
            val result = ExtractResult(
                fileName = finalName,
                outputUri = when (ref) {
                    is ToolOutputSaver.SaveRef.FileRef -> uriForFile(context, ref.file)
                    is ToolOutputSaver.SaveRef.UriRef -> ref.uri
                },
                sizeBytes = ref.length(context).takeIf { it > 0 } ?: extracted.length(),
                itemCount = pages.size,
                cloudUploadSuccess = cloud.first,
                cloudUploadError = cloud.second,
            )
            return result to ref
        } finally {
            extracted.delete()
        }
    }

    private data class ImageOutcome(
        val results: List<ExtractResult>,
        val batchFolder: BatchFolder?,
        val backupItems: List<Pair<ToolOutputSaver.SaveRef, String>>,
    )

    private suspend fun exportImagesOnIo(st: ExtractUiState, pages: List<Int>): ImageOutcome {
        val input = currentFile ?: throw IllegalStateException("No PDF loaded")
        val batchFolder = createBatchFolder(st.saveLocationUri)
        val renderDir = File(toolsDir, "render_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            ExtractEngine.extractImages(input, pages.map { it - 1 }, renderDir, imageDpi) { done, total ->
                _state.update { it.copy(progress = done.toFloat() / total.coerceAtLeast(1)) }
            }
            val uploadToCloud = st.cloudUploadEnabled && cloudUploadAllowed()
            val imageFiles = renderDir.listFiles()?.sortedBy { it.name }.orEmpty()
            // Group the pages in the cloud: the folder can be entered to
            // visualize them, like the main export flow.
            val cloudResults = if (uploadToCloud) {
                cloudRepository.uploadGroup(
                    fileUris = imageFiles.map { uriForFile(context, it) },
                    groupName = batchFolder.name,
                    formatName = "PNG",
                )
            } else {
                emptyList()
            }
            val backupItems = mutableListOf<Pair<ToolOutputSaver.SaveRef, String>>()
            val results = imageFiles.mapIndexed { index, file ->
                val ref = saver.save(file.name, file, st.saveLocationUri, batchFolder.uri)
                backupItems += ref to file.name
                val cloud = if (uploadToCloud) {
                    val uploadResult = cloudResults.getOrNull(index)
                    if (uploadResult != null && uploadResult.isSuccess) {
                        true to null
                    } else {
                        val message = CloudErrorPresenter.message(
                            context,
                            uploadResult?.exceptionOrNull(),
                            R.string.cloud_error_upload,
                        )
                        false to message
                    }
                } else {
                    null to null
                }
                ExtractResult(
                    fileName = file.name,
                    outputUri = when (ref) {
                        is ToolOutputSaver.SaveRef.FileRef -> uriForFile(context, ref.file)
                        is ToolOutputSaver.SaveRef.UriRef -> ref.uri
                    },
                    sizeBytes = ref.length(context),
                    batchFolderUri = batchFolder.uri,
                    itemCount = 1,
                    cloudUploadSuccess = cloud.first,
                    cloudUploadError = cloud.second,
                )
            }
            if (results.isEmpty()) throw IllegalStateException("No images exported")
            return ImageOutcome(results, batchFolder, backupItems)
        } finally {
            renderDir.deleteRecursively()
        }
    }

    private fun destinationAvailable(saveLocationUri: String?): Boolean =
        saveLocationUri == null || runCatching {
            DocumentFile.fromTreeUri(context, saveLocationUri.toUri())
        }.getOrNull() != null

    /** Creates the destination folder for an image export, or null for a single PDF. */
    private fun createBatchFolder(saveLocationUri: String?): BatchFolder {
        val folderName = ExportNames.folderName("Nopalito_Scan_Extraer")
        val treeUri = saveLocationUri?.toUri()
        val tree = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        return if (tree != null) {
            val existing = tree.findFile(folderName)
            val dir = if (existing != null && existing.isDirectory) {
                existing
            } else {
                tree.createDirectory(folderName)
                    ?: throw IllegalStateException("Cannot create batch folder")
            }
            BatchFolder(dir.uri, dir.name ?: folderName)
        } else {
            val base = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val dir = File(base, folderName).apply { mkdirs() }
            BatchFolder(Uri.fromFile(dir), folderName)
        }
    }

    /**
     * Tree destinations reuse an existing file on write; to honour "never
     * overwrite without confirmation" the name is made unique here (Downloads /
     * MediaStore always create a fresh entry, so no guard is needed there).
     */
    private fun uniqueNameInSaveLocation(saveLocationUri: String?, base: String): String {
        if (saveLocationUri == null) return base
        val tree = runCatching { DocumentFile.fromTreeUri(context, saveLocationUri.toUri()) }
            .getOrNull() ?: return base
        var name = base
        var counter = 2
        while (tree.findFile(name) != null) {
            name = uniqueName(base, counter++)
        }
        return name
    }

    /**
     * Registers the result in the local history, mirroring the compressor and
     * the other tools: it keeps an app-private backup copy so History can
     * preview/open/share the file even after it is deleted from Downloads.
     */
    private suspend fun recordInHistory(
        results: List<ExtractResult>,
        batchFolder: BatchFolder?,
        backupItems: List<Pair<ToolOutputSaver.SaveRef, String>>,
    ) {
        if (results.isEmpty() || backupItems.isEmpty()) return
        try {
            val isFolder = results.size > 1
            val format = if (isFolder) "PNG" else "PDF"
            val totalSize = results.sumOf { it.sizeBytes }
            val documentName = if (isFolder) {
                batchFolder?.name ?: ExportNames.folderName("Nopalito_Scan_Extraer")
            } else {
                results.first().fileName
            }

            val backupDir = exportsBackupDir.apply { mkdirs() }
            val backupPath = if (isFolder) {
                val dir = File(backupDir, "extract_${System.currentTimeMillis()}")
                dir.mkdirs()
                backupItems.forEach { (ref, name) -> writeBackupCopy(ref, File(dir, name)) }
                dir.absolutePath
            } else {
                val ref = backupItems.first().first
                val file = File(backupDir, backupItems.first().second)
                writeBackupCopy(ref, file)
                file.absolutePath
            }

            val childrenUris = if (isFolder) {
                results.joinToString("\n") { it.outputUri?.toString() ?: "" }.ifEmpty { null }
            } else {
                null
            }

            historyRepository.insert(
                ExportHistoryEntity(
                    documentName = documentName,
                    dateTime = System.currentTimeMillis(),
                    pageCount = results.sumOf { it.itemCount },
                    format = format,
                    quality = "EXTRACTED",
                    fileSizeBytes = totalSize,
                    exportCount = 1,
                    thumbnailPath = null,
                    originalDocumentPath = null,
                    exportedFilePath = if (isFolder) null else results.first().outputUri?.toString(),
                    status = ExportHistoryEntity.STATUS_AVAILABLE,
                    resultType = if (isFolder) {
                        ExportHistoryEntity.RESULT_TYPE_FOLDER
                    } else {
                        ExportHistoryEntity.RESULT_TYPE_FILE
                    },
                    exportedFolderUri = if (isFolder) {
                        batchFolder?.uri
                            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
                            ?.toString()
                    } else {
                        null
                    },
                    exportedItemCount = results.size,
                    childrenUris = childrenUris,
                    backupPath = if (isFolder) null else backupPath,
                    backupDirPath = if (isFolder) backupPath else null,
                )
            )
        } catch (e: Exception) {
            logger.e("ExtractHistory", "Failed to record extract history", e)
        }
    }

    private fun writeBackupCopy(ref: ToolOutputSaver.SaveRef, target: File) {
        when (ref) {
            is ToolOutputSaver.SaveRef.FileRef -> ref.file.copyTo(target, overwrite = true)
            is ToolOutputSaver.SaveRef.UriRef -> {
                val input = context.contentResolver.openInputStream(ref.uri)
                    ?: throw IllegalStateException("Cannot read output for backup")
                input.use { ins ->
                    target.outputStream().use { out -> ins.copyTo(out) }
                }
            }
        }
    }

    private fun cacheInput(file: PickedFile): File {
        val cacheFile = File(toolsDir, "${System.currentTimeMillis()}_${file.name}")
        val input = context.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Cannot read input file")
        input.use { ins ->
            cacheFile.outputStream().use { out -> ins.copyTo(out) }
        }
        return cacheFile
    }

    private fun defaultOutputName(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName)
        return "$base (extraidas).pdf"
    }

    private fun uniqueName(name: String, counter: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)}_$counter${name.substring(dot)}"
        else "${name}_$counter"
    }

    private fun mapError(): String = context.stringFor(R.string.ep_error, AppLocaleOverride.locale)

    private fun closeCurrentPdf() {
        runCatching { currentRenderer?.close() }
        runCatching { currentPfd?.close() }
        currentRenderer = null
        currentPfd = null
        currentFile = null
    }
}

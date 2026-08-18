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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import nopalito.app.ui.screens.tools.PickedFile
import nopalito.app.ui.screens.tools.shared.PasswordGenerator
import nopalito.app.ui.screens.tools.shared.PdfPreviewRenderer
import nopalito.app.ui.screens.tools.shared.ToolOutputSaver
import nopalito.app.ui.uriForFile
import java.io.File

/**
 * ViewModel of the "Reorder PDF pages" tool.
 *
 * One PDF at a time: the picked file is cached locally and a native
 * [PdfRenderer] is held open for the lazy page preview (rendered on demand on
 * the IO dispatcher, one page at a time, so large multi-page files never load
 * everything into memory at once).
 *
 * The reordered page list lives in [ReorderUiState.pageOrder] (a [MutableStateFlow],
 * so it survives recompositions and configuration changes) and is the only
 * source of truth for thumbnails and the main preview: moving a page just
 * updates that list and the UI recomposes. The physical PDF is generated only
 * on export, copying the original pages in the selected order with
 * [ReorderEngine.reorderPages] (PDFBox `importPage`, preserving the original
 * text) and writing the result through [ToolOutputSaver] into the destination
 * configured in Settings - the same source of truth as every other tool.
 */
class ReorderViewModel(container: AppContainer) : ViewModel() {

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

    private val _state = MutableStateFlow(ReorderUiState())
    val state: StateFlow<ReorderUiState> = _state.asStateFlow()

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
                fileUri = file.uri,
                fileName = "",
                sizeBytes = file.sizeBytes,
                isLoaded = false,
                isLoading = true,
                pageCount = 0,
                pageOrder = emptyList(),
                selectedPreviewIndex = 0,
                outputFileName = "",
                passwordEnabled = false,
                password = "",
                generatedPassword = null,
                generateDialogVisible = false,
                results = emptyList(),
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { cacheInput(file) }
                if (withContext(Dispatchers.IO) { isPdfEncrypted(cached) }) {
                    // /Encrypt dictionary present: the renderer would refuse the
                    // file (or open it silently when only the owner password is
                    // set). Report it as protected right away.
                    closeCurrentPdf()
                    cached.delete()
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isLoaded = false,
                            errorMessage = context.stringFor(R.string.ror_error_protected, AppLocaleOverride.locale)
                        )
                    }
                    return@launch
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
                closeCurrentPdf()
                currentRenderer = renderer
                currentPfd = pfd
                currentFile = cached
                val count = renderer.pageCount
                _state.update {
                    it.copy(
                        isLoading = false,
                        isLoaded = true,
                        pageCount = count,
                        pageOrder = (1..count).toList(),
                        selectedPreviewIndex = 0,
                        fileName = file.name,
                        outputFileName = defaultOutputName(file.name),
                    )
                }
            } catch (_: SecurityException) {
                // Native renderer rejects password-protected PDFs.
                closeCurrentPdf()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isLoaded = false,
                        errorMessage = context.stringFor(R.string.ror_error_protected, AppLocaleOverride.locale)
                    )
                }
            } catch (e: Exception) {
                logger.e("Reorder", "Cannot open PDF", e)
                closeCurrentPdf()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isLoaded = false,
                        errorMessage = context.stringFor(R.string.ror_error_invalid, AppLocaleOverride.locale)
                    )
                }
            }
        }
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

    fun setOutputFileName(name: String) {
        _state.update { it.copy(outputFileName = name, results = emptyList(), errorMessage = null) }
    }

    /** Protection switch state (the exported PDF is encrypted when enabled). */
    fun setPasswordEnabled(enabled: Boolean) {
        _state.update { it.copy(passwordEnabled = enabled, results = emptyList(), errorMessage = null) }
    }

    fun setPassword(password: String) {
        _state.update { it.copy(password = password, results = emptyList(), errorMessage = null) }
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

    /**
     * Cloud upload is gated on having an active cloud session (the only real
     * entitlement available today). When a real Premium/Billing entitlement
     * exists, combine it here without UI changes.
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

    /** Selects which page of the current order is shown in the main preview. */
    fun selectPage(index: Int) {
        _state.update {
            val last = it.pageOrder.lastIndex
            it.copy(selectedPreviewIndex = index.coerceIn(0, last.coerceAtLeast(0)))
        }
    }

    /**
     * Drag-and-drop result: moves the page at position [from] to position [to]
     * in the observable order. The preview/thumbnails recompose immediately;
     * no PDF is built here.
     */
    fun movePage(from: Int, to: Int) {
        val st = _state.value
        if (!st.isLoaded || st.isProcessing) return
        val newOrder = ReorderEngine.moveElement(st.pageOrder, from, to)
        if (newOrder == st.pageOrder) return
        _state.update {
            it.copy(
                pageOrder = newOrder,
                selectedPreviewIndex = it.selectedPreviewIndex.coerceIn(0, newOrder.lastIndex),
                results = emptyList(),
                errorMessage = null,
            )
        }
    }

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
     * Renders one page (by original 0-based index) for the preview at the given
     * pixel width, off the main thread. Renders are serialized so that at most
     * one page bitmap is rendered at a time; very large pages retry at half
     * width. Returns null when the page cannot be rendered or the caller was
     * cancelled while rendering (the bitmap is then recycled).
     */
    suspend fun renderPageForPreview(pageIndex: Int, targetWidthPx: Int): Bitmap? =
        previewRenderMutex.withLock {
            withContext(Dispatchers.IO) {
                val renderer = currentRenderer ?: return@withContext null
                val rendered = PdfPreviewRenderer.renderWithOomRetry(renderer, pageIndex, targetWidthPx)
                if (rendered != null && !coroutineContext.isActive) {
                    // The caller was cancelled while the (non-interruptible) render was
                    // running - e.g. the item scrolled away or rotated. Release the bitmap
                    // so it is never leaked.
                    rendered.recycle()
                    null
                } else {
                    rendered
                }
            }
        }

    /** Exports the reordered pages as a new PDF in the destination configured in Settings. */
    fun exportPdf() {
        val st = _state.value
        if (!st.isLoaded || st.isProcessing) return
        if (st.pageOrder.isEmpty()) return
        if (st.passwordEnabled && st.password.isBlank()) {
            _state.update {
                it.copy(
                    errorMessage = context.stringFor(
                        R.string.ror_password_required,
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
                        R.string.ror_destination_unavailable,
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
                progressLabel = context.stringFor(R.string.ror_progress, AppLocaleOverride.locale),
                results = emptyList(),
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { exportOnIo(st) }
                recordInHistory(outcome.first, outcome.second)
                _state.update {
                    it.copy(
                        isProcessing = false,
                        progress = null,
                        progressLabel = null,
                        results = listOf(outcome.first)
                    )
                }
            } catch (e: Exception) {
                logger.e("Reorder", "PDF export failed", e)
                _state.update {
                    it.copy(
                        isProcessing = false,
                        progress = null,
                        progressLabel = null,
                        errorMessage = context.stringFor(R.string.ror_error, AppLocaleOverride.locale)
                    )
                }
            }
        }
    }

    private suspend fun exportOnIo(st: ReorderUiState): Pair<ReorderResult, ToolOutputSaver.SaveRef> {
        val input = currentFile ?: throw IllegalStateException("No PDF loaded")
        val reordered = File(toolsDir, "reordered_${System.currentTimeMillis()}.pdf")
        val password = st.password.takeIf { st.passwordEnabled && it.isNotBlank() }
        val uploadToCloud = st.cloudUploadEnabled && cloudUploadAllowed()
        try {
            val orderIndices = st.pageOrder.map { it - 1 }
            ReorderEngine.reorderPages(input, orderIndices, reordered, password) { done, total ->
                _state.update { it.copy(progress = done.toFloat() / total.coerceAtLeast(1)) }
            }
            val safeName = ExportNames.sanitizeFileName(
                st.outputFileName.trim().ifBlank { defaultOutputName(st.fileName) },
                defaultOutputName(st.fileName),
            )
            val name = uniqueNameInSaveLocation(st.saveLocationUri, safeName)
            val ref = saver.save(name, reordered, st.saveLocationUri)
            val finalName = (ref as? ToolOutputSaver.SaveRef.FileRef)?.file?.name ?: name
            val cloud = if (uploadToCloud) {
                val uploadUri = uriForFile(context, reordered)
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
            val result = ReorderResult(
                fileName = finalName,
                outputUri = when (ref) {
                    is ToolOutputSaver.SaveRef.FileRef -> uriForFile(context, ref.file)
                    is ToolOutputSaver.SaveRef.UriRef -> ref.uri
                },
                sizeBytes = ref.length(context).takeIf { it > 0 } ?: reordered.length(),
                pageCount = orderIndices.size,
                protected = password != null,
                cloudUploadSuccess = cloud.first,
                cloudUploadError = cloud.second,
            )
            return result to ref
        } finally {
            reordered.delete()
        }
    }

    private fun destinationAvailable(saveLocationUri: String?): Boolean =
        saveLocationUri == null || runCatching {
            DocumentFile.fromTreeUri(context, saveLocationUri.toUri())
        }.getOrNull() != null

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
     * Registers the result in the local history, mirroring the other tools: it
     * keeps an app-private backup copy so History can preview/open/share the
     * file even after it is deleted from Downloads.
     */
    private suspend fun recordInHistory(result: ReorderResult, ref: ToolOutputSaver.SaveRef) {
        if (result.outputUri == null) return
        try {
            val backupDir = exportsBackupDir.apply { mkdirs() }
            val backupFile = File(backupDir, result.fileName)
            writeBackupCopy(ref, backupFile)

            historyRepository.insert(
                ExportHistoryEntity(
                    documentName = result.fileName,
                    dateTime = System.currentTimeMillis(),
                    pageCount = result.pageCount,
                    format = "PDF",
                    quality = "REORDERED",
                    fileSizeBytes = result.sizeBytes,
                    exportCount = 1,
                    thumbnailPath = null,
                    originalDocumentPath = null,
                    exportedFilePath = result.outputUri.toString(),
                    status = ExportHistoryEntity.STATUS_AVAILABLE,
                    resultType = ExportHistoryEntity.RESULT_TYPE_FILE,
                    exportedItemCount = 1,
                    childrenUris = null,
                    backupPath = backupFile.absolutePath,
                )
            )
        } catch (e: Exception) {
            logger.e("ReorderHistory", "Failed to record reorder history", e)
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
        return "${base}_reordenado.pdf"
    }

    private fun uniqueName(name: String, counter: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)}_$counter${name.substring(dot)}"
        else "${name}_$counter"
    }

    private fun closeCurrentPdf() {
        runCatching { currentRenderer?.close() }
        runCatching { currentPfd?.close() }
        currentRenderer = null
        currentPfd = null
        currentFile = null
    }
}
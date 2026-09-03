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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
 * ViewModel of the "Delete PDF pages" tool.
 *
 * One PDF at a time: the picked file is cached locally and a native
 * [PdfRenderer] is held open for the lazy page preview (rendered on demand on
 * the IO dispatcher, one page at a time, so large multi-page files never load
 * everything into memory at once).
 *
 * The current page list lives in [DeletePagesUiState.pageOrder] (a
 * [MutableStateFlow], so it survives recompositions and configuration changes)
 * and is the only source of truth for thumbnails and the main preview: moving
 * a page or deleting pages just updates that list and the UI recomposes. Every
 * applied deletion pushes the previous order onto the undo stack, so the user
 * can restore the exact page order, preview selection and thumbnails. The
 * physical PDF is generated only on export, copying the remaining pages with
 * [DeletePagesEngine.reorderPages] (PDFBox `importPage`, preserving the
 * original text) and writing the result through [ToolOutputSaver] into the
 * destination configured in Settings - the same source of truth as every other
 * tool.
 *
 * The blank-page analysis runs on the IO dispatcher, is cancellable and
 * combines per-page PDF structure signals ([DeletePagesEngine.pageContentSignals])
 * with the non-white pixel ratio of a small render; every temporary bitmap is
 * recycled.
 */
class DeletePagesViewModel(container: AppContainer) : ViewModel() {

    @SuppressLint("StaticFieldLeak")
    private val context: Context = container.applicationContext
    private val settingsRepository = container.settingsRepository
    private val cloudSessionManager: CloudSessionManager = container.cloudSessionManager
    private val cloudRepository = CloudRepository(context)
    private val historyRepository = container.historyRepository
    private val logger = container.logger
    private val exportsBackupDir = container.exportsBackupDir
    private val statsRepository = container.statsRepository
    private val saver = ToolOutputSaver(context)
    private val toolsDir: File = File(context.cacheDir, "tools").apply { mkdirs() }

    private val _state = MutableStateFlow(DeletePagesUiState())
    val state: StateFlow<DeletePagesUiState> = _state.asStateFlow()

    /** Native renderer kept open for the lazy preview (closed on load/new/clear). */
    private var currentRenderer: PdfRenderer? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentFile: File? = null

    /** Serializes preview renders (see [renderPageForPreview]). */
    private val previewRenderMutex = Mutex()

    /** Current blank-page analysis job (cancellable). */
    private var blankAnalysisJob: Job? = null

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
        blankAnalysisJob?.cancel()
        closeCurrentPdf()
        super.onCleared()
    }

    /** Opens a picked PDF: caches it, counts its pages and keeps it for preview. */
    fun addFile(file: PickedFile) {
        blankAnalysisJob?.cancel()
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
                deleteMode = false,
                markedForDeletion = emptySet(),
                deleteDialogVisible = false,
                undoStack = emptyList(),
                isAnalyzingBlanks = false,
                blankProgress = null,
                blankProgressLabel = null,
                blankResult = null,
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
                            errorMessage = context.stringFor(
                                R.string.dpp_error_protected,
                                AppLocaleOverride.locale
                            )
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
                        errorMessage = context.stringFor(
                            R.string.dpp_error_protected,
                            AppLocaleOverride.locale
                        )
                    )
                }
            } catch (e: Exception) {
                logger.e("DeletePages", "Cannot open PDF", e)
                closeCurrentPdf()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isLoaded = false,
                        errorMessage = context.stringFor(
                            R.string.dpp_error_invalid,
                            AppLocaleOverride.locale
                        )
                    )
                }
            }
        }
    }

    /** Reports that a picked file is not a PDF. */
    fun reportInvalidFileType() {
        _state.update {
            it.copy(
                errorMessage = context.stringFor(
                    R.string.tools_invalid_file_type,
                    AppLocaleOverride.locale,
                    "PDF"
                ),
                results = emptyList(),
            )
        }
    }

    fun setOutputFileName(name: String) {
        _state.update { it.copy(outputFileName = name, results = emptyList(), errorMessage = null) }
    }

    /** Protection switch state (the exported PDF is encrypted when enabled). */
    fun setPasswordEnabled(enabled: Boolean) {
        _state.update {
            it.copy(
                passwordEnabled = enabled,
                results = emptyList(),
                errorMessage = null
            )
        }
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

    private fun cloudUploadAllowed(): Boolean =
        _state.value.isAuthenticated

    /** Selects which page of the current order is shown in the main preview. */
    fun selectPage(index: Int) {
        _state.update {
            val last = it.pageOrder.lastIndex
            it.copy(selectedPreviewIndex = index.coerceIn(0, last.coerceAtLeast(0)))
        }
    }

    // ── Manual page deletion ──

    /** Enters/exits the delete mode (marks pages instead of dragging them). */
    fun toggleDeleteMode() {
        val st = _state.value
        if (!st.isLoaded || st.isProcessing || st.isAnalyzingBlanks) return
        val entering = !st.deleteMode
        _state.update {
            it.copy(
                deleteMode = entering,
                markedForDeletion = if (entering) it.markedForDeletion else emptySet(),
                deleteDialogVisible = false,
                results = emptyList(),
                errorMessage = null,
            )
        }
    }

    /** Toggles the mark of the page (original 1-based number) in delete mode. */
    fun toggleMarkedForDeletion(originalPageNumber: Int) {
        val st = _state.value
        if (!st.deleteMode) return
        _state.update {
            val marked = it.markedForDeletion.toMutableSet()
            if (!marked.add(originalPageNumber)) marked.remove(originalPageNumber)
            it.copy(markedForDeletion = marked, results = emptyList(), errorMessage = null)
        }
    }

    /**
     * Deletes a single page (by its original 1-based number) straight from its
     * thumbnail's trash button, with the usual undo snapshot.
     */
    fun deletePage(originalPageNumber: Int) {
        val st = _state.value
        if (!st.isLoaded || st.isProcessing) return
        if (originalPageNumber in st.pageOrder) {
            applyDeletion(setOf(originalPageNumber))
        }
    }

    /** Marks every remaining page for deletion (batch select-all in delete mode). */
    fun markAllForDeletion() {
        val st = _state.value
        if (!st.deleteMode) return
        _state.update {
            it.copy(
                markedForDeletion = it.pageOrder.toSet(),
                results = emptyList(),
                errorMessage = null,
            )
        }
    }

    /** Clears the current bulk selection in delete mode. */
    fun clearMarkedForDeletion() {
        if (!_state.value.deleteMode) return
        _state.update { it.copy(markedForDeletion = emptySet()) }
    }

    /** Shows the confirmation dialog for the marked pages. */
    fun requestDeleteConfirmation() {
        if (_state.value.markedForDeletion.isNotEmpty()) {
            _state.update { it.copy(deleteDialogVisible = true) }
        }
    }

    fun dismissDeleteConfirmation() {
        _state.update { it.copy(deleteDialogVisible = false) }
    }

    /**
     * Applies the deletion of the marked pages: pushes the current order onto
     * the undo stack and removes the pages from the observable order. The
     * thumbnails and preview recompose immediately; the PDF is only rebuilt on
     * export.
     */
    fun confirmDeleteMarked() {
        val st = _state.value
        if (st.markedForDeletion.isEmpty()) return
        applyDeletion(st.markedForDeletion)
    }

    /** Restores the page order and preview selection of the last deletion. */
    fun undoLastDeletion() {
        val st = _state.value
        val entry = st.undoStack.lastOrNull() ?: return
        _state.update {
            it.copy(
                pageOrder = entry.pageOrder,
                selectedPreviewIndex = entry.selectedPreviewIndex.coerceIn(
                    0,
                    entry.pageOrder.lastIndex.coerceAtLeast(0)
                ),
                undoStack = it.undoStack.dropLast(1),
                deleteMode = false,
                markedForDeletion = emptySet(),
                deleteDialogVisible = false,
                results = emptyList(),
                errorMessage = null,
            )
        }
    }

    private fun applyDeletion(toRemove: Set<Int>) {
        val st = _state.value
        if (toRemove.isEmpty()) return
        if (toRemove.size >= st.pageOrder.size) {
            _state.update {
                it.copy(
                    deleteMode = false,
                    markedForDeletion = emptySet(),
                    deleteDialogVisible = false,
                    results = emptyList(),
                    errorMessage = context.stringFor(
                        R.string.dpp_cannot_delete_all,
                        AppLocaleOverride.locale
                    ),
                )
            }
            return
        }
        val newOrder = DeletePagesEngine.withoutPages(st.pageOrder, toRemove)
        _state.update {
            it.copy(
                pageOrder = newOrder,
                selectedPreviewIndex = it.selectedPreviewIndex.coerceIn(
                    0,
                    newOrder.lastIndex.coerceAtLeast(0)
                ),
                undoStack = (it.undoStack + DeletePagesUndoEntry(
                    pageOrder = st.pageOrder,
                    selectedPreviewIndex = st.selectedPreviewIndex,
                    deletedCount = toRemove.size,
                )).takeLast(MAX_UNDO_ENTRIES),
                deleteMode = false,
                markedForDeletion = emptySet(),
                deleteDialogVisible = false,
                results = emptyList(),
                errorMessage = null,
            )
        }
    }

    // ── Blank-page analysis ──

    /**
     * Starts the blank-page analysis on the IO dispatcher. For every original
     * page it gathers the PDF structure signals and the non-white pixel ratio
     * of a small render (bitmaps recycled), then classifies the page. Progress
     * ("analizando página X de Y") is reported through the state and the job
     * can be cancelled via [cancelBlankAnalysis].
     */
    fun startBlankAnalysis() {
        val st = _state.value
        if (!st.isLoaded || st.isProcessing || st.isAnalyzingBlanks) return
        blankAnalysisJob?.cancel()
        _state.update {
            it.copy(
                isAnalyzingBlanks = true,
                blankProgress = 0f,
                blankProgressLabel = context.stringFor(
                    R.string.dpp_blank_analyzing,
                    AppLocaleOverride.locale,
                    1,
                    st.pageCount.coerceAtLeast(1),
                ),
                blankResult = null,
                results = emptyList(),
                errorMessage = null,
            )
        }
        blankAnalysisJob = viewModelScope.launch {
            try {
                val input = currentFile ?: throw IllegalStateException("No PDF loaded")
                val params = BlankDetectionParams()
                val findings = withContext(Dispatchers.IO) {
                    PDDocument.load(input).use { doc ->
                        val count = doc.numberOfPages
                        val result = mutableListOf<BlankPageInfo>()
                        for (index in 0 until count) {
                            if (!coroutineContext.isActive) break
                            val originalPageNumber = index + 1
                            _state.update {
                                it.copy(
                                    blankProgress = index.toFloat() / count.coerceAtLeast(1),
                                    blankProgressLabel = context.stringFor(
                                        R.string.dpp_blank_analyzing,
                                        AppLocaleOverride.locale,
                                        originalPageNumber,
                                        count,
                                    ),
                                )
                            }
                            val signals = DeletePagesEngine.pageContentSignals(doc, index)
                            val ratio = analyzeNonWhiteRatio(index) ?: 0f
                            val status = DeletePagesEngine.classifyBlank(signals, ratio, params)
                            if (status != BlankPageStatus.HAS_CONTENT) {
                                result.add(BlankPageInfo(originalPageNumber, status))
                            }
                        }
                        result
                    }
                }
                _state.update {
                    it.copy(
                        isAnalyzingBlanks = false,
                        blankProgress = null,
                        blankProgressLabel = null,
                        blankResult = findings,
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        isAnalyzingBlanks = false,
                        blankProgress = null,
                        blankProgressLabel = null
                    )
                }
                throw e
            } catch (e: Exception) {
                logger.e("DeletePages", "Blank analysis failed", e)
                _state.update {
                    it.copy(
                        isAnalyzingBlanks = false,
                        blankProgress = null,
                        blankProgressLabel = null,
                        errorMessage = context.stringFor(
                            R.string.dpp_blank_analysis_failed,
                            AppLocaleOverride.locale
                        ),
                    )
                }
            }
        }
    }

    /** Cancels a running blank-page analysis. */
    fun cancelBlankAnalysis() {
        blankAnalysisJob?.cancel()
        blankAnalysisJob = null
        _state.update {
            it.copy(isAnalyzingBlanks = false, blankProgress = null, blankProgressLabel = null)
        }
    }

    /** Dismisses the blank-page result dialog without deleting anything. */
    fun dismissBlankResult() {
        _state.update { it.copy(blankResult = null) }
    }

    /**
     * Deletes the pages the user kept checked in the blank-result dialog (their
     * original 1-based numbers), with the usual undo snapshot.
     */
    fun confirmBlankDeletion(selected: Set<Int>) {
        _state.update { it.copy(blankResult = null) }
        if (selected.isNotEmpty()) {
            applyDeletion(selected)
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
                val rendered =
                    PdfPreviewRenderer.renderWithOomRetry(renderer, pageIndex, targetWidthPx)
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

    /**
     * Renders the page at half the preview width (small, fast) and returns the
     * fraction of non-white pixels outside the ignored margins. The bitmap is
     * always recycled. Returns null if the page cannot be rendered.
     */
    private suspend fun analyzeNonWhiteRatio(pageIndex: Int): Float? =
        previewRenderMutex.withLock {
            withContext(Dispatchers.IO) {
                val renderer = currentRenderer ?: return@withContext null
                val params = BlankDetectionParams()
                val bitmap = runCatching {
                    PdfPreviewRenderer.renderPage(renderer, pageIndex, BLANK_ANALYSIS_TARGET_WIDTH)
                }.getOrNull() ?: return@withContext null
                try {
                    nonWhiteRatio(bitmap, params.marginFraction, params.whiteTolerance)
                } finally {
                    bitmap.recycle()
                }
            }
        }

    /** Fraction (0..1) of pixels outside the margins that are not near-white. */
    private fun nonWhiteRatio(bitmap: Bitmap, marginFraction: Float, whiteTolerance: Int): Float {
        val w = bitmap.width
        val h = bitmap.height
        val x0 = (w * marginFraction).toInt()
        val y0 = (h * marginFraction).toInt()
        val x1 = w - x0
        val y1 = h - y0
        if (x1 <= x0 || y1 <= y0) return 0f
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val threshold = 255 - whiteTolerance
        var nonWhite = 0
        var total = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                total++
                val px = pixels[row + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                if (r < threshold || g < threshold || b < threshold) nonWhite++
            }
        }
        return if (total == 0) 0f else nonWhite.toFloat() / total
    }

    // ── Export ──

    /** Exports the current pages as a new PDF in the destination configured in Settings. */
    fun exportPdf() {
        val st = _state.value
        if (!st.isLoaded || st.isProcessing) return
        if (st.pageOrder.isEmpty()) {
            _state.update {
                it.copy(
                    errorMessage = context.stringFor(
                        R.string.dpp_nothing_to_export,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }
        if (st.passwordEnabled && st.password.isBlank()) {
            _state.update {
                it.copy(
                    errorMessage = context.stringFor(
                        R.string.dpp_password_required,
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
                        R.string.dpp_destination_unavailable,
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
                progressLabel = context.stringFor(R.string.dpp_progress, AppLocaleOverride.locale),
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
                viewModelScope.launch {
                    runCatching { statsRepository.logToolUsed("delete_pages") }
                    runCatching {
                        statsRepository.logScanExported(
                            format = "PDF",
                            destination = "tool_delete_pages"
                        )
                    }
                    runCatching { statsRepository.logScanOpened(source = "tool_delete_pages") }
                }
            } catch (e: Exception) {
                logger.e("DeletePages", "PDF export failed", e)
                _state.update {
                    it.copy(
                        isProcessing = false,
                        progress = null,
                        progressLabel = null,
                        errorMessage = context.stringFor(
                            R.string.dpp_error,
                            AppLocaleOverride.locale
                        )
                    )
                }
            }
        }
    }

    private suspend fun exportOnIo(st: DeletePagesUiState): Pair<DeletePagesResult, ToolOutputSaver.SaveRef> {
        val input = currentFile ?: throw IllegalStateException("No PDF loaded")
        val reordered = File(toolsDir, "deleted_${System.currentTimeMillis()}.pdf")
        val password = st.password.takeIf { st.passwordEnabled && it.isNotBlank() }
        val uploadToCloud = st.cloudUploadEnabled && cloudUploadAllowed()
        try {
            val orderIndices = st.pageOrder.map { it - 1 }
            DeletePagesEngine.reorderPages(
                input,
                orderIndices,
                reordered,
                password
            ) { done, total ->
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
            val result = DeletePagesResult(
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
    private suspend fun recordInHistory(result: DeletePagesResult, ref: ToolOutputSaver.SaveRef) {
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
            logger.e("DeletePagesHistory", "Failed to record delete history", e)
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
        return "${base}_sin_paginas.pdf"
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

    private companion object {
        /** How many undos are kept (oldest dropped first). */
        const val MAX_UNDO_ENTRIES = 10

        /** Preview-independent small width used by the blank-page analysis. */
        const val BLANK_ANALYSIS_TARGET_WIDTH = 160
    }
}
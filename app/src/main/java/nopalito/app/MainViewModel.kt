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

package nopalito.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nopalito.app.data.ImageRepository
import nopalito.app.data.Logger
import nopalito.app.data.OverlayRepository
import nopalito.app.domain.CapturedPage
import nopalito.app.domain.Rotation
import nopalito.app.domain.ScanPage
import nopalito.app.domain.ocr.ExtractDocumentTextUseCase
import nopalito.app.domain.ocr.TextExtractionState
import nopalito.app.ui.NavigationState
import nopalito.app.ui.Screen
import nopalito.app.ui.components.ImportedSignatureProcessor
import nopalito.app.ui.screens.crop.CropInitState
import nopalito.app.ui.screens.document.CurrentPageUiState
import nopalito.app.ui.screens.document.DateOverlayStyle
import nopalito.app.ui.screens.document.DocumentUiState
import nopalito.app.ui.screens.document.PageOverlays
import nopalito.app.ui.screens.document.SignatureState
import nopalito.app.ui.state.DocumentUiModel
import nopalito.app.ui.state.PageThumbnail
import nopalito.imageprocessing.ColorMode
import nopalito.imageprocessing.ImageSize
import nopalito.imageprocessing.Quad
import kotlin.math.min

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    val imageRepository: ImageRepository,
    private val overlayRepository: OverlayRepository,
    private val logger: Logger,
    private val extractTextUseCase: ExtractDocumentTextUseCase,
) : ViewModel() {

    private val _navigationState = MutableStateFlow<NavigationState?>(null)
    val currentScreen: StateFlow<Screen?> = _navigationState.map { it?.current }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _pages = MutableStateFlow<List<ScanPage>>(emptyList())
    private val _pageOverlays = MutableStateFlow<Map<String, PageOverlays>>(emptyMap())

    /**
     * Serializes whole-object page mutations (move / reorder / rotate /
     * delete) end-to-end (repository write + [_pages] publish), so rapid
     * successive drags can never overwrite a fresher order with a stale
     * snapshot (lost update). The repository mutex alone is not enough: it
     * protects the disk state, but two concurrent ViewModel coroutines could
     * still publish their snapshots in the opposite order.
     */
    private val pageMutationMutex = Mutex()

    // True once the initial overlay load has finished. Persisting before that
    // would overwrite the on-disk overlays with an empty map, silently erasing
    // signatures on startup.
    private var overlaysLoaded = false

    // True when the current document is an INE credential session (front + back).
    // Declared before `init` because the init coroutine can run synchronously on a
    // cold start (empty pages → no suspension) and reads these on line 60.
    private val _isIneDocument = MutableStateFlow(false)

    // Whether the camera is currently in INE (credential front/back) capture mode.
    private val _ineMode = MutableStateFlow(false)


    init {
        viewModelScope.launch {
            val pages = imageRepository.pages()

            _pages.value = pages

            // Restore the persisted INE session flag so resuming the app keeps the
            // credential front/back flow instead of falling back to a normal scan.
            val persistedIne = imageRepository.isIneSession()
            _isIneDocument.value = persistedIne
            _ineMode.value = persistedIne

            // Load persisted overlays from disk so signatures survive app restarts.
            val loadedOverlays = overlayRepository.loadAll()
            _pageOverlays.value = loadedOverlays
            overlaysLoaded = true

            _navigationState.value =
                if (pages.isEmpty()) {
                    NavigationState.initial()
                } else {
                    NavigationState.initial().navigateTo(Screen.Main.ResumeScan)
                }
        }
    }

    val documentUiModel: StateFlow<DocumentUiModel> =
        combine(_pages, _pageOverlays) { pages, overlays ->
            pages.map {
                val jpeg = imageRepository.getThumbnail(it.key(), overlays[it.key().pageId])
                PageThumbnail(it.key(), jpeg)
            }.toImmutableList()
        }
            .flowOn(Dispatchers.IO)
            .map { DocumentUiModel(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = DocumentUiModel()
            )

    private val _currentPageIndex = MutableStateFlow(0)
    private val _loadingPageId = MutableStateFlow<String?>(null)

    private val _pageToReplaceId = MutableStateFlow<String?>(null)

    private val _textExtraction =
        MutableStateFlow<TextExtractionState>(TextExtractionState.Idle)

    private val currentPageUiState: Flow<CurrentPageUiState?> =
        combine(_currentPageIndex, _pages, _loadingPageId) { index, pages, loadingId ->
            val page = pages.getOrNull(index)
            Pair(page, loadingId)
        }
            .mapLatest { (page, loadingId) ->
                page?.let {
                    val isLoading = (it.id == loadingId)
                    val canBeCropped = page.metadata != null
                    // Both bitmaps load concurrently: the full-res page image
                    // and the neutral filter-preview base (cached per page, so
                    // switching filters never re-renders it).
                    coroutineScope {
                        val bitmapDeferred = async {
                            try {
                                imageRepository.jpegBytes(it.key())?.toBitmap()
                            } catch (e: Exception) {
                                logger.e("MainViewModel", "Failed to load image for ${it.id}", e)
                                null
                            }
                        }
                        val previewBaseDeferred = async {
                            try {
                                imageRepository.previewBase(it.id)?.toBitmap()
                            } catch (e: Exception) {
                                logger.e("MainViewModel", "Failed to load preview base for ${it.id}", e)
                                null
                            }
                        }
                        CurrentPageUiState(
                            it.key(),
                            bitmapDeferred.await(),
                            it.colorMode,
                            canBeCropped,
                            isLoading,
                            filterPreviewBase = previewBaseDeferred.await(),
                        )
                    }
                }
            }
            .flowOn(Dispatchers.IO)

    val documentUiState: StateFlow<DocumentUiState> =
        combine(
            _currentPageIndex,
            currentPageUiState,
            documentUiModel,
            _pageOverlays,
            _textExtraction,
        ) { index, page, document, overlays, textExtraction ->
            val pageId = page?.key?.pageId
            val pageWithOverlays = if (pageId != null) {
                page.copy(overlays = overlays[pageId] ?: PageOverlays())
            } else page
            DocumentUiState(index, pageWithOverlays, document, overlays, textExtraction)
        }
            .stateIn(
                viewModelScope, SharingStarted.Eagerly,
                DocumentUiState(0, null, DocumentUiModel())
            )

    fun onPageSelected(index: Int) {
        _currentPageIndex.value = index
    }

    /**
     * Opens the existing editor keeping the two captures as separate pages. The merge
     * into a single composite sheet happens only at export time. The INE session flag
     * ([isIneDocument]) is already set by [setIneMode], so it persists until a new
     * session resets it.
     */
    fun completeIneCapture() {
        navigateTo(Screen.Main.Document(0))
    }

    /** True when the current document is an INE credential session (front + back). */
    val isIneDocument: StateFlow<Boolean> = _isIneDocument.asStateFlow()

    /** Whether the camera is currently in INE (credential front/back) capture mode. */
    val ineMode: StateFlow<Boolean> = _ineMode.asStateFlow()

    /**
     * Activates/deactivates the INE capture overlay. Activating also marks the current
     * document as an INE session, which is kept until a new session is started — so the
     * whole flow (capture, editor, export) stays INE-specific until "Nueva sesión".
     * The flag is persisted so resuming the app restores the INE session.
     */
    fun setIneMode(enabled: Boolean) {
        _ineMode.value = enabled
        // Turning the mode off also ends the credential session: the persisted flag
        // must be cleared, otherwise resuming the app would restore INE mode even
        // though the user disabled it.
        _isIneDocument.value = enabled
        imageRepository.setIneSession(enabled)
    }

    /**
     * Leaves INE mode entirely and clears the persisted credential session. Used when
     * entering QR scan mode so a stale INE session never overrides QR on app restart.
     */
    fun exitIneMode() {
        _isIneDocument.value = false
        _ineMode.value = false
        imageRepository.setIneSession(false)
    }

    fun retakePage() {
        _pageToReplaceId.value = currentPage().id
        navigateTo(Screen.Main.Camera)
    }

    /**
     * Updates the signature overlay for a page with the complete [SignatureState]
     * and rendered bitmap. Persists the change to disk.
     */
    fun updateSignature(
        pageId: String,
        state: SignatureState,
        bitmap: Bitmap,
    ) {
        android.util.Log.d(
            "ImportedSignature",
            "updateSignature page=$pageId bmp=${bitmap.width}x${bitmap.height} " +
                    "alpha=${ImportedSignatureProcessor.alphaBounds(bitmap)} source=${state.source} " +
                    "bytesLen=${state.importedImageBytes?.size}",
        )
        _pageOverlays.update { overlays ->
            val existing = overlays[pageId] ?: PageOverlays()
            val updated = existing.copy(
                signatureState = state,
                signatureBitmap = bitmap,
                signatureSource = state.source,
                signaturePositionFraction = Offset(
                    state.positionFractionX.coerceIn(0f, 1f),
                    state.positionFractionY.coerceIn(0f, 1f),
                ),
                signatureScale = state.overlayScale.coerceIn(
                    SignatureState.MIN_OVERLAY_SCALE,
                    SignatureState.MAX_OVERLAY_SCALE,
                ),
            )
            overlays + (pageId to updated)
        }
        persistOverlays()
    }

    fun updateDateOverlay(pageId: String, dateText: String, positionFraction: Offset) {
        val clamped = Offset(
            positionFraction.x.coerceIn(0f, 1f),
            positionFraction.y.coerceIn(0f, 1f),
        )
        _pageOverlays.update { overlays ->
            val existing = overlays[pageId] ?: PageOverlays()
            overlays + (pageId to existing.copy(
                dateText = dateText,
                datePositionFraction = clamped,
            ))
        }
        persistOverlays()
    }

    fun updateDateScale(pageId: String, scale: Float) {
        _pageOverlays.update { overlays ->
            val existing = overlays[pageId] ?: return@update overlays
            overlays + (pageId to existing.copy(dateScale = scale.coerceIn(0.5f, 2.5f)))
        }
        persistOverlays()
    }

    fun updateSignatureRotation(pageId: String, degrees: Float) {
        val normalized = normalizeDegrees(degrees)
        _pageOverlays.update { overlays ->
            val existing = overlays[pageId] ?: return@update overlays
            overlays + (pageId to existing.copy(signatureRotationDegrees = normalized))
        }
        persistOverlays()
    }

    fun updateDateRotation(pageId: String, degrees: Float) {
        val normalized = normalizeDegrees(degrees)
        _pageOverlays.update { overlays ->
            val existing = overlays[pageId] ?: return@update overlays
            overlays + (pageId to existing.copy(dateRotationDegrees = normalized))
        }
        persistOverlays()
    }

    private fun normalizeDegrees(degrees: Float): Float =
        ((degrees % 360f) + 360f) % 360f

    fun updateDateStyle(pageId: String, style: DateOverlayStyle) {
        _pageOverlays.update { overlays ->
            val existing = overlays[pageId] ?: return@update overlays
            overlays + (pageId to existing.copy(dateStyle = style))
        }
        persistOverlays()
    }

    fun updateSignaturePosition(pageId: String, positionFraction: Offset) {
        val clamped = Offset(
            positionFraction.x.coerceIn(0f, 1f),
            positionFraction.y.coerceIn(0f, 1f),
        )
        _pageOverlays.update { overlays ->
            val existing = overlays[pageId] ?: return@update overlays
            // Keep signatureState in sync with the new position.
            val updatedState = existing.signatureState?.copy(
                positionFractionX = clamped.x,
                positionFractionY = clamped.y,
            )
            overlays + (pageId to existing.copy(
                signaturePositionFraction = clamped,
                signatureState = updatedState,
            ))
        }
        persistOverlays()
    }

    fun updateDatePosition(pageId: String, positionFraction: Offset) {
        val clamped = Offset(
            positionFraction.x.coerceIn(0f, 1f),
            positionFraction.y.coerceIn(0f, 1f),
        )
        _pageOverlays.update { overlays ->
            val existing = overlays[pageId] ?: return@update overlays
            overlays + (pageId to existing.copy(datePositionFraction = clamped))
        }
        persistOverlays()
    }

    fun removeSignatureOverlay(pageId: String) {
        _pageOverlays.update { overlays ->
            val existing = overlays[pageId] ?: return@update overlays
            overlays + (pageId to existing.copy(
                signatureState = null,
                signatureBitmap = null,
                signaturePositionFraction = null,
            ))
        }
        persistOverlays()
    }

    fun removeDateOverlay(pageId: String) {
        _pageOverlays.update { overlays ->
            val existing = overlays[pageId] ?: return@update overlays
            overlays + (pageId to existing.copy(dateText = null, datePositionFraction = null))
        }
        persistOverlays()
    }

    /**
     * Updates the signature overlay scale. Uses the unified range
     * (0.3 .. 3.0) and keeps [] in sync.
     */
    fun updateSignatureScale(pageId: String, scale: Float) {
        val coerced =
            scale.coerceIn(SignatureState.MIN_OVERLAY_SCALE, SignatureState.MAX_OVERLAY_SCALE)
        _pageOverlays.update { overlays ->
            val existing = overlays[pageId] ?: return@update overlays
            val updatedState = existing.signatureState?.copy(overlayScale = coerced)
            overlays + (pageId to existing.copy(
                signatureScale = coerced,
                signatureState = updatedState,
            ))
        }
        persistOverlays()
    }

    fun navigateTo(destination: Screen) {
        if (destination is Screen.Main.Document) {
            require(_pages.value.isNotEmpty()) {
                "Cannot navigate to DocumentScreen with zero pages"
            }
            _currentPageIndex.value = min(_pages.value.size - 1, destination.initialPage)
        }
        _navigationState.update { it?.navigateTo(destination) }
    }

    fun navigateBack() {
        _navigationState.update { stack -> stack?.navigateBack() }
    }

    fun replaceCurrentScreen(destination: Screen) {
        _navigationState.update { stack -> stack?.navigateToReplacingCurrent(destination) }
    }

    fun rotateCurrentPage(clockwise: Boolean) {
        viewModelScope.launch {
            pageMutationMutex.withLock {
                val pages = withContext(Dispatchers.IO) {
                    imageRepository.rotate(currentPage().id, clockwise)
                    imageRepository.pages()
                }
                _pages.value = pages
            }
        }
    }

    /**
     * Moves the whole page object with [id] to [newIndex].
     *
     * The page keeps its id, image files, filter, rotation, crop, overlays
     * and OCR association: only the visual order changes. The visible
     * selection tracks the same page by id (not by index), so the navbar
     * (filter, signature, date, OCR, …) keeps editing the page the user was
     * looking at instead of whichever page lands on the old index.
     */
    fun movePage(id: String, newIndex: Int) {
        viewModelScope.launch {
            pageMutationMutex.withLock {
                val currentId = _pages.value.getOrNull(_currentPageIndex.value)?.id
                val pages = withContext(Dispatchers.IO) {
                    imageRepository.movePage(id, newIndex)
                }
                publishPagesTrackingSelection(pages, currentId, fallbackIndex = newIndex)
            }
        }
    }

    /**
     * Persists a full reorder ([ids] in visual order, as sent by the
     * drag-and-drop sheet on every change). Idempotent and convergent under
     * rapid drags: each call carries the complete sequence, never a bare
     * index delta against a possibly stale base order.
     */
    fun setPageOrder(ids: List<String>) {
        viewModelScope.launch {
            pageMutationMutex.withLock {
                val currentId = _pages.value.getOrNull(_currentPageIndex.value)?.id
                val pages = withContext(Dispatchers.IO) {
                    imageRepository.setPageOrder(ids)
                }
                publishPagesTrackingSelection(pages, currentId, fallbackIndex = 0)
            }
        }
    }

    /**
     * Publishes [pages] keeping the selection glued to [currentId] (the page
     * follows its id to its new visual position). Counters shown in the UI
     * are always derived as index + 1, so they renumber to 1..N by
     * construction and can never duplicate.
     */
    private fun publishPagesTrackingSelection(
        pages: List<ScanPage>,
        currentId: String?,
        fallbackIndex: Int,
    ) {
        _pages.value = pages
        _currentPageIndex.value = if (pages.isEmpty()) {
            0
        } else if (currentId != null) {
            pages.indexOfFirst { it.id == currentId }.takeIf { it >= 0 }
                ?: fallbackIndex.coerceIn(0, pages.lastIndex)
        } else {
            fallbackIndex.coerceIn(0, pages.lastIndex)
        }
    }

    fun deleteCurrentPage() {
        viewModelScope.launch {
            pageMutationMutex.withLock {
                val pages = withContext(Dispatchers.IO) {
                    imageRepository.delete(currentPage().id)
                    imageRepository.pages()
                }

                if (pages.isEmpty()) {
                    navigateTo(Screen.Main.Camera)
                    _currentPageIndex.value = 0
                } else if (_currentPageIndex.value >= pages.size) {
                    _currentPageIndex.value = pages.size - 1
                }
                _pages.value = pages
            }
        }
    }

    /** Direct filter selection from the filter strip. */
    fun setCurrentPageColorMode(mode: ColorMode) {
        viewModelScope.launch {
            pageMutationMutex.withLock {
                val currentPage = runCatching { currentPage() }.getOrNull() ?: return@withLock
                if (currentPage.colorMode == mode) return@withLock
                _loadingPageId.value = currentPage.id
                val pages = withContext(Dispatchers.IO) {
                    imageRepository.setColorMode(currentPage.id, mode)
                    imageRepository.pages()
                }
                _pages.value = pages
                _loadingPageId.value = null
            }
        }
    }

    /**
     * Runs OCR on the visible page bitmap and publishes the result for the
     * extract-text sheet. No-op while a previous extraction is still running.
     *
     * The page is resolved from the live selection ([_currentPageIndex] +
     * [_pages]) at tap time and its bitmap is decoded fresh: the composed
     * `documentUiState.currentPage.bitmap` can still hold the previous page
     * while the new one reloads on IO after a swipe/reorder, which used to
     * OCR the wrong page.
     */
    fun extractCurrentPageText() {
        if (_textExtraction.value is TextExtractionState.Scanning) return
        val snapshot = _pages.value
        val index = _currentPageIndex.value.coerceIn(0, snapshot.lastIndex.coerceAtLeast(0))
        val page = snapshot.getOrNull(index)
        if (page == null) {
            _textExtraction.value =
                TextExtractionState.Error("No page available for OCR")
            return
        }
        viewModelScope.launch {
            _textExtraction.value = TextExtractionState.Scanning
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { imageRepository.jpegBytes(page.key())?.toBitmap() }.getOrNull()
            }
            _textExtraction.value = if (bitmap == null) {
                TextExtractionState.Error("No page bitmap available for OCR")
            } else {
                extractTextUseCase(bitmap)
            }
        }
    }

    /** Retries the extraction after a failure. */
    fun retryTextExtraction() = extractCurrentPageText()

    /** Hides the extraction sheet and resets the flow to idle. */
    fun dismissExtractedText() {
        _textExtraction.value = TextExtractionState.Idle
    }

    fun setCurrentPageUserQuad(userQuad: Quad) {
        viewModelScope.launch {
            pageMutationMutex.withLock {
                val currentPage = currentPage()
                val totalRotation = currentPage.totalRotation()
                val rotateIterations = (4 - totalRotation.degrees / 90) % 4
                val newQuad = userQuad.rotate90(rotateIterations, ImageSize(1, 1))
                _loadingPageId.value = currentPage.id
                val pages = withContext(Dispatchers.IO) {
                    imageRepository.setUserQuad(currentPage.id, newQuad)
                    imageRepository.pages()
                }
                _pages.value = pages
                _loadingPageId.value = null
            }
        }
    }

    private fun currentPage(): ScanPage {
        val index = _currentPageIndex.value
        val pages = _pages.value
        return pages.getOrNull(index) ?: throw IllegalStateException(
            "No current page for index $index (${pages.size} pages)"
        )
    }

    fun startNewDocument() {
        _isIneDocument.value = false
        _ineMode.value = false
        imageRepository.setIneSession(false)
        _pages.value = persistentListOf()
        _pageOverlays.value = emptyMap()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                imageRepository.clear()
                overlayRepository.clear()
            }
        }
    }

    fun handleImageCaptured(capturedPage: CapturedPage) {
        viewModelScope.launch {
            pageMutationMutex.withLock {
                val replaceId = _pageToReplaceId.value
                _pageToReplaceId.value = null
                val replacedIndex = _pages.value.indexOfFirst { it.id == replaceId }
                val pages = withContext(Dispatchers.IO) {
                    val sourceJpeg = capturedPage.sourceJpeg.await()
                    if (replaceId != null) {
                        imageRepository.replacePage(
                            replaceId,
                            capturedPage.pageJpeg,
                            sourceJpeg,
                            capturedPage.metadata,
                            capturedPage.colorMode,
                        )
                    } else {
                        @Suppress("DeferredResultUnused")
                        imageRepository.add(
                            capturedPage.pageJpeg,
                            sourceJpeg,
                            capturedPage.metadata,
                            capturedPage.colorMode,
                        )
                    }
                    imageRepository.pages()
                }
                _pages.value = pages
                // In an INE session a retake replaces a single face in place; return to the
                // editor right away so the user sees the updated front/back composite. If the
                // document ended up empty (e.g. the session was reset while capturing), stay
                // on the camera instead of crashing on an editor with no pages.
                if (replaceId != null && _ineMode.value) {
                    if (pages.isNotEmpty()) {
                        navigateTo(Screen.Main.Document(replacedIndex.coerceAtLeast(0)))
                    } else {
                        navigateTo(Screen.Main.Camera)
                    }
                }
            }
        }
    }

    /**
     * Persists the current overlays map to disk in the background.
     *
     * The write is non-cancellable so the last confirmed edit is not lost
     * when the ViewModel is cleared or the process is killed right after
     * confirming a signature. [onCleared] additionally flushes any pending
     * save synchronously.
     */
    private var persistJob: Job? = null
    private fun persistOverlays() {
        if (!overlaysLoaded) return
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            withContext(Dispatchers.IO + NonCancellable) {
                overlayRepository.saveAll(_pageOverlays.value)
            }
        }
    }

    override fun onCleared() {
        // Best-effort final flush so the most recent state reaches disk even
        // if the app is closed right after an edit. Safe to call twice: the
        // repository serializes writes with a mutex.
        runCatching {
            runBlocking { overlayRepository.saveAll(_pageOverlays.value) }
        }
    }

    private val _cropInitState = MutableStateFlow<CropInitState>(CropInitState.Loading)
    val cropInitState: StateFlow<CropInitState> = _cropInitState

    private var cropInitialStateJob: Job? = null
    fun onClickOnCropButton() {
        cropInitialStateJob?.cancel()
        cropInitialStateJob = viewModelScope.launch {
            _cropInitState.value = CropInitState.Loading

            val page = currentPage()

            val metadata = page.metadata
            val rotation = page.totalRotation()

            val bitmap = withContext(Dispatchers.IO) {
                val source = imageRepository.source(page.id)
                val bytes = source?.bytes ?: return@withContext null

                val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (original != null && rotation != Rotation.R0) {
                    val matrix = Matrix().apply { postRotate(rotation.degrees.toFloat()) }
                    Bitmap.createBitmap(
                        original, 0, 0, original.width, original.height, matrix, true
                    )
                } else {
                    original
                }
            }

            val quad = metadata?.normalizedQuad?.rotate90(
                rotation.degrees / 90,
                ImageSize(1, 1)
            )

            _cropInitState.value = if (bitmap == null || quad == null)
                CropInitState.Error
            else
                CropInitState.Ready(page.id, bitmap, quad)
            navigateTo(Screen.Main.EditImage)
        }

    }
}
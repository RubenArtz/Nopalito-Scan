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

package nopalito.app.ui.screens.tools.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nopalito.app.data.Logger
import nopalito.app.ui.screens.cloud.data.ApiException
import nopalito.app.ui.screens.cloud.data.CloudConversionRepository
import nopalito.app.ui.screens.tools.PickedFile
import java.io.File

/** How a file preview is produced: native PDF pages, a local image, or a
 * document converted to a temporary PDF by the backend. */
enum class PreviewFileType { PDF, IMAGE, WORD }

/** Preview state of one file of a batch (each file gets its own thumbnail strip). */
data class PreviewFileState(
    /** Uri key used to render this file's pages (see [FilePreviewController.renderBatchPage]). */
    val uriKey: String,
    val fileName: String,
    val pageCount: Int = 0,
    val isLoading: Boolean = false,
    val failed: Boolean = false,
    /** True when the file is password-protected (cannot be previewed). */
    val protected: Boolean = false,
)

/** Read-only page preview state published to the owning ViewModel. */
data class PreviewState(
    /** How many pages the single-file preview shows; 0 = no preview. */
    val singlePageCount: Int = 0,
    /** True while a preview is being generated (backend conversions). */
    val isLoading: Boolean = false,
    /** True when the preview could not be generated (the tool still works). */
    val failed: Boolean = false,
    /** True when the file is password-protected and cannot be previewed. */
    val protected: Boolean = false,
    /** Per-file previews shown in batch mode (one entry per picked file). */
    val batch: List<PreviewFileState> = emptyList(),
)

/**
 * Read-only page preview shared by the tools ("Compress", "Protect with
 * password", "Convert to PDF").
 *
 * A single file gets a thumbnail strip: PDFs are rendered natively, images
 * are decoded locally and Word/office documents are converted to a temporary
 * PDF by the backend ([CloudConversionRepository.previewToPdf]); a batch gets
 * one read-only thumbnail strip per file. The preview is purely visual - it
 * never changes the files that the tool processes.
 *
 * The controller runs its loaders in [scope] (the owning ViewModel's scope),
 * publishes [state] and must be released with [clear] (or left to the
 * ViewModel's onCleared). Pages render on demand off the main thread; batch
 * files use a transient renderer per render, so memory stays bounded.
 */
class FilePreviewController(
    private val context: Context,
    private val logger: Logger,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private val toolsDir: File = File(context.cacheDir, "tools_preview").apply { mkdirs() }

    /** Serializes preview renders (see [renderPage]). */
    private val renderMutex = Mutex()

    /** Bumped on every [prepare]/[clear]; stale loaders stop publishing. */
    private var generation = 0

    /** Native renderer kept open for the single-file preview (closed on clear). */
    private var currentRenderer: PdfRenderer? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentPdfFile: File? = null

    /** Converted Word document (temporary PDF from the backend), deleted on clear. */
    private var currentWordPdfFile: File? = null

    /** Cached copy of the picked image, decoded on demand by the thumbnails. */
    private var currentImageFile: File? = null

    /** Cached copy per picked uri (batch mode), for on-demand page rendering. */
    private val batchFiles = mutableMapOf<String, File>()

    /** Temporary converted PDFs per picked uri (batch Word/office documents). */
    private val batchWordPdfs = mutableMapOf<String, File>()

    /** File type of the current batch, used to route [renderBatchPage]. */
    private var batchType: PreviewFileType? = null

    /**
     * Loads the preview for the picked files (single or batch). Any previous
     * preview is released first.
     */
    fun prepare(files: List<PickedFile>, type: PreviewFileType) {
        if (files.size == 1) {
            when (type) {
                PreviewFileType.PDF -> loadPdf(files.first())
                PreviewFileType.IMAGE -> loadImage(files.first())
                PreviewFileType.WORD -> loadWord(files.first())
            }
        } else {
            clear()
            loadBatch(files, type)
        }
    }

    /** Releases every cached file, renderer and temporary PDF and resets the state. */
    fun clear() {
        generation++
        releaseResources()
        _state.value = PreviewState()
    }

    private fun releaseResources() {
        runCatching { currentRenderer?.close() }
        runCatching { currentPfd?.close() }
        currentRenderer = null
        currentPfd = null
        runCatching { currentPdfFile?.delete() }
        currentPdfFile = null
        runCatching { currentWordPdfFile?.delete() }
        currentWordPdfFile = null
        runCatching { currentImageFile?.delete() }
        currentImageFile = null
        batchFiles.values.forEach { runCatching { it.delete() } }
        batchFiles.clear()
        batchWordPdfs.values.forEach { runCatching { it.delete() } }
        batchWordPdfs.clear()
        batchType = null
    }

    // ── Single file ──

    private fun loadPdf(file: PickedFile) {
        clear()
        val gen = generation
        scope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { cacheInput(file) }
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
                releaseResources()
                if (gen != generation) return@launch
                currentRenderer = renderer
                currentPfd = pfd
                currentPdfFile = cached
                _state.value = PreviewState(singlePageCount = renderer.pageCount)
            } catch (_: SecurityException) {
                // Native renderer rejects password-protected PDFs: no preview.
                if (gen == generation) {
                    clear()
                    _state.value = PreviewState(failed = true, protected = true)
                }
            } catch (e: Exception) {
                logger.e("FilePreview", "Cannot open PDF page preview", e)
                if (gen == generation) {
                    clear()
                    _state.value = PreviewState(failed = true)
                }
            }
        }
    }

    private fun loadImage(file: PickedFile) {
        clear()
        val gen = generation
        scope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { cacheInput(file) }
                if (gen != generation) {
                    cached.delete()
                    return@launch
                }
                // The bitmap is decoded on demand by the thumbnails, so each
                // thumbnail owns a bitmap it can safely recycle.
                currentImageFile = cached
                _state.value = PreviewState(singlePageCount = 1)
            } catch (e: Exception) {
                logger.e("FilePreview", "Cannot open image preview", e)
                if (gen == generation) {
                    clear()
                    _state.value = PreviewState(failed = true)
                }
            }
        }
    }

    /**
     * Word/office documents have no local renderer: the backend converts the
     * document to a temporary PDF (`api/libreoffice/preview/pdf`) whose pages
     * are shown as thumbnails; the temporary PDF is deleted on [clear].
     */
    private fun loadWord(file: PickedFile) {
        clear()
        val gen = generation
        _state.value = PreviewState(isLoading = true)
        scope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { cacheInput(file) }
                val result = withContext(Dispatchers.IO) {
                    CloudConversionRepository(context).previewToPdf(cached, file.name)
                }
                val pdf = result.getOrNull()
                if (gen != generation) {
                    cached.delete()
                    pdf?.delete()
                    return@launch
                }
                if (pdf == null) {
                    cached.delete()
                    _state.value = PreviewState(
                        failed = true,
                        protected = (result.exceptionOrNull() as? ApiException)?.code == "DOCUMENT_ENCRYPTED",
                    )
                    return@launch
                }
                val (renderer, pfd) = withContext(Dispatchers.IO) {
                    val descriptor = ParcelFileDescriptor.open(
                        pdf,
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                    try {
                        PdfRenderer(descriptor) to descriptor
                    } catch (e: Exception) {
                        descriptor.close()
                        throw e
                    }
                }
                releaseResources()
                if (gen != generation) return@launch
                currentRenderer = renderer
                currentPfd = pfd
                currentWordPdfFile = pdf
                _state.value = PreviewState(singlePageCount = renderer.pageCount)
            } catch (e: Exception) {
                logger.e("FilePreview", "Cannot generate Word preview", e)
                if (gen == generation) {
                    clear()
                    _state.value = PreviewState(failed = true)
                }
            }
        }
    }

    // ── Batch ──

    /**
     * Batch mode: caches every picked file, counts its pages (Word/office
     * documents are converted to temporary PDFs by the backend, one at a time)
     * and exposes one [PreviewFileState] per file so the UI can render a
     * thumbnail strip for each of them.
     */
    private fun loadBatch(files: List<PickedFile>, type: PreviewFileType) {
        batchType = type
        val gen = generation
        _state.value = PreviewState(
            batch = files.map { file ->
                PreviewFileState(uriKey = file.uri.toString(), fileName = file.name, isLoading = true)
            },
        )
        scope.launch {
            for (file in files) {
                val uriKey = file.uri.toString()
                try {
                    val cached = withContext(Dispatchers.IO) { cacheInput(file) }
                    if (gen != generation) {
                        cached.delete()
                        return@launch
                    }
                    batchFiles[uriKey] = cached
                    when (type) {
                        PreviewFileType.PDF -> {
                            val result = withContext(Dispatchers.IO) { countPdfPages(cached) }
                            if (gen != generation) return@launch
                            markEntry(uriKey) {
                                when {
                                    result.protected -> it.copy(failed = true, protected = true)
                                    result.count == null -> it.copy(failed = true)
                                    else -> it.copy(pageCount = result.count)
                                }
                            }
                        }

                        PreviewFileType.IMAGE ->
                            markEntry(uriKey) { it.copy(pageCount = 1) }

                        PreviewFileType.WORD -> {
                            val result = withContext(Dispatchers.IO) {
                                CloudConversionRepository(context).previewToPdf(cached, file.name)
                            }
                            val pdf = result.getOrNull()
                            if (gen != generation) {
                                pdf?.delete()
                                return@launch
                            }
                            if (pdf == null) {
                                markEntry(uriKey) {
                                    it.copy(
                                        failed = true,
                                        protected = (result.exceptionOrNull() as? ApiException)?.code == "DOCUMENT_ENCRYPTED",
                                    )
                                }
                            } else {
                                batchWordPdfs[uriKey] = pdf
                                val count = withContext(Dispatchers.IO) {
                                    runCatching { PdfPreviewRenderer.countPdfPages(pdf) }.getOrNull()
                                }
                                if (gen != generation) return@launch
                                markEntry(uriKey) {
                                    if (count == null) it.copy(failed = true)
                                    else it.copy(pageCount = count)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e("FilePreview", "Cannot build batch preview for ${file.name}", e)
                    if (gen == generation) {
                        markEntry(uriKey) { it.copy(failed = true) }
                    }
                }
            }
        }
    }

    /** Updates one batch entry; the entry always leaves the loading state. */
    private fun markEntry(uriKey: String, transform: (PreviewFileState) -> PreviewFileState) {
        _state.value = _state.value.copy(
            batch = _state.value.batch.map {
                if (it.uriKey == uriKey) transform(it.copy(isLoading = false)) else it
            },
        )
    }

    // ── Rendering ──

    /**
     * Renders one page (by 0-based index) of the single-file preview at the
     * given pixel width, off the main thread. For images it decodes a fresh
     * bitmap from the cached copy (each thumbnail owns what it renders).
     * Returns null when the page cannot be rendered or the caller was
     * cancelled while rendering (the bitmap is then recycled).
     */
    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? {
        val imageFile = currentImageFile
        if (imageFile != null) {
            return withContext(Dispatchers.IO) { decodeImageSampled(imageFile) }
        }
        return renderMutex.withLock {
            withContext(Dispatchers.IO) {
                val renderer = currentRenderer ?: return@withContext null
                val rendered = PdfPreviewRenderer.renderWithOomRetry(renderer, pageIndex, targetWidthPx)
                if (rendered != null && !coroutineContext.isActive) {
                    rendered.recycle()
                    null
                } else {
                    rendered
                }
            }
        }
    }

    /**
     * Renders one page of a batch file (by 0-based index) for its thumbnail
     * strip. A transient renderer is opened per render - never kept open for
     * every file at once - so memory stays bounded on large batches. Images
     * decode a fresh bitmap from the cached copy. Returns null on failure.
     */
    suspend fun renderBatchPage(uriKey: String, pageIndex: Int, targetWidthPx: Int): Bitmap? =
        renderMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    when (batchType) {
                        PreviewFileType.IMAGE -> batchFiles[uriKey]?.let { decodeImageSampled(it) }
                        PreviewFileType.WORD -> batchWordPdfs[uriKey]?.let {
                            renderPdfPageTransient(it, pageIndex, targetWidthPx)
                        }

                        else -> batchFiles[uriKey]?.let {
                            renderPdfPageTransient(it, pageIndex, targetWidthPx)
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }

    private fun renderPdfPageTransient(file: File, pageIndex: Int, targetWidthPx: Int): Bitmap? =
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex !in 0 until renderer.pageCount) {
                        null
                    } else {
                        PdfPreviewRenderer.renderWithOomRetry(renderer, pageIndex, targetWidthPx)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }

    /** Opens [file] transiently and returns its page count, distinguishing
     * password-protected PDFs (SecurityException) from other failures. */
    private fun countPdfPages(file: File): PdfCount =
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer -> PdfCount(renderer.pageCount, protected = false) }
            }
        } catch (_: SecurityException) {
            PdfCount(null, protected = true)
        } catch (_: Exception) {
            PdfCount(null, protected = false)
        }

    private data class PdfCount(val count: Int?, val protected: Boolean)

    private fun cacheInput(file: PickedFile): File {
        val cacheFile = File(toolsDir, "${System.currentTimeMillis()}_${file.name}")
        val input = context.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Cannot read input file")
        input.use { ins ->
            cacheFile.outputStream().use { out -> ins.copyTo(out) }
        }
        return cacheFile
    }

    /** Decodes an image with a bounded target size, so thumbnails stay cheap. */
    private fun decodeImageSampled(file: File): Bitmap? {
        val maxSide = 1024
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}

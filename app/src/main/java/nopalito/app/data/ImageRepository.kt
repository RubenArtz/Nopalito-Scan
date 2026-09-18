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

package nopalito.app.data

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nopalito.app.BuildConfig
import nopalito.app.domain.Jpeg
import nopalito.app.domain.PageMetadata
import nopalito.app.domain.PageViewKey
import nopalito.app.domain.Rotation
import nopalito.app.domain.ScanPage
import nopalito.app.platform.composeOverlaysOnBitmap
import nopalito.app.ui.screens.document.PageOverlays
import nopalito.app.ui.screens.document.toPageExportOverlays
import nopalito.imageprocessing.ColorMode
import nopalito.imageprocessing.ImageSize
import nopalito.imageprocessing.OpticalMeasures
import nopalito.imageprocessing.Point
import nopalito.imageprocessing.Quad
import nopalito.imageprocessing.cameraIntrinsics
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections.synchronizedMap

const val SOURCE_DIR_NAME = "sources"
const val PROCESSED_DIR_NAME = "scanned_pages"
const val THUMBNAIL_DIR_NAME = "thumbnails"

/**
 * Repository responsible for:
 * - page persistence (document.json)
 * - image files (work, source, thumbnails)
 * - page-level operations (add, rotate, move, delete)
 */
class ImageRepository(
    scanRootDir: File,
    val transformations: ImageTransformations,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private val sourceDir = File(scanRootDir, SOURCE_DIR_NAME).apply { mkdirs() }
    private val processedDir = File(scanRootDir, PROCESSED_DIR_NAME).apply { mkdirs() }
    private val thumbnailDir = File(scanRootDir, THUMBNAIL_DIR_NAME)
    private val scanRoot: File = scanRootDir
    private val originalsDir: File get() = OriginalStore.originalsDir(scanRoot)
    private val safeDir: File get() = OriginalStore.safeDir(scanRoot)

    private val mutex = Mutex()

    private val metadataFile = File(processedDir, "document.json")
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    /** Loaded by [loadPages]; declared first so initialization cannot reset the persisted value. */
    private var isIne: Boolean = false
    private var pages: PageStore = PageStore(loadPages())

    // Lazy, opt-in reference work: no capture-time hashing, decoding or variant allocations.
    val variants by lazy { ProcessingVariantStore(scanRoot) }
    private val referenceMutex = Mutex()

    suspend fun referenceSelection(id: String): VariantSelection = withContext(Dispatchers.IO) {
        variants.selection(id)
    }

    suspend fun referenceVariants(id: String): List<VariantMetadata> = withContext(Dispatchers.IO) {
        variants.comparison(id)
    }

    suspend fun deleteCandidateReference(id: String) = referenceMutex.withLock {
        withContext(Dispatchers.IO) {
            variants.deleteCandidate(id)
            val selection = variants.selection(id)
            mutex.withLock {
                pages.update(id) {
                    it.copy(
                        candidateVariantId = selection.candidateVariantId,
                        activeVariantId = selection.activeVariantId
                    )
                }
                saveMetadata()
            }
        }
    }

    /** This action never replaces the editor JPEG, its caches, or the preserved original. */
    suspend fun createBaselineReference(
        id: String,
        freezeStored: Boolean,
        flags: nopalito.app.domain.ScanPipelineFlags,
    ): VariantMetadata = referenceMutex.withLock {
        val snapshot = mutex.withLock { requireNotNull(pages.get(id)) { "Page no longer exists" } }
        val stored = processedImageFile(
            PageViewKey(
                id,
                Rotation.R0,
                snapshot.colorMode,
                snapshot.quadVersion
            )
        )
        val source = nopalito.app.domain.BaselineSource(originalFile(id), sourceFile(id), stored)
        val useCase = nopalito.app.domain.ReprocessBaselineUseCase(
            variants,
            nopalito.app.domain.DewarpGate { android.util.Log.i("Dewarp", it) })
        try {
            // Freeze the visible reference before the first regeneration. Active remains this JPEG.
            if (!freezeStored && variants.selection(id).baselineVariantId == null) {
                useCase.run(
                    id,
                    source,
                    flags,
                    true,
                    { nopalito.app.platform.BaselineReference.render(snapshot, it) },
                    { input, output, kind, storedCopy ->
                        nopalito.app.platform.BaselineReference.recipe(
                            snapshot,
                            input,
                            output,
                            kind,
                            storedCopy
                        )
                    },
                    { mutex.withLock { check(pages.get(id) == snapshot) { "Page changed during processing" } } })
            }
            useCase.run(
                id,
                source,
                flags,
                freezeStored || snapshot.toMetadata() == null,
                { nopalito.app.platform.BaselineReference.render(snapshot, it) },
                { input, output, kind, storedCopy ->
                    nopalito.app.platform.BaselineReference.recipe(
                        snapshot,
                        input,
                        output,
                        kind,
                        storedCopy
                    )
                },
                { mutex.withLock { check(pages.get(id) == snapshot) { "Page changed during processing" } } })
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                val selection = variants.selection(id)
                val active = selection.activeVariantId?.let { variants.metadata(id, it) }
                mutex.withLock {
                    // Do not overwrite concurrent color/crop/rotation edits with an old snapshot.
                    pages.update(id) { current ->
                        current.copy(
                            activeVariantId = selection.activeVariantId,
                            baselineVariantId = selection.baselineVariantId,
                            candidateVariantId = selection.candidateVariantId,
                            variantsVersion = selection.variantsVersion,
                            processingRecipeHash = active?.identity?.recipeHash,
                            algorithmId = active?.identity?.algorithmId,
                            regionId = active?.identity?.regionId,
                            processingStatus = selection.processingStatus,
                            processingError = selection.processingError,
                        )
                    }
                    saveMetadata()
                }
            }
        }
    }

    /** Whether the current document is an INE (credential front/back) session. */
    fun isIneSession(): Boolean = isIne

    /** Persists whether the current document is an INE session. */
    fun setIneSession(enabled: Boolean) {
        if (isIne == enabled) return
        isIne = enabled
        saveMetadata()
    }

    private val processingJobs = synchronizedMap(mutableMapOf<PageViewKey, Deferred<Unit>>())
    private val imageCache = createLruCache<PageViewKey, Deferred<Jpeg?>>(maxEntries = 50)
    private val thumbnailCache =
        createLruCache<ThumbnailCacheKey, Deferred<Jpeg?>>(maxEntries = 1000)

    /**
     * Neutral ORIGINAL renderings (thumbnail-sized) used as the base for the
     * filter-strip previews. Keyed by page + quad version; never written to
     * disk and never mutating page state.
     */
    private val previewBaseCache =
        createLruCache<PreviewBaseKey, Deferred<Jpeg?>>(maxEntries = 20)

    private fun <K, V> createLruCache(maxEntries: Int): MutableMap<K, V> =
        synchronizedMap(object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<K, V>) = size > maxEntries
        })

    // --- Metadata ---

    private fun loadPages(): MutableList<PageV2> {
        thumbnailDir.deleteRecursively() // clean up dir that was used in older versions
        normalizeLegacyFiles()
        val filesOnDisk = processedDir.listFiles()
            ?.filter { it.extension == "jpg" }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val metadata = loadMetadataDocument()
        isIne = metadata?.isIne == true

        val metadataPages = metadata?.pages
        return when {
            metadataPages != null ->
                metadataPages
                    .filter {
                        processedImageFileName(
                            it.id,
                            it.colorMode,
                            it.quadVersion
                        ) in filesOnDisk
                    }
                    .toMutableList()

            else ->
                filesOnDisk
                    .sorted()
                    .map { pageFromLegacyFileName(it) }
                    .toMutableList()
        }
    }

    private fun loadMetadataDocument(): DocumentMetadataV2? {
        if (!metadataFile.exists()) return null
        return runCatching {
            val jsonText = metadataFile.readText()
            val jsonElement = Json.parseToJsonElement(jsonText)
            val version = jsonElement.jsonObject["version"]?.jsonPrimitive?.int ?: 1
            when (version) {
                1 -> migrateFromV1(Json.decodeFromJsonElement<DocumentMetadataV1>(jsonElement))
                2 -> Json.decodeFromJsonElement<DocumentMetadataV2>(jsonElement)
                else -> error("Unsupported metadata version: $version")
            }
        }.getOrNull()
    }

    private fun migrateFromV1(meta: DocumentMetadataV1): DocumentMetadataV2 =
        DocumentMetadataV2(
            pages = meta.pages.map { pageFromLegacyFileName(it.file) },
            schemaVersion = "2.1",
            pipelineVersion = nopalito.app.domain.CaptureMetadata.PIPELINE_VERSION,
        )

    private fun pageFromLegacyFileName(fileName: String): PageV2 {
        val name = fileName.removeSuffix(".jpg")
        val dashIndex = name.lastIndexOf('-')
        val id = if (dashIndex >= 0) name.substring(0, dashIndex) else name
        // Legacy docs never had a preserved original: mark explicitly so
        // export falls back to scanned_pages with a warning.
        return PageV2(id, hasOriginal = false, processingStatus = "PROCESSED")
    }

    private fun saveMetadata() {
        val metadata = DocumentMetadataV2(
            pages = pages.pages(),
            isIne = isIne,
            schemaVersion = "2.1",
            pipelineVersion = nopalito.app.domain.CaptureMetadata.PIPELINE_VERSION,
        )
        val temporary =
            File(metadataFile.parentFile, ".tmp-document-${java.util.UUID.randomUUID()}")
        try {
            java.io.FileOutputStream(temporary).use { stream ->
                stream.write(json.encodeToString(metadata).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            publishTempFile(temporary, metadataFile)
        } finally {
            temporary.delete()
        }
    }

    // --- Main API ---

    suspend fun pages(): List<ScanPage> = mutex.withLock {
        snapshotLocked()
    }

    private fun snapshotLocked(): List<ScanPage> =
        pages.pages().mapNotNull {
            runCatching {
                val manualRotation = Rotation.fromDegrees(it.manualRotationDegrees)
                ScanPage(
                    id = it.id,
                    manualRotation = manualRotation,
                    colorMode = it.colorMode,
                    quadVersion = it.quadVersion,
                    metadata = it.toMetadata(),
                    hasOriginal = it.hasOriginal,
                    originalRelativePath = it.sourceFile,
                    sourceSha256 = it.sourceSha256,
                    safeSha256 = it.safeSha256,
                    hasSafeCopy = it.safeFile == "safe/${it.id}.jpg",
                )
            }.getOrNull()
        }

    suspend fun add(processed: Jpeg, source: Jpeg, metadata: PageMetadata, colorMode: ColorMode) =
        mutex.withLock {
            val id = newPageId()
            val key = PageViewKey(id, Rotation.R0, colorMode, 0)
            writeBytesAtomically(processedImageFile(key), processed.bytes)
            writeBytesAtomically(sourceFile(id), source.bytes)
            pages.addOrReplace(
                PageV2(
                    id = id,
                    quad = metadata.normalizedQuad.toSerializable(),
                    baseRotationDegrees = metadata.baseRotation.degrees,
                    manualRotationDegrees = Rotation.R0.degrees,
                    isColored = metadata.autoColorMode == ColorMode.COLOR,
                    colorMode = colorMode,
                    focalLength = metadata.opticalMeasures?.cameraIntrinsics?.focalLength,
                    sensorWidth = metadata.opticalMeasures?.cameraIntrinsics?.sensorWidth,
                    subjectDistance = metadata.opticalMeasures?.subjectDistance,
                    sourceWidth = metadata.sourceSize?.width?.toInt(),
                    sourceHeight = metadata.sourceSize?.height?.toInt(),
                    hasOriginal = false,
                    processingStatus = "PROCESSED",
                )
            )
            saveMetadata()
            // Pre-populate cache for R0
            imageCache.put(key, CompletableDeferred(processed))
        }

    class InsufficientStorageException(val requiredBytes: Long, val freeBytes: Long) :
        java.io.IOException("insufficient storage: required=$requiredBytes free=$freeBytes")

    class AtomicMoveException(reason: String) : java.io.IOException(reason)

    class InvalidCaptureException(reason: String) : java.io.IOException(reason)

    /**
     * Phase 1 file-capture persist: moves the CameraX temp file into
     * `originals/<id>.jpg` atomically, writes the processed page, and records
     * real dimensions. Never overwrites an existing id; failures keep the
     * temp file for diagnosis except on cancellation.
     */
    suspend fun addFileCapture(
        originalTemp: File,
        processed: Jpeg,
        metadata: PageMetadata,
        colorMode: ColorMode,
        tier: nopalito.app.domain.CaptureTier,
        capturedWidth: Int?,
        capturedHeight: Int?,
        workingWidth: Int?,
        workingHeight: Int?,
        processedWidth: Int?,
        processedHeight: Int?,
        cameraId: String?,
        rotationDegrees: Int,
        exifOrientation: Int,
        captureMode: String?,
    ): String = mutex.withLock {
        val id = newPageId()
        when (val move = OriginalStore.finalizeCapture(originalTemp, originalsDir, id)) {
            is AtomicMoveResult.Success -> {
                val key = PageViewKey(id, Rotation.R0, colorMode, 0)
                val processedFile = processedImageFile(key)
                writeBytesAtomically(processedFile, processed.bytes)
                // Legacy working source is not duplicated: editor prefers
                // originals/ when present (see updatePage).
                pages.addOrReplace(
                    PageV2(
                        id = id,
                        quad = metadata.normalizedQuad.toSerializable(),
                        baseRotationDegrees = metadata.baseRotation.degrees,
                        manualRotationDegrees = Rotation.R0.degrees,
                        isColored = metadata.autoColorMode == ColorMode.COLOR,
                        colorMode = colorMode,
                        focalLength = metadata.opticalMeasures?.cameraIntrinsics?.focalLength,
                        sensorWidth = metadata.opticalMeasures?.cameraIntrinsics?.sensorWidth,
                        subjectDistance = metadata.opticalMeasures?.subjectDistance,
                        sourceWidth = metadata.sourceSize?.width?.toInt(),
                        sourceHeight = metadata.sourceSize?.height?.toInt(),
                        captureTier = tier.name,
                        originalCaptureTier = tier.name,
                        capturedWidth = capturedWidth,
                        capturedHeight = capturedHeight,
                        workingWidth = workingWidth,
                        workingHeight = workingHeight,
                        processedWidth = processedWidth,
                        processedHeight = processedHeight,
                        sourceFile = "originals/$id.jpg",
                        processedFile = processedFile.name,
                        sourceFileSize = move.finalFile.length(),
                        processedFileSize = processed.bytes.size.toLong(),
                        sourceSha256 = move.sha256,
                        jpegQuality = nopalito.app.domain.ExportQuality.BALANCED.jpegQuality,
                        captureMode = captureMode,
                        cameraId = cameraId,
                        rotationDegrees = rotationDegrees,
                        exifOrientation = exifOrientation,
                        timestamp = System.currentTimeMillis(),
                        pipelineVersion = nopalito.app.domain.CaptureMetadata.PIPELINE_VERSION,
                        hasOriginal = true,
                        processingStatus = "PROCESSED",
                    )
                )
                saveMetadata()
                imageCache[key] = CompletableDeferred(processed)
                id
            }

            is AtomicMoveResult.InsufficientStorage ->
                throw InsufficientStorageException(move.requiredBytes, move.freeBytes)

            is AtomicMoveResult.AtomicMoveFailed ->
                throw AtomicMoveException(move.reason)

            is AtomicMoveResult.InvalidCapture ->
                throw InvalidCaptureException(move.reason)

            is AtomicMoveResult.Cancelled ->
                throw java.util.concurrent.CancellationException("capture cancelled")
        }
    }

    suspend fun setColorMode(id: String, colorMode: ColorMode) {
        updatePage(id) { page, metadata ->
            PageUpdate(
                updatedPage = page.copy(colorMode = colorMode),
                normalizedQuad = metadata.normalizedQuad,
                colorMode = colorMode,
            )
        }
    }

    suspend fun setUserQuad(id: String, newQuad: Quad) {
        android.util.Log.i(
            "Crop",
            "setUserQuad page=$id quad=${quadCorners(newQuad)}",
        )
        updatePage(id) { page, metadata ->
            PageUpdate(
                updatedPage = page.copy(
                    quadVersion = page.quadVersion + 1,
                    userQuad = newQuad.toSerializable(),
                ),
                normalizedQuad = newQuad,
                colorMode = page.colorMode ?: metadata.autoColorMode,
            )
        }
    }

    private data class PageUpdate(
        val updatedPage: PageV2,
        val normalizedQuad: Quad,
        val colorMode: ColorMode,
    )

    private suspend fun updatePage(
        id: String,
        buildUpdate: (PageV2, PageMetadata) -> PageUpdate
    ) {
        val page = mutex.withLock { pages.get(id) }
        val metadata = page?.toMetadata() ?: return
        // Prefer the preserved original; fall back to the legacy working
        // source for documents captured before Phase 1.
        val masterFile = if (originalFile(id).exists()) originalFile(id) else sourceFile(id)
        if (!masterFile.exists()) {
            android.util.Log.w("Crop", "updatePage page=$id missing master, skip reprocess")
            return
        }

        val update = buildUpdate(page, metadata)
        val key = PageViewKey(
            pageId = id,
            rotation = Rotation.R0,
            colorMode = update.colorMode,
            quadVersion = update.updatedPage.quadVersion
        )

        val processedFile = processedImageFile(key)
        android.util.Log.i(
            "Crop",
            "reprocess page=$id key=$key master=${pathForLog(masterFile)} " +
                    "processed=${pathForLog(processedFile)} quad=${quadCorners(update.normalizedQuad)} " +
                    "color=${update.colorMode}",
        )
        val job = processingJobs.computeIfAbsent(key) {
            scope.async(Dispatchers.IO) {
                // Always regenerate from the master with the current quad: the
                // warped file is the single source of truth for editor and
                // export. Never reuse a stale file for a new quad version.
                val sourceJpeg = Jpeg(masterFile.readBytes())
                val processedJpeg =
                    transformations.process(
                        sourceJpeg,
                        metadata = metadata.copy(normalizedQuad = update.normalizedQuad),
                        colorMode = update.colorMode
                    )
                writeBytesAtomically(processedFile, processedJpeg.bytes)
                android.util.Log.i(
                    "Crop",
                    "reprocessed page=$id file=${pathForLog(processedFile)} " +
                            "bytes=${processedJpeg.bytes.size} dims=${jpegBounds(processedJpeg.bytes)}",
                )
            }
        }
        try {
            job.await()
        } finally {
            processingJobs.remove(key, job)
        }

        mutex.withLock {
            val bounds =
                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(processedFile.absolutePath, bounds)
            pages.update(id) {
                update.updatedPage.copy(
                    processedFile = processedFile.name,
                    processedFileSize = runCatching { processedFile.length() }.getOrNull(),
                    processedWidth = bounds.outWidth.takeIf { it > 0 },
                    processedHeight = bounds.outHeight.takeIf { it > 0 },
                )
            }
            saveMetadata()
        }
        invalidateCachesForPage(id)
    }

    private fun quadCorners(quad: Quad): String {
        fun f(v: Double) = "%.4f".format(v)
        return "TL(${f(quad.topLeft.x)},${f(quad.topLeft.y)}) " +
                "TR(${f(quad.topRight.x)},${f(quad.topRight.y)}) " +
                "BR(${f(quad.bottomRight.x)},${f(quad.bottomRight.y)}) " +
                "BL(${f(quad.bottomLeft.x)},${f(quad.bottomLeft.y)})"
    }

    private fun jpegBounds(bytes: ByteArray): String {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            "${opts.outWidth}x${opts.outHeight}"
        } catch (_: Exception) {
            "unknown"
        }
    }

    suspend fun rotate(id: String, clockwise: Boolean) = mutex.withLock {
        val page = pages.get(id) ?: return@withLock
        val delta = if (clockwise) Rotation.R90 else Rotation.R270
        val newRotation = Rotation.fromDegrees(page.manualRotationDegrees).add(delta)
        pages.update(id) {
            it.copy(manualRotationDegrees = newRotation.degrees)
        }
        saveMetadata()
    }

    suspend fun jpegBytes(key: PageViewKey): Jpeg? =
        getOrCompute(imageCache, key, ::computeProcessedImage)


    suspend fun getThumbnail(key: PageViewKey, overlays: PageOverlays? = null): Jpeg? =
        getOrCompute(thumbnailCache, ThumbnailCacheKey(key, overlays)) { k ->
            computeThumbnail(k.key, k.overlays)
        }

    /**
     * Neutral base image for the filter-strip previews: the page source
     * rendered with [ColorMode.ORIGINAL] and reduced to thumbnail size.
     *
     * The strip draws each filter cell by applying a preview [ColorMatrix] on
     * top of a base bitmap. That base must be filter-neutral: when the page's
     * own (possibly destructive, e.g. grayscale/BW) rendering was used
     * instead, every cell inherited the selected filter. Rendered fully
     * in-memory — no file is written and no page field is touched. Returns
     * null for legacy pages without metadata/source.
     */
    suspend fun previewBase(id: String): Jpeg? {
        val page = mutex.withLock { pages.get(id) } ?: return null
        return getOrCompute(previewBaseCache, PreviewBaseKey(id, page.quadVersion)) {
            withContext(Dispatchers.IO) {
                val metadata = page.toMetadata() ?: return@withContext null
                val file =
                    if (originalFile(page.id).exists()) originalFile(page.id) else sourceFile(page.id)
                if (!file.exists()) return@withContext null
                runCatching {
                    val neutral = transformations.process(
                        Jpeg(file.readBytes()),
                        metadata,
                        ColorMode.ORIGINAL,
                    )
                    transformations.resizeToThumbnail(neutral)
                }.getOrNull()
            }
        }
    }

    // --- Cache compute functions ---

    private suspend fun <K> getOrCompute(
        cache: MutableMap<K, Deferred<Jpeg?>>,
        key: K,
        compute: suspend (K) -> Jpeg?
    ): Jpeg? {
        val deferred = cache.computeIfAbsent(key) { k ->
            scope.async(Dispatchers.IO) { compute(k) }
        }
        try {
            return deferred.await()
        } catch (e: Exception) {
            cache.remove(key, deferred)
            throw e
        }
    }

    private suspend fun computeProcessedImage(key: PageViewKey): Jpeg? =
        withContext(Dispatchers.IO) {
            val baseFile = processedImageFile(key)
            if (!baseFile.exists()) return@withContext null
            val baseJpeg = Jpeg(baseFile.readBytes())
            if (key.rotation == Rotation.R0) {
                baseJpeg
            } else {
                transformations.rotate(
                    baseJpeg,
                    key.rotation.degrees
                )
            }
        }

    private suspend fun computeThumbnail(key: PageViewKey, overlays: PageOverlays?): Jpeg? =
        withContext(Dispatchers.IO) {
            val processed = getOrCompute(imageCache, key, ::computeProcessedImage)
                ?: return@withContext null
            try {
                bakeOverlaysOnThumbnail(transformations.resizeToThumbnail(processed), overlays)
            } catch (e: Exception) {
                val message = "Failed to compute thumbnail for ${key.pageId}"
                logger.e("ImageRepository", message, e)
                null
            }
        }

    /**
     * Cache key that includes the page's overlay state, so the thumbnail is
     * recomputed (and its signature/date re-baked) whenever the overlays change.
     */
    private data class ThumbnailCacheKey(
        val key: PageViewKey,
        val overlays: PageOverlays?,
    )

    /**
     * Cache key for the neutral filter-preview base: page id + quad version,
     * so a recrop automatically recomputes it.
     */
    private data class PreviewBaseKey(
        val pageId: String,
        val quadVersion: Int,
    )

    /**
     * Draws the page's signature/date overlays onto the thumbnail, so previews
     * match what the editor and the exported file show. Returns the input
     * thumbnail unchanged when there are no overlays.
     */
    private fun bakeOverlaysOnThumbnail(thumbnail: Jpeg, overlays: PageOverlays?): Jpeg {
        val exportOverlays = overlays?.toPageExportOverlays() ?: return thumbnail
        val base = thumbnail.toBitmap()
        val composed = composeOverlaysOnBitmap(base, exportOverlays)
        if (composed == null) {
            base.recycle()
            return thumbnail
        }
        val bos = ByteArrayOutputStream()
        composed.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        base.recycle()
        composed.recycle()
        return Jpeg(bos.toByteArray())
    }

    // --- Other operations ---

    private fun processedImageFileName(
        id: String,
        colorMode: ColorMode?,
        quadVersion: Int
    ): String {
        val sb = StringBuilder(id)
        if (colorMode != null)
            sb.append(".").append(colorMode.name.lowercase())
        if (quadVersion > 0)
            sb.append(".q").append(quadVersion)
        sb.append(".jpg")
        return sb.toString()
    }

    private fun processedImageFile(key: PageViewKey): File =
        File(processedDir, processedImageFileName(key.pageId, key.colorMode, key.quadVersion))

    /** Real on-disk processed artifact used only by the central export preparer. */
    fun processedFileForExport(key: PageViewKey): File = processedImageFile(key)

    private fun sourceFile(id: String): File =
        File(sourceDir, "$id.jpg")

    /** Legacy uncropped source used only as a preparation fallback. */
    fun legacySourceFileForExport(id: String): File = sourceFile(id)

    private fun writeBytesAtomically(target: File, bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Refusing to write an empty image: ${target.name}" }
        target.parentFile?.mkdirs()
        val temp = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
        try {
            java.io.FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(temp.length() == bytes.size.toLong()) { "Incomplete image write: ${target.name}" }
            publishTempFile(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun source(id: String): Jpeg? {
        val file = sourceFile(id)
        return if (file.exists()) Jpeg(file.readBytes()) else null
    }

    /**
     * Phase 1 original handling.
     *
     * - `originals/` is the master source and is never deleted by quota.
     * - `safe/` is optional and regenerable; quota LRU may delete it.
     * - `sources/` (legacy working source) is kept for backward compat.
     */
    fun originalFile(id: String): File = File(originalsDir, "$id.jpg")

    fun safeJpegFile(id: String): File = File(safeDir, "$id.jpg")

    fun originalBytes(id: String): ByteArray? {
        val f = originalFile(id)
        return if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null
    }

    /**
     * Export-only original lookup. A stray/orphan file is never accepted merely because its
     * name matches: persisted provenance must mark it as the original and its checksum (when
     * available) must still match.
     */
    fun verifiedOriginalBytesForExport(
        id: String,
        hasOriginal: Boolean,
        expectedRelativePath: String?,
        expectedSha256: String?,
    ): ByteArray? {
        if (!hasOriginal || expectedRelativePath != "originals/$id.jpg") return null
        val bytes = originalBytes(id) ?: return null
        if (expectedSha256 != null && nopalito.app.domain.OriginalIntegrity.sha256(bytes) != expectedSha256) {
            android.util.Log.w("ExportPrepare", "originalChecksumMismatch pageId=$id")
            return null
        }
        return bytes
    }

    /**
     * Editing master bytes: the preserved original for Phase 1 captures,
     * the legacy working source otherwise. Crop init must use this: file
     * captures never write `sources/`, so [source] alone returns null and
     * the crop screen opens empty.
     */
    fun masterBytes(id: String): ByteArray? =
        originalBytes(id) ?: source(id)?.bytes

    fun safeBytes(id: String): ByteArray? {
        val f = safeJpegFile(id)
        return if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null
    }

    fun verifiedSafeBytesForExport(
        id: String,
        hasSafeCopy: Boolean,
        expectedSha256: String?,
    ): ByteArray? {
        if (!hasSafeCopy) return null
        val bytes = safeBytes(id) ?: return null
        if (expectedSha256 != null && nopalito.app.domain.OriginalIntegrity.sha256(bytes) != expectedSha256) {
            android.util.Log.w("ExportPrepare", "safeChecksumMismatch pageId=$id")
            return null
        }
        return bytes
    }

    /**
     * Quota cleanup: deletes only regenerable files (safe/, thumbnails,
     * processed files that can be re-rendered from source). Never deletes
     * `originals/`. Returns freed bytes.
     */
    suspend fun enforceQuota(maxSafeBytes: Long = 300L * 1024L * 1024L): Long =
        withContext(Dispatchers.IO) {
            var freed = 0L
            val safeFiles = safeDir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
            var total = safeFiles.sumOf { it.length() }
            for (f in safeFiles) {
                if (total <= maxSafeBytes) break
                val size = f.length()
                if (f.delete()) {
                    freed += size
                    total -= size
                }
            }
            thumbnailDir.listFiles()?.forEach { it.delete() }
            freed
        }

    /**
     * Explicit user-initiated deletion of a preserved original. Callers must
     * show confirmation explaining that maximum-resolution and HIGH processing
     * from the camera master will fall back to the stored processed page.
     */
    suspend fun deleteOriginalWithConfirmation(id: String, confirmed: Boolean): Boolean {
        if (!confirmed) return false
        return mutex.withLock {
            val page = pages.get(id) ?: return@withLock false
            runCatching { originalFile(id).delete() }
            pages.update(id) {
                it.copy(
                    hasOriginal = false,
                    sourceFile = null,
                    sourceSha256 = null
                )
            }
            saveMetadata()
            !originalFile(page.id).exists()
        }
    }

    /**
     * Moves the whole page object with [id] to [newIndex] and returns the new
     * ordered snapshot atomically (move + persist + snapshot under a single
     * lock), so concurrent reorder calls can never interleave a stale read
     * between the move and the snapshot.
     *
     * Only the in-memory order and `document.json` change: no image file is
     * touched, decoded, recompressed or resized here, so quality and
     * resolution are preserved bit-for-bit.
     */
    suspend fun movePage(id: String, newIndex: Int): List<ScanPage> = mutex.withLock {
        pages.move(id, newIndex)
        saveMetadata()
        snapshotLocked()
    }

    /**
     * Persists a full reorder ([ids] in visual order) atomically and returns
     * the new snapshot. Drag-and-drop sheets send the complete sequence on
     * every change, which makes rapid successive drags idempotent and
     * convergent however they interleave. Same guarantee as [movePage]: whole
     * objects move, image files are never rewritten.
     */
    suspend fun setPageOrder(ids: List<String>): List<ScanPage> = mutex.withLock {
        pages.setOrder(ids)
        saveMetadata()
        snapshotLocked()
    }

    suspend fun delete(id: String) = mutex.withLock {
        pages.delete(id)
        saveMetadata()
        sourceFile(id).delete()
        runCatching { originalFile(id).delete() }
        runCatching { safeJpegFile(id).delete() }
        processedDir.listFiles()
            ?.filter { it.name.startsWith("$id.") || it.name.startsWith("$id-") }
            ?.forEach { it.delete() }
        // No need to clean caches: stale entries will be evicted by LRU
    }

    suspend fun replacePage(
        id: String,
        processed: Jpeg,
        source: Jpeg,
        metadata: PageMetadata,
        colorMode: ColorMode
    ) =
        mutex.withLock {
            val existing = pages.get(id) ?: return@withLock
            // A replacement is a new source revision. A new quad version prevents every
            // in-memory and on-disk cache from confusing it with the previous page.
            val newQuadVersion = existing.quadVersion + 1
            val key = PageViewKey(id, Rotation.R0, colorMode, newQuadVersion)
            writeBytesAtomically(processedImageFile(key), processed.bytes)
            val master = if (existing.hasOriginal) originalFile(id) else sourceFile(id)
            writeBytesAtomically(master, source.bytes)
            safeJpegFile(id).delete()
            // Update PageV2 in-place
            pages.addOrReplace(
                existing.copy(
                    quad = metadata.normalizedQuad.toSerializable(),
                    userQuad = null,
                    quadVersion = newQuadVersion,
                    baseRotationDegrees = metadata.baseRotation.degrees,
                    isColored = metadata.autoColorMode == ColorMode.COLOR,
                    colorMode = colorMode,
                    focalLength = metadata.opticalMeasures?.cameraIntrinsics?.focalLength,
                    sensorWidth = metadata.opticalMeasures?.cameraIntrinsics?.sensorWidth,
                    subjectDistance = metadata.opticalMeasures?.subjectDistance,
                    sourceWidth = metadata.sourceSize?.width?.toInt(),
                    sourceHeight = metadata.sourceSize?.height?.toInt(),
                    sourceFile = if (existing.hasOriginal) "originals/$id.jpg" else null,
                    sourceFileSize = source.bytes.size.toLong(),
                    sourceSha256 = nopalito.app.domain.OriginalIntegrity.sha256(source.bytes),
                    processedFile = processedImageFile(key).name,
                    processedFileSize = processed.bytes.size.toLong(),
                    timestamp = System.currentTimeMillis(),
                    // preserve manualRotationDegrees
                )
            )
            saveMetadata()
            // Only after the new source, processed image and metadata are durable may old
            // processed variants be removed. A failed replacement keeps the last valid page.
            processedDir.listFiles()
                ?.filter {
                    (it.name.startsWith("$id.") || it.name.startsWith("$id-")) &&
                            it.canonicalFile != processedImageFile(key).canonicalFile
                }
                ?.forEach { it.delete() }
            // Drop stale cached images/thumbnails for this page: the key is unchanged
            // after a retake, so without this the editor preview keeps showing the old photo.
            invalidateCachesForPage(id)
            // Pre-populate cache
            imageCache[key] = CompletableDeferred(processed)
        }

    /** Removes all cached image/thumbnail/preview entries belonging to [pageId]. */
    private fun invalidateCachesForPage(pageId: String) {
        synchronized(imageCache) {
            imageCache.entries.removeAll { it.key.pageId == pageId }
        }
        synchronized(thumbnailCache) {
            thumbnailCache.entries.removeAll { it.key.key.pageId == pageId }
        }
        synchronized(previewBaseCache) {
            previewBaseCache.entries.removeAll { it.key.pageId == pageId }
        }
    }

    suspend fun clear() = mutex.withLock {
        pages.clear()
        saveMetadata()
        sourceDir.listFiles()?.forEach { it.delete() }
        runCatching { originalsDir.listFiles()?.forEach { it.delete() } }
        runCatching { safeDir.listFiles()?.forEach { it.delete() } }
        processedDir.listFiles()?.forEach { it.delete() }
        synchronized(imageCache) { imageCache.clear() }
        synchronized(thumbnailCache) { thumbnailCache.clear() }
        synchronized(previewBaseCache) { previewBaseCache.clear() }
    }

    // --- Legacy migration ---

    data class DiskPageFiles(
        val base: File?,
        val rotated: List<File>
    )

    // Legacy normalization strategy:
    // If only rotated files exist, keep ONE arbitrarily as base (id.jpg)
    // and discard the others. We intentionally sacrifice exact rotation
    // fidelity to restore a coherent model.
    private fun normalizeLegacyFiles() {
        val jpgs = processedDir.listFiles()?.filter { it.extension == "jpg" }.orEmpty()
        val byId = jpgs.groupBy { file ->
            val name = file.name.removeSuffix(".jpg")
            val dash = name.lastIndexOf('-')
            if (dash >= 0) name.substring(0, dash) else name
        }
        val pages = byId.mapValues { (_, files) ->
            val base = files.find { !it.name.contains('-') }
            val rotated = files.filter { it.name.contains('-') }
            DiskPageFiles(base, rotated)
        }
        pages.forEach { (id, files) ->
            if (files.base == null && files.rotated.isNotEmpty()) {
                val sortedRotatedFiles = files.rotated.sortedBy { it.name }
                val legacyFile = sortedRotatedFiles.first()
                val target = File(processedDir, "$id.jpg")
                if (legacyFile.renameTo(target)) {
                    sortedRotatedFiles.drop(1).forEach { it.delete() }
                }
            }
        }
    }

    fun lastAddedSourceFile(): File? {
        val sourceFiles = sourceDir.listFiles()?.filter { it.extension == "jpg" }
        if (sourceFiles.isNullOrEmpty()) {
            return null
        }
        return sourceFiles.maxByOrNull { it.lastModified() }
    }

    /**
     * Returns the absolute path of the source image file for a given page ID,
     * or null if the file does not exist.
     */
    fun sourceFilePath(id: String): String? {
        val file = originalFile(id).takeIf(File::exists) ?: sourceFile(id)
        return if (file.exists()) file.absolutePath else null
    }

    private fun pathForLog(file: File): String = if (BuildConfig.DEBUG) {
        file.absolutePath
    } else {
        "sha256:${
            nopalito.app.domain.OriginalIntegrity.sha256(file.absolutePath.toByteArray()).take(16)
        }"
    }

    private fun newPageId(): String =
        "${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().replace("-", "")}"
}

fun Quad.toSerializable(): NormalizedQuad =
    NormalizedQuad(
        topLeft = PointD(topLeft.x, topLeft.y),
        topRight = PointD(topRight.x, topRight.y),
        bottomRight = PointD(bottomRight.x, bottomRight.y),
        bottomLeft = PointD(bottomLeft.x, bottomLeft.y)
    )

fun NormalizedQuad.toQuad(): Quad =
    Quad(
        Point(topLeft.x, topLeft.y),
        Point(topRight.x, topRight.y),
        Point(bottomRight.x, bottomRight.y),
        Point(bottomLeft.x, bottomLeft.y)
    )

fun PageV2.toMetadata(): PageMetadata? {
    if (quad == null || isColored == null) return null
    val cameraIntrinsics = cameraIntrinsics(focalLength, sensorWidth)
    val sourceSize =
        if (sourceWidth != null && sourceHeight != null)
            ImageSize(sourceWidth, sourceHeight)
        else
            null
    return PageMetadata(
        (userQuad ?: quad).toQuad(),
        Rotation.fromDegrees(baseRotationDegrees),
        if (isColored) ColorMode.COLOR else ColorMode.GRAYSCALE,
        sourceSize,
        cameraIntrinsics?.let { OpticalMeasures(it, subjectDistance) },
    )
}

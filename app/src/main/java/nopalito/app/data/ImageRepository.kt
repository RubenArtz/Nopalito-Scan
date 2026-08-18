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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import nopalito.app.domain.*
import nopalito.app.platform.composeOverlaysOnBitmap
import nopalito.app.ui.screens.document.PageOverlays
import nopalito.app.ui.screens.document.toPageExportOverlays
import nopalito.imageprocessing.*
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

    private val mutex = Mutex()

    private val metadataFile = File(processedDir, "document.json")
    private val json = Json { prettyPrint = false; encodeDefaults = true }
    private var pages: PageStore = PageStore(loadPages())

    /** Persisted flag: the current document is an INE (credential front/back) session. */
    private var isIne: Boolean = false

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
    private val thumbnailCache = createLruCache<ThumbnailCacheKey, Deferred<Jpeg?>>(maxEntries = 1000)

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
        DocumentMetadataV2(pages = meta.pages.map { pageFromLegacyFileName(it.file) })

    private fun pageFromLegacyFileName(fileName: String): PageV2 {
        val name = fileName.removeSuffix(".jpg")
        val dashIndex = name.lastIndexOf('-')
        val id = if (dashIndex >= 0) name.substring(0, dashIndex) else name
        return PageV2(id)
    }

    private fun saveMetadata() {
        val metadata = DocumentMetadataV2(pages = pages.pages(), isIne = isIne)
        metadataFile.writeText(json.encodeToString(metadata))
    }

    // --- Main API ---

    suspend fun pages(): List<ScanPage> = mutex.withLock {
        pages.pages().mapNotNull {
            runCatching {
                val manualRotation = Rotation.fromDegrees(it.manualRotationDegrees)
                ScanPage(it.id, manualRotation, it.colorMode, it.quadVersion, it.toMetadata())
            }.getOrNull()
        }
    }

    suspend fun add(processed: Jpeg, source: Jpeg, metadata: PageMetadata, colorMode: ColorMode) =
        mutex.withLock {
            val id = "${System.currentTimeMillis()}"
            val key = PageViewKey(id, Rotation.R0, colorMode, 0)
            processedImageFile(key).writeBytes(processed.bytes)
            sourceFile(id).writeBytes(source.bytes)
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
                )
            )
            saveMetadata()
            // Pre-populate cache for R0
            imageCache.put(key, CompletableDeferred(processed))
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
        val sourceFile = sourceFile(id)
        if (!sourceFile.exists())
            return

        val update = buildUpdate(page, metadata)
        val key = PageViewKey(
            pageId = id,
            rotation = Rotation.R0,
            colorMode = update.colorMode,
            quadVersion = update.updatedPage.quadVersion
        )

        val processedFile = processedImageFile(key)
        val job = processingJobs.computeIfAbsent(key) {
            scope.async(Dispatchers.IO) {
                if (!processedFile.exists()) {
                    val sourceJpeg = Jpeg(sourceFile.readBytes())
                    val processedJpeg =
                        transformations.process(
                            sourceJpeg,
                            metadata = metadata.copy(normalizedQuad = update.normalizedQuad),
                            colorMode = update.colorMode
                        )
                    processedFile.writeBytes(processedJpeg.bytes)
                }
            }
        }
        try {
            job.await()
        } finally {
            processingJobs.remove(key, job)
        }

        mutex.withLock {
            pages.update(id) { update.updatedPage }
            saveMetadata()
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

    private fun sourceFile(id: String): File =
        File(sourceDir, "$id.jpg")

    fun source(id: String): Jpeg? {
        val file = sourceFile(id)
        return if (file.exists()) Jpeg(file.readBytes()) else null
    }

    suspend fun movePage(id: String, newIndex: Int) = mutex.withLock {
        pages.move(id, newIndex)
        saveMetadata()
    }

    suspend fun delete(id: String) = mutex.withLock {
        pages.delete(id)
        saveMetadata()
        sourceFile(id).delete()
        processedDir.listFiles()
            ?.filter { it.name.startsWith("$id.") || it.name.startsWith("$id-") }
            ?.forEach { it.delete() }
        // No need to clean caches: stale entries will be evicted by LRU
    }

    suspend fun replacePage(id: String, processed: Jpeg, source: Jpeg, metadata: PageMetadata, colorMode: ColorMode) =
        mutex.withLock {
            val existing = pages.get(id) ?: return@withLock
            // Delete old processed files for this id
            processedDir.listFiles()
                ?.filter { it.name.startsWith("$id.") || it.name.startsWith("$id-") }
                ?.forEach { it.delete() }
            // Delete old source
            sourceFile(id).delete()
            // Write new files with same id
            val key = PageViewKey(id, Rotation.R0, colorMode, existing.quadVersion)
            processedImageFile(key).writeBytes(processed.bytes)
            sourceFile(id).writeBytes(source.bytes)
            // Update PageV2 in-place
            pages.addOrReplace(
                existing.copy(
                    quad = metadata.normalizedQuad.toSerializable(),
                    baseRotationDegrees = metadata.baseRotation.degrees,
                    colorMode = colorMode,
                    // preserve manualRotationDegrees
                )
            )
            saveMetadata()
            // Drop stale cached images/thumbnails for this page: the key is unchanged
            // after a retake, so without this the editor preview keeps showing the old photo.
            invalidateCachesForPage(id)
            // Pre-populate cache
            imageCache[key] = CompletableDeferred(processed)
        }

    /** Removes all cached image/thumbnail entries belonging to [pageId]. */
    private fun invalidateCachesForPage(pageId: String) {
        synchronized(imageCache) {
            imageCache.entries.removeAll { it.key.pageId == pageId }
        }
        synchronized(thumbnailCache) {
            thumbnailCache.entries.removeAll { it.key.key.pageId == pageId }
        }
    }

    suspend fun clear() = mutex.withLock {
        pages.clear()
        saveMetadata()
        sourceDir.listFiles()?.forEach { it.delete() }
        processedDir.listFiles()?.forEach { it.delete() }
        synchronized(imageCache) { imageCache.clear() }
        synchronized(thumbnailCache) { thumbnailCache.clear() }
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
        val file = sourceFile(id)
        return if (file.exists()) file.absolutePath else null
    }
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

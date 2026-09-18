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

package nopalito.app.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import nopalito.app.BuildConfig
import nopalito.app.data.ImageRepository
import nopalito.app.data.publishTempFile
import nopalito.app.data.syncBestEffort
import nopalito.app.platform.composeOverlaysOnBitmap
import nopalito.app.platform.processedImage
import nopalito.imageprocessing.ColorMode
import nopalito.imageprocessing.EstimatedDimensions
import nopalito.imageprocessing.Quad
import nopalito.imageprocessing.estimateRealDimensions
import nopalito.imageprocessing.resizeForMaxPixels
import nopalito.imageprocessing.rotate
import nopalito.imageprocessing.scaledTo
import org.opencv.core.Mat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** The real physical input used to create an export artifact. */
enum class ExportSourceType {
    CAMERA_ORIGINAL,
    SAFE_COPY,
    LEGACY_SOURCE,
    STORED_PROCESSED,
    COMPOSITE,
}

/** What the materialized file contains; none of these values means raw camera data. */
enum class ProcessedExportArtifactType {
    PROCESSED_FULL_RES,
    PROCESSED_HIGH,
    PROCESSED_BALANCED,
    PROCESSED_COMPRESSED,
    PROCESSED_PREVIEW,
    PROCESSED_COMPOSITE,
}

data class ExportQuadPoint(val x: Double, val y: Double)

/** Overlay input consumed only by the central preparation layer. */
data class ExportPageOverlays(
    val signatureBitmap: Bitmap? = null,
    val signaturePositionFractionX: Float? = null,
    val signaturePositionFractionY: Float? = null,
    val signatureScale: Float = 1.0f,
    val signatureRotationDegrees: Float = 0f,
    val dateText: String? = null,
    val datePositionFractionX: Float? = null,
    val datePositionFractionY: Float? = null,
    val dateScale: Float = 1.0f,
    val dateRotationDegrees: Float = 0f,
    val dateStyleTextColor: Long = 0xFFFFFFFF,
    val dateStyleFontSize: Float = 14f,
    val dateStyleBackgroundStyle: String = "CAPSULE",
    val dateStyleBackgroundColor: Long = 0x80000000,
)

/**
 * The only image object accepted by JPEG, PNG, PDF and Word exporters.
 * [file] is always a non-empty JPEG which already contains perspective,
 * rotation, color mode and overlays. Camera/source files never cross this boundary.
 */
@ConsistentCopyVisibility
data class ProcessedExportPage internal constructor(
    val pageId: String,
    val file: File,
    val sourceType: ExportSourceType,
    val artifactType: ProcessedExportArtifactType,
    val inputAbsolutePath: String,
    val inputByteSize: Long,
    val inputWidth: Int,
    val inputHeight: Int,
    val width: Int,
    val height: Int,
    val quad: List<ExportQuadPoint>,
    val quadVersion: Int?,
    val rotation: Int,
    val colorMode: ColorMode?,
    val requestedQuality: ExportQuality,
    val byteSize: Long,
    val sha256: String,
    val physicalWidthMm: Double? = null,
    val physicalHeightMm: Double? = null,
) {
    val absolutePath: String get() = file.absolutePath

    fun readJpeg(): Jpeg {
        check(file.exists() && file.length() > 0L) { "Missing export artifact for $pageId" }
        val bytes = file.readBytes()
        check(bytes.size.toLong() == byteSize) { "Export artifact size changed for $pageId" }
        check(sha256(bytes) == sha256) { "Export artifact checksum changed for $pageId" }
        val dimensions = imageBounds(bytes)
        check(dimensions.first == width && dimensions.second == height) {
            "Export artifact dimensions changed for $pageId"
        }
        return Jpeg(bytes)
    }
}

private const val EXPORT_PIPELINE_VERSION = 3

private data class InputCandidate(
    val sourceType: ExportSourceType,
    val file: File,
    val bytes: suspend () -> ByteArray?,
    val needsPerspectiveProcessing: Boolean,
)

/** Materializes pages sequentially so exporters can never reach a camera file. */
@Suppress("DEPRECATION")
suspend fun prepareProcessedExportPages(
    imageRepository: ImageRepository,
    requestedQuality: ExportQuality,
    cacheDir: File,
    requestedFormat: String,
    overlays: Map<String, ExportPageOverlays> = emptyMap(),
): List<ProcessedExportPage> {
    cacheDir.mkdirs()
    require(cacheDir.exists() && cacheDir.isDirectory) { "Invalid export cache: $cacheDir" }
    val effectiveQuality = if (requestedQuality == ExportQuality.MAX_COMPRESSION) {
        ExportQuality.HIGH
    } else {
        requestedQuality
    }
    return imageRepository.pages().map { page ->
        prepareOnePage(
            imageRepository = imageRepository,
            page = page,
            requestedQuality = requestedQuality,
            effectiveQuality = effectiveQuality,
            cacheDir = cacheDir,
            requestedFormat = requestedFormat,
            overlays = overlays[page.id],
        )
    }
}

private suspend fun prepareOnePage(
    imageRepository: ImageRepository,
    page: ScanPage,
    requestedQuality: ExportQuality,
    effectiveQuality: ExportQuality,
    cacheDir: File,
    requestedFormat: String,
    overlays: ExportPageOverlays?,
): ProcessedExportPage {
    val metadata = page.metadata
    val colorMode = page.colorMode
    val storedFile = imageRepository.processedFileForExport(page.key().copy(rotation = Rotation.R0))
    val candidates = buildList {
        if (effectiveQuality == ExportQuality.ORIGINAL || effectiveQuality == ExportQuality.HIGH) {
            add(
                InputCandidate(
                    ExportSourceType.CAMERA_ORIGINAL,
                    imageRepository.originalFile(page.id),
                    {
                        imageRepository.verifiedOriginalBytesForExport(
                            page.id, page.hasOriginal, page.originalRelativePath, page.sourceSha256,
                        )
                    },
                    true
                )
            )
            add(InputCandidate(ExportSourceType.SAFE_COPY, imageRepository.safeJpegFile(page.id), {
                imageRepository.verifiedSafeBytesForExport(
                    page.id, page.hasSafeCopy, page.safeSha256,
                )
            }, true))
            val legacy = imageRepository.legacySourceFileForExport(page.id)
            add(InputCandidate(ExportSourceType.LEGACY_SOURCE, legacy, {
                legacy.takeIf(File::exists)?.readBytes()
            }, true))
        }
        add(InputCandidate(ExportSourceType.STORED_PROCESSED, storedFile, {
            storedFile.takeIf(File::exists)?.readBytes()
        }, false))
    }

    var lastFailure: Throwable? = null
    for (candidate in candidates) {
        val inputBytes = try {
            candidate.bytes()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastFailure = error
            null
        } ?: continue
        if (inputBytes.isEmpty()) continue
        if (candidate.needsPerspectiveProcessing && (metadata == null || colorMode == null)) continue

        val inputDimensions = imageBounds(inputBytes)
        val artifactType = artifactTypeFor(effectiveQuality, candidate.sourceType)
        val cacheKey = exportCacheKey(page, effectiveQuality, candidate, inputBytes, overlays)
        val target = File(cacheDir, "${safeName(page.id)}-$cacheKey.jpg")
        try {
            if (!isValidCachedArtifact(target)) {
                val processed = if (candidate.needsPerspectiveProcessing) {
                    processedImage(
                        source = Jpeg(inputBytes),
                        metadata = requireNotNull(metadata),
                        rotation = page.totalRotation(),
                        colorMode = requireNotNull(colorMode),
                        exportQuality = effectiveQuality,
                    )
                } else {
                    val oriented = rotateStoredProcessed(Jpeg(inputBytes), page.manualRotation)
                    when (effectiveQuality) {
                        ExportQuality.COMPRESSED, ExportQuality.PREVIEW -> resizeJpegBytesForMaxPixels(
                            oriented,
                            effectiveQuality.maxPixels.toDouble(),
                            effectiveQuality.jpegQuality
                        )

                        else -> oriented
                    }
                }
                val finalBytes = bakeOverlays(processed.bytes, overlays, page.id)
                writeExportArtifactAtomically(target, finalBytes)
            }
            val outputBytes = target.readBytes()
            val outputDimensions = imageBounds(outputBytes)
            check(outputDimensions.first > 0 && outputDimensions.second > 0) {
                "Invalid processed export dimensions for ${page.id}"
            }
            val physical = page.estimatedDimensionsForExport()
            val artifact = ProcessedExportPage(
                pageId = page.id,
                file = target,
                sourceType = candidate.sourceType,
                artifactType = artifactType,
                inputAbsolutePath = candidate.file.absolutePath,
                inputByteSize = inputBytes.size.toLong(),
                inputWidth = inputDimensions.first,
                inputHeight = inputDimensions.second,
                width = outputDimensions.first,
                height = outputDimensions.second,
                quad = metadata?.normalizedQuad?.toExportPoints().orEmpty(),
                quadVersion = page.quadVersion,
                rotation = page.totalRotation().degrees,
                colorMode = colorMode,
                requestedQuality = requestedQuality,
                byteSize = target.length(),
                sha256 = sha256(outputBytes),
                physicalWidthMm = (physical as? EstimatedDimensions.Physical)?.widthMm,
                physicalHeightMm = (physical as? EstimatedDimensions.Physical)?.heightMm,
            )
            logPreparedArtifact(artifact, requestedFormat)
            return artifact
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastFailure = error
            Log.w(
                "ExportPrepare",
                "candidateFailed pageId=${page.id} sourceType=${candidate.sourceType} " +
                        "inputPath=${pathForLog(candidate.file.absolutePath)}",
                error,
            )
        }
    }
    throw IOException("No processed export source available for page ${page.id}", lastFailure)
}

@Suppress("DEPRECATION")
private fun artifactTypeFor(
    quality: ExportQuality,
    sourceType: ExportSourceType,
): ProcessedExportArtifactType {
    if (sourceType == ExportSourceType.STORED_PROCESSED &&
        (quality == ExportQuality.ORIGINAL || quality == ExportQuality.HIGH)
    ) return ProcessedExportArtifactType.PROCESSED_BALANCED
    return when (quality) {
        ExportQuality.ORIGINAL -> ProcessedExportArtifactType.PROCESSED_FULL_RES
        ExportQuality.HIGH, ExportQuality.MAX_COMPRESSION -> ProcessedExportArtifactType.PROCESSED_HIGH
        ExportQuality.BALANCED -> ProcessedExportArtifactType.PROCESSED_BALANCED
        ExportQuality.COMPRESSED -> ProcessedExportArtifactType.PROCESSED_COMPRESSED
        ExportQuality.PREVIEW -> ProcessedExportArtifactType.PROCESSED_PREVIEW
    }
}

private fun exportCacheKey(
    page: ScanPage,
    quality: ExportQuality,
    candidate: InputCandidate,
    inputBytes: ByteArray,
    overlays: ExportPageOverlays?,
): String {
    val identity = buildString {
        append("v=").append(EXPORT_PIPELINE_VERSION)
        append("|page=").append(page.id)
        append("|quadVersion=").append(page.quadVersion)
        append("|quad=").append(page.metadata?.normalizedQuad?.toExportPoints().orEmpty())
        append("|rotation=").append(page.totalRotation().degrees)
        append("|color=").append(page.colorMode)
        append("|quality=").append(quality)
        append("|source=").append(candidate.sourceType)
        append("|sourcePath=").append(candidate.file.absolutePath)
        append("|sourceLength=").append(inputBytes.size)
        append("|sourceModified=").append(candidate.file.lastModified())
        append("|sourceDigest=").append(sha256(inputBytes))
        append("|overlays=").append(overlayFingerprint(overlays))
    }
    return sha256(identity.toByteArray()).take(20)
}

private fun bakeOverlays(
    bytes: ByteArray,
    overlays: ExportPageOverlays?,
    pageId: String
): ByteArray {
    if (overlays == null) return bytes
    val base = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IOException("Cannot decode processed page $pageId for overlays")
    try {
        val composed = composeOverlaysOnBitmap(base, overlays) ?: return bytes
        try {
            return ByteArrayOutputStream().use { output ->
                check(composed.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    "Cannot encode overlays for $pageId"
                }
                output.toByteArray()
            }
        } finally {
            if (!composed.isRecycled) composed.recycle()
        }
    } finally {
        if (!base.isRecycled) base.recycle()
    }
}

/** Creates the INE composite from two already-processed artifacts. */
fun mergeIneProcessedPages(
    front: ProcessedExportPage,
    back: ProcessedExportPage,
    cacheDir: File,
    fillFraction: Float,
    requestedFormat: String,
): ProcessedExportPage {
    val key = sha256(
        "ine|v=$EXPORT_PIPELINE_VERSION|${front.sha256}|${back.sha256}|$fillFraction".toByteArray()
    ).take(20)
    val target = File(cacheDir.apply { mkdirs() }, "ine-composite-$key.jpg")
    if (!isValidCachedArtifact(target)) {
        val frontBitmap = front.readJpeg().toBitmap()
        val backBitmap = back.readJpeg().toBitmap()
        try {
            writeExportArtifactAtomically(
                target,
                mergeIneBitmaps(frontBitmap, backBitmap, fillFraction).bytes,
            )
        } finally {
            if (!frontBitmap.isRecycled) frontBitmap.recycle()
            if (!backBitmap.isRecycled) backBitmap.recycle()
        }
    }
    val bytes = target.readBytes()
    val dimensions = imageBounds(bytes)
    return ProcessedExportPage(
        pageId = "ine-composite",
        file = target,
        sourceType = ExportSourceType.COMPOSITE,
        artifactType = ProcessedExportArtifactType.PROCESSED_COMPOSITE,
        inputAbsolutePath = "${front.absolutePath};${back.absolutePath}",
        inputByteSize = front.byteSize + back.byteSize,
        inputWidth = maxOf(front.width, back.width),
        inputHeight = front.height + back.height,
        width = dimensions.first,
        height = dimensions.second,
        quad = emptyList(),
        quadVersion = null,
        rotation = 0,
        colorMode = null,
        requestedQuality = front.requestedQuality,
        byteSize = target.length(),
        sha256 = sha256(bytes),
    ).also { logPreparedArtifact(it, requestedFormat) }
}

internal fun writeExportArtifactAtomically(target: File, bytes: ByteArray) {
    if (bytes.isEmpty()) throw IOException("Refusing to write an empty export artifact")
    target.parentFile?.mkdirs()
    val temp = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
    try {
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.syncBestEffort(target.name)
        }
        if (temp.length() != bytes.size.toLong() || !isValidImage(temp)) {
            throw IOException("Invalid temporary export artifact for ${target.name}")
        }
        publishTempFile(temp, target)
        writeChecksumAtomically(target)
    } finally {
        if (temp.exists()) temp.delete()
    }
}

private fun logPreparedArtifact(page: ProcessedExportPage, requestedFormat: String) {
    Log.i(
        "ExportPrepare",
        "prepared pageId=${page.pageId} requestedFormat=$requestedFormat " +
                "requestedQuality=${page.requestedQuality} sourceType=${page.sourceType} " +
                "artifactType=${page.artifactType} inputPath=${pathForLog(page.inputAbsolutePath)} " +
                "outputPath=${pathForLog(page.absolutePath)} inputByteSize=${page.inputByteSize} " +
                "outputByteSize=${page.byteSize} inputWidth=${page.inputWidth} " +
                "inputHeight=${page.inputHeight} outputWidth=${page.width} outputHeight=${page.height} " +
                "quadVersion=${page.quadVersion} quadCoordinates=${page.quad} rotation=${page.rotation} " +
                "colorMode=${page.colorMode} sha256=${page.sha256}",
    )
}

private fun isValidImage(file: File): Boolean =
    file.exists() && file.length() > 0L && imageBounds(file.readBytes()).let { it.first > 0 && it.second > 0 }

private fun isValidCachedArtifact(file: File): Boolean {
    if (!isValidImage(file)) return false
    val checksumFile = checksumFile(file)
    val expected = runCatching { checksumFile.readText().trim() }.getOrNull() ?: return false
    return expected.length == 64 && runCatching { sha256(file.readBytes()) == expected }.getOrDefault(
        false
    )
}

private fun writeChecksumAtomically(target: File) {
    val checksumTarget = checksumFile(target)
    val bytes = sha256(target.readBytes()).toByteArray(Charsets.US_ASCII)
    val temp = File.createTempFile(".${checksumTarget.name}.", ".tmp", checksumTarget.parentFile)
    try {
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.syncBestEffort(checksumTarget.name)
        }
        publishTempFile(temp, checksumTarget)
    } finally {
        if (temp.exists()) temp.delete()
    }
}

private fun checksumFile(file: File): File = File(file.parentFile, "${file.name}.sha256")

private fun imageBounds(bytes: ByteArray): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return options.outWidth to options.outHeight
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

private fun Quad.toExportPoints(): List<ExportQuadPoint> = listOf(
    ExportQuadPoint(topLeft.x, topLeft.y),
    ExportQuadPoint(topRight.x, topRight.y),
    ExportQuadPoint(bottomRight.x, bottomRight.y),
    ExportQuadPoint(bottomLeft.x, bottomLeft.y),
)

private fun overlayFingerprint(overlays: ExportPageOverlays?): String {
    if (overlays == null) return "none"
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(overlays.copy(signatureBitmap = null).toString().toByteArray(Charsets.UTF_8))
    overlays.signatureBitmap?.let { bitmap ->
        digest.update(bitmap.width.toString().toByteArray())
        digest.update(bitmap.height.toString().toByteArray())
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels.forEach { pixel ->
            digest.update((pixel ushr 24).toByte())
            digest.update((pixel ushr 16).toByte())
            digest.update((pixel ushr 8).toByte())
            digest.update(pixel.toByte())
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun pathForLog(path: String): String = if (BuildConfig.DEBUG) {
    path
} else {
    "sha256:${sha256(path.toByteArray(Charsets.UTF_8)).take(16)}"
}

private fun ScanPage.estimatedDimensionsForExport(): EstimatedDimensions? {
    val meta = metadata ?: return null
    val size = meta.sourceSize ?: return null
    val scaledQuad = meta.normalizedQuad.scaledTo(1.0, 1.0, size.width, size.height)
    val dimensions = estimateRealDimensions(
        scaledQuad, size.width.toInt(), size.height.toInt(), meta.opticalMeasures
    ).snapToStandardFormat()
    return if ((totalRotation() == Rotation.R90 || totalRotation() == Rotation.R270) &&
        dimensions is EstimatedDimensions.Physical
    ) {
        EstimatedDimensions.Physical(dimensions.heightMm, dimensions.widthMm)
    } else dimensions
}

/** Stacks two already-final page images on one white INE sheet. */
fun mergeIneBitmaps(front: Bitmap, back: Bitmap, fillFraction: Float = 0.5f): Jpeg {
    val contentW = maxOf(front.width, back.width)
    val gap = (contentW * 0.06f).toInt().coerceAtLeast(16)
    val contentH = front.height + back.height + gap
    val shortSide = minOf(contentW, contentH).toFloat()
    val scale = if (fillFraction > 0f) shortSide / fillFraction else shortSide
    val outW = maxOf(contentW.toFloat(), scale).toInt().coerceAtLeast(1)
    val outH = maxOf(contentH.toFloat(), scale).toInt().coerceAtLeast(1)
    val composite = createBitmap(outW, outH)
    return try {
        val canvas = Canvas(composite)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(front, (outW - front.width) / 2f, (outH - contentH) / 2f, null)
        canvas.drawBitmap(
            back,
            (outW - back.width) / 2f,
            (outH - contentH) / 2f + front.height + gap,
            null,
        )
        ByteArrayOutputStream().use { output ->
            check(composite.compress(Bitmap.CompressFormat.JPEG, 95, output))
            Jpeg(output.toByteArray())
        }
    } finally {
        composite.recycle()
    }
}

private fun resizeJpegBytesForMaxPixels(
    jpeg: Jpeg,
    maxPixels: Double,
    jpegQuality: Int,
): Jpeg {
    var decoded: Mat? = null
    var resized: Mat? = null
    try {
        decoded = jpeg.toMat(MAX_FULL_RES_EXPORT_PIXELS)
        resized = resizeForMaxPixels(decoded, maxPixels)
        return Jpeg.fromMat(resized, jpegQuality)
    } finally {
        decoded?.release()
        resized?.release()
    }
}

private fun rotateStoredProcessed(jpeg: Jpeg, rotation: Rotation): Jpeg {
    if (rotation == Rotation.R0) return jpeg
    var decoded: Mat? = null
    var rotated: Mat? = null
    try {
        decoded = jpeg.toMat(MAX_FULL_RES_EXPORT_PIXELS)
        rotated = rotate(decoded, rotation.degrees)
        return Jpeg.fromMat(rotated, ExportQuality.ORIGINAL.jpegQuality)
    } finally {
        decoded?.release()
        rotated?.release()
    }
}

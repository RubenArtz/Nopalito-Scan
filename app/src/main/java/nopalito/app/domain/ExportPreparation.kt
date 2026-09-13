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

@file:Suppress("KotlinConstantConditions")

package nopalito.app.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import nopalito.app.data.ImageRepository
import nopalito.app.platform.processedImage
import nopalito.imageprocessing.EstimatedDimensions
import nopalito.imageprocessing.estimateRealDimensions
import nopalito.imageprocessing.resizeForMaxPixels
import nopalito.imageprocessing.scaledTo
import org.opencv.core.Mat
import java.io.ByteArrayOutputStream

fun interface JpegProvider {
    suspend fun get(): Jpeg
}

data class PageToExport(
    val page: ScanPage,
    val overlays: PageExportOverlays? = null,
    val jpeg: JpegProvider,
    val origin: ExportOrigin = ExportOrigin.PROCESSED_STORED,
    val originCause: String? = null,
) {
    data class PageExportOverlays(
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

    fun estimatedDimensions(): EstimatedDimensions? {
        val metadata = page.metadata ?: return null
        val size = metadata.sourceSize ?: return null

        val quad = metadata.normalizedQuad.scaledTo(1.0, 1.0, size.width, size.height)
        val realDimensions = estimateRealDimensions(
            quad, size.width.toInt(), size.height.toInt(), metadata.opticalMeasures
        ).snapToStandardFormat()
        return realDimensions.applyRotation(page.totalRotation())
    }
}

private fun EstimatedDimensions.applyRotation(rotation: Rotation): EstimatedDimensions {
    if ((rotation == Rotation.R90 || rotation == Rotation.R270)
        && this is EstimatedDimensions.Physical
    ) {
        return EstimatedDimensions.Physical(heightMm, widthMm)
    }
    return this
}

/**
 * Where export bytes came from. ORIGINAL_FILE means the preserved capture
 * was copied without decode/encode; anything else documents why a decode
 * was required (rotation, overlays, OCR bitmap, filter, missing original).
 */
enum class ExportOrigin {
    ORIGINAL_FILE,
    REPROCESSED_HIGH,
    PROCESSED_STORED,
    FALLBACK_NO_ORIGINAL,
}

@Suppress("DEPRECATION")
suspend fun pagesToExport(
    imageRepository: ImageRepository,
    exportQuality: ExportQuality
): List<PageToExport> {

    val pages = imageRepository.pages()
    // MAX_COMPRESSION is a legacy alias of HIGH, never a 0.5 MP render.
    val effective =
        if (exportQuality == ExportQuality.MAX_COMPRESSION) ExportQuality.HIGH else exportQuality
    return when (effective) {
        ExportQuality.ORIGINAL -> pages.map { page ->
            val original = if (imageRepository.hasOriginal(page.id)) {
                imageRepository.originalBytes(page.id)
            } else {
                null
            }
            if (original != null) {
                if (page.totalRotation() != Rotation.R0) {
                    android.util.Log.w(
                        "Export",
                        "ORIGINAL for ${page.id} requires decode: rotation=${page.totalRotation()}",
                    )
                    PageToExport(
                        page = page,
                        origin = ExportOrigin.ORIGINAL_FILE,
                        originCause = "rotation-decode",
                        jpeg = JpegProvider {
                            rotateOriginalForExport(
                                original,
                                page.totalRotation()
                            )
                        },
                    )
                } else {
                    PageToExport(
                        page = page,
                        origin = ExportOrigin.ORIGINAL_FILE,
                        jpeg = JpegProvider { Jpeg(original) },
                    )
                }
            } else {
                android.util.Log.w(
                    "Export",
                    "ORIGINAL requested without SourceOriginal for ${page.id}: falling back to stored processed",
                )
                PageToExport(
                    page = page,
                    origin = ExportOrigin.FALLBACK_NO_ORIGINAL,
                    originCause = "missing-original",
                    jpeg = JpegProvider { jpeg(page, imageRepository) },
                )
            }
        }

        ExportQuality.HIGH -> pages.map { page ->
            PageToExport(
                page = page,
                origin = ExportOrigin.REPROCESSED_HIGH,
                jpeg = JpegProvider { highReprocess(imageRepository, page, effective) },
            )
        }

        ExportQuality.BALANCED -> pages.map {
            PageToExport(
                page = it,
                origin = ExportOrigin.PROCESSED_STORED,
                jpeg = JpegProvider { jpeg(it, imageRepository) },
            )
        }

        ExportQuality.COMPRESSED -> pages.map { page ->
            PageToExport(
                page = page,
                origin = ExportOrigin.PROCESSED_STORED,
                jpeg = JpegProvider {
                    resizeJpegBytesForMaxPixels(
                        jpeg = jpeg(page, imageRepository),
                        maxPixels = ExportQuality.COMPRESSED.maxPixels.toDouble(),
                        jpegQuality = ExportQuality.COMPRESSED.jpegQuality,
                    )
                },
            )
        }

        ExportQuality.PREVIEW -> pages.map { page ->
            PageToExport(
                page = page,
                origin = ExportOrigin.PROCESSED_STORED,
                jpeg = JpegProvider {
                    resizeJpegBytesForMaxPixels(
                        jpeg = jpeg(page, imageRepository),
                        maxPixels = ExportQuality.PREVIEW.maxPixels.toDouble(),
                        jpegQuality = ExportQuality.PREVIEW.jpegQuality,
                    )
                },
            )
        }

        ExportQuality.MAX_COMPRESSION -> pages.map { page ->
            PageToExport(
                page = page,
                origin = ExportOrigin.REPROCESSED_HIGH,
                originCause = "legacy-max-alias-high",
                jpeg = JpegProvider { highReprocess(imageRepository, page, ExportQuality.HIGH) },
            )
        }
    }
}

private suspend fun highReprocess(
    imageRepository: ImageRepository,
    page: ScanPage,
    quality: ExportQuality,
): Jpeg {
    // Priority: SourceOriginal > SourceSafe > stored processed (legacy).
    imageRepository.originalBytes(page.id)?.let { bytes ->
        val metadata = page.metadata
        val colorMode = page.colorMode
        if (metadata != null && colorMode != null) {
            return try {
                // Single full-res decode inside processedImage; working
                // segmentation already happened at capture, so no second
                // full-res bitmap is held here.
                processedImage(Jpeg(bytes), metadata, page.totalRotation(), colorMode, quality)
            } catch (e: Exception) {
                android.util.Log.w(
                    "Export",
                    "HIGH from original failed for ${page.id}, trying safe",
                    e
                )
                highFromSafeOrStored(imageRepository, page, quality)
            }
        }
    }
    return highFromSafeOrStored(imageRepository, page, quality)
}

private suspend fun highFromSafeOrStored(
    imageRepository: ImageRepository,
    page: ScanPage,
    quality: ExportQuality,
): Jpeg {
    imageRepository.safeBytes(page.id)?.let { bytes ->
        val metadata = page.metadata
        val colorMode = page.colorMode
        if (metadata != null && colorMode != null) {
            return runCatching {
                processedImage(Jpeg(bytes), metadata, page.totalRotation(), colorMode, quality)
            }.getOrElse { jpeg(page, imageRepository) }
        }
    }
    android.util.Log.w(
        "Export",
        "HIGH without original/safe for ${page.id}: using stored processed"
    )
    return jpeg(page, imageRepository)
}

private fun rotateOriginalForExport(original: ByteArray, rotation: Rotation): Jpeg {
    if (rotation == Rotation.R0) return Jpeg(original)
    val opts = android.graphics.BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(original, 0, original.size, opts)
        ?: return Jpeg(original)
    try {
        val matrix = android.graphics.Matrix().apply { postRotate(rotation.degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
        )
        try {
            val out = java.io.ByteArrayOutputStream()
            // q95: rotation only, no enhancement change.
            rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            return Jpeg(out.toByteArray())
        } finally {
            if (rotated !== bitmap && !rotated.isRecycled) rotated.recycle()
        }
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

private suspend fun jpeg(page: ScanPage, imageRepository: ImageRepository): Jpeg {
    val key = page.key()
    return imageRepository.jpegBytes(key)
        ?: throw IllegalArgumentException("JPEG not found for $key")
}

/**
 * Stacks the front and back INE captures onto a single export page: front on top,
 * back below, on one clean white sheet. Each [Bitmap] must already have its overlays
 * (signature/date) and color/rotation applied. Used so an INE credential exports as
 * one unified document instead of two separate pages.
 *
 * [fillFraction] is the portion of the output's shorter side that the content occupies
 * (0..1). Higher values make the credential fill the sheet (e.g. the "INE at 200%"
 * legal copy), lower values leave a generous white margin around it.
 */
fun mergeIneBitmaps(front: Bitmap, back: Bitmap, fillFraction: Float = 0.5f): Jpeg {
    val contentW = maxOf(front.width, back.width)
    // Small vertical gap so the two faces don't touch, while still forming one sheet.
    val gap = (contentW * 0.06f).toInt().coerceAtLeast(16)
    val contentH = front.height + back.height + gap
    // The content fills `fillFraction` of the shorter side; the rest is white margin.
    val shortSide = minOf(contentW, contentH).toFloat()
    val scale = if (fillFraction > 0f) shortSide / fillFraction else shortSide
    val outW = maxOf(contentW.toFloat(), scale).toInt().coerceAtLeast(1)
    val outH = maxOf(contentH.toFloat(), scale).toInt().coerceAtLeast(1)
    val marginX = (outW - contentW) / 2f
    val marginY = (outH - contentH) / 2f
    val composite = createBitmap(outW, outH)
    val canvas = Canvas(composite)
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.drawBitmap(front, marginX + (contentW - front.width) / 2f, marginY, null)
    canvas.drawBitmap(
        back,
        marginX + (contentW - back.width) / 2f,
        marginY + front.height + gap,
        null,
    )
    val bos = ByteArrayOutputStream()
    composite.compress(Bitmap.CompressFormat.JPEG, 90, bos)
    return Jpeg(bos.toByteArray())
}

private fun resizeJpegBytesForMaxPixels(
    jpeg: Jpeg,
    maxPixels: Double,
    jpegQuality: Int
): Jpeg {
    var decoded: Mat? = null
    var resized: Mat? = null
    try {
        decoded = jpeg.toMat()
        resized = resizeForMaxPixels(decoded, maxPixels)
        return Jpeg.fromMat(resized, jpegQuality)
    } finally {
        decoded?.release()
        resized?.release()
    }
}
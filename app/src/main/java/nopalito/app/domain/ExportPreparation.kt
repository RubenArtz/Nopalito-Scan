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

suspend fun pagesToExport(
    imageRepository: ImageRepository,
    exportQuality: ExportQuality
): List<PageToExport> {

    val pages = imageRepository.pages()
    return when (exportQuality) {
        ExportQuality.ORIGINAL -> pages.map {
            PageToExport(it) { jpeg(it, imageRepository) }
        }

        ExportQuality.HIGH -> pages.map { page ->
            PageToExport(page) {
                val source = imageRepository.source(page.id)
                val metadata = page.metadata
                val colorMode = page.colorMode
                if (source != null && metadata != null && colorMode != null) {
                    val rotation = page.totalRotation()
                    processedImage(source, metadata, rotation, colorMode, exportQuality)
                } else
                    jpeg(page, imageRepository)
            }
        }

        ExportQuality.BALANCED, ExportQuality.COMPRESSED, ExportQuality.MAX_COMPRESSION -> pages.map { page ->
            PageToExport(page) {
                resizeJpegBytesForMaxPixels(
                    jpeg = jpeg(page, imageRepository),
                    maxPixels = exportQuality.maxPixels.toDouble(),
                    jpegQuality = exportQuality.jpegQuality
                )
            }
        }
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
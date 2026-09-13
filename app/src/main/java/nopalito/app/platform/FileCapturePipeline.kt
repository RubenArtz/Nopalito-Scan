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

package nopalito.app.platform

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import nopalito.app.domain.CaptureTier
import nopalito.app.platform.FileCapturePipeline.decodeBounds
import java.io.File

/**
 * Helpers to read a CameraX file capture without loading full-res pixels
 * more than once (Phase 1).
 *
 * The preserved original is never decoded at full resolution for detection:
 * [decodeBounds] gives real dimensions for metadata and [decodeSampled]
 * produces the ~1280 px working copy for segmentation. Full-res decode
 * happens exactly once inside the current `extractDocument` path.
 */
object FileCapturePipeline {
    data class Bounds(val width: Int, val height: Int)

    data class ExifInfo(
        val orientation: Int,
        val width: Int,
        val height: Int,
    )

    fun decodeBounds(file: File): Bounds? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return Bounds(opts.outWidth, opts.outHeight)
    }

    fun readExif(file: File, fallback: Bounds?): ExifInfo {
        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        ) ?: ExifInterface.ORIENTATION_NORMAL
        val w = exif?.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
            ?.takeIf { it > 0 } ?: fallback?.width ?: 0
        val h = exif?.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
            ?.takeIf { it > 0 } ?: fallback?.height ?: 0
        return ExifInfo(orientation, w, h)
    }

    fun sampleSizeForLongSide(bounds: Bounds, longSide: Int): Int {
        var sample = 1
        var longest = maxOf(bounds.width, bounds.height)
        while (longest / (sample * 2) >= longSide && sample < 16) sample *= 2
        return sample
    }

    fun tierForFlags(requested: CaptureTier, highQualityCapture: Boolean): CaptureTier =
        if (!highQualityCapture && (requested == CaptureTier.HIGH)) CaptureTier.BALANCED else requested

    data class WorkingQuality(
        val laplacianVar: Double,
        val meanLuma: Double,
        val saturatedPct: Double,
    )

    /**
     * Measurement only (no visual change): sharpness as variance-of-Laplacian
     * reusing [nopalito.imageprocessing.estimateBlurVariance], mean luminance
     * and near-white saturation % on a working-size bitmap. All Mats released.
     * Returns null when OpenCV is unavailable.
     */
    fun measureWorkingQuality(bmp: android.graphics.Bitmap): WorkingQuality? {
        return runCatching {
            val rgba = org.opencv.core.Mat()
            org.opencv.android.Utils.bitmapToMat(bmp, rgba)
            val gray = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(
                rgba, gray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY
            )
            rgba.release()
            try {
                val lap = nopalito.imageprocessing.estimateBlurVariance(gray)
                val luma = org.opencv.core.Core.mean(gray).`val`[0]
                val white = org.opencv.core.Mat()
                org.opencv.imgproc.Imgproc.threshold(
                    gray, white, 250.0, 255.0, org.opencv.imgproc.Imgproc.THRESH_BINARY
                )
                val total = gray.rows() * gray.cols()
                val sat = if (total > 0) {
                    org.opencv.core.Core.countNonZero(white) * 100.0 / total
                } else {
                    0.0
                }
                white.release()
                WorkingQuality(lap, luma, sat)
            } finally {
                gray.release()
            }
        }.getOrNull()
    }
}

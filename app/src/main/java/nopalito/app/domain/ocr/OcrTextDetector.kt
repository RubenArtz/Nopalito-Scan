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

package nopalito.app.domain.ocr

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.googlecode.tesseract.android.TessBaseAPI
import nopalito.app.domain.OcrService
import nopalito.imageprocessing.OcrTextBox
import nopalito.imageprocessing.enhanceBwImage
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Text detection: locates words in a page bitmap and returns their bounding
 * boxes. This is a thin, testable seam over [OcrService] so callers never
 * touch the Tesseract engine directly.
 *
 * The bitmap is binarized (grayscale + adaptive threshold) before recognition:
 * camera photos carry shadows, uneven lighting and colored annotations that
 * Tesseract otherwise reads as garbage. The box coordinates returned by the
 * engine are scaled back to the source bitmap size, so binarization (which
 * preserves dimensions) keeps them valid.
 */
class OcrTextDetector(
    private val ocrService: OcrService,
) {
    /**
     * Detects words in [bitmap]. Returns an empty list when the engine is not
     * initialized or no words pass the confidence threshold.
     *
     * @param pageSegMode Tesseract segmentation mode. Defaults to
     * PSM_SINGLE_BLOCK: scanned pages are uniform text blocks, and the fully
     * automatic mode hallucinates text lines inside logos and graphics.
     */
    suspend fun detect(
        bitmap: Bitmap,
        pageSegMode: Int = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
    ): List<OcrTextBox> {
        val prepared = runCatching { binarize(bitmap) }.getOrNull() ?: bitmap
        try {
            return ocrService.runOcr(prepared, pageSegMode)
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    /**
     * Binarizes [source] into a clean black-on-white bitmap: drops shadows,
     * paper texture and light colored marks (handwritten annotations, stamps
     * seals that survive as faint color) while keeping dark print intact.
     * Returns a new bitmap; the source is never modified.
     */
    private fun binarize(source: Bitmap): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(source, rgba)
        val bgr = Mat()
        val out = Mat()
        try {
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            val thresholded = enhanceBwImage(bgr)
            try {
                // matToBitmap reliably supports 4-channel mats; convert back.
                Imgproc.cvtColor(thresholded, out, Imgproc.COLOR_BGR2RGBA)
            } finally {
                thresholded.release()
            }
            val result = createBitmap(out.cols(), out.rows())
            Utils.matToBitmap(out, result)
            return result
        } finally {
            rgba.release()
            bgr.release()
            out.release()
        }
    }
}
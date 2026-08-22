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

package nopalito.imageprocessing

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.opencv.core.Point as CvPoint

/**
 * Exercises [correctBlur] / [estimateBlurVariance] with synthetic frames
 * using the real OpenCV native library (org.openpnp desktop build).
 */
class BlurCorrectionTest {

    companion object {
        init {
            runCatching { nu.pattern.OpenCV.loadShared() }
        }
    }

    @BeforeTest
    fun checkNatives() {
        Mat().release()
    }

    /** Text-like document: white page with thin black strokes. */
    private fun sharpDocument(): Mat {
        val frame = Mat(300, 400, CvType.CV_8UC3, Scalar(235.0, 235.0, 235.0))
        val ink = Scalar(30.0, 30.0, 30.0)
        // Thin horizontal bars resembling text lines.
        for (row in 0 until 10) {
            val y = (40 + row * 24).toDouble()
            var x = 50.0
            while (x < 340.0) {
                Imgproc.rectangle(
                    frame,
                    CvPoint(x, y),
                    CvPoint(x + 14.0, y + 5.0),
                    ink,
                    -1,
                )
                x += 26.0
            }
        }
        return frame
    }

    @Test
    fun `a blurred document scores lower than a sharp one`() {
        val sharp = sharpDocument()
        val blurred = Mat()
        Imgproc.GaussianBlur(sharp, blurred, Size(0.0, 0.0), 6.0)

        val sharpScore = estimateBlurVariance(sharp)
        val blurredScore = estimateBlurVariance(blurred)

        assertTrue(sharpScore > blurredScore * 4, "sharp=$sharpScore blurred=$blurredScore")
        assertTrue(blurredScore < DEFAULT_BLUR_VARIANCE_THRESHOLD)
        assertTrue(sharpScore >= DEFAULT_BLUR_VARIANCE_THRESHOLD)
    }

    @Test
    fun `a sharp document is returned untouched`() {
        val sharp = sharpDocument()
        val result = correctBlur(sharp)
        assertTrue(result === sharp, "sharp input must return the same instance (no cost)")
    }

    @Test
    fun `a blurry document gets sharpened`() {
        val blurred = Mat()
        Imgproc.GaussianBlur(sharpDocument(), blurred, Size(0.0, 0.0), 6.0)

        val corrected = correctBlur(blurred)
        assertNotEquals(blurred, corrected, "blurry input must produce a new sharpened mat")
        val before = estimateBlurVariance(blurred)
        val after = estimateBlurVariance(corrected)
        assertTrue(after > before * 1.5, "expected sharpening: $before -> $after")
    }
}

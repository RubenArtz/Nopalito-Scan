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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.opencv.core.Point as CvPoint

/**
 * Exercises [PartialShapeDetector] against synthetic frames using the real
 * OpenCV native library (org.openpnp desktop build).
 */
class PartialShapeDetectorTest {

    companion object {
        init {
            runCatching { nu.pattern.OpenCV.loadShared() }
        }
    }

    private val detector = PartialShapeDetector()

    @BeforeTest
    fun checkNatives() {
        // Fail fast if the native library is unavailable in this environment.
        Mat().release()
    }

    private fun blankFrame(): Mat = Mat(240, 320, CvType.CV_8UC1, Scalar(40.0))

    private fun finish(frame: Mat): Mat {
        Imgproc.GaussianBlur(frame, frame, Size(3.0, 3.0), 0.0)
        return frame
    }

    @Test
    fun `a fully visible document yields the complete shape`() {
        val frame = finish(blankFrame()).apply {
            Imgproc.rectangle(
                this,
                CvPoint(60.0, 60.0),
                CvPoint(260.0, 180.0),
                Scalar(220.0),
                -1,
            )
        }
        val expectedCorners = listOf(
            Point(60.0, 60.0),
            Point(260.0, 60.0),
            Point(260.0, 180.0),
            Point(60.0, 180.0),
        )

        val result = assertNotNull(detector.detect(frame))
        assertTrue(result.isComplete, "a full rectangle must be reported as complete")
        assertEquals(4, result.corners.size)
        assertTrue(result.openArms.isEmpty())
        for (corner in result.corners) {
            val nearest = expectedCorners.minBy { norm(it, corner) }
            assertTrue(norm(nearest, corner) < 10.0, "corner $corner too far from $nearest")
        }
    }

    @Test
    fun `a single visible corner is reported progressively`() {
        // A sheet whose top-left part is outside the frame: only its bottom-
        // right corner (and the two edges leading to it) are visible.
        val frame = finish(blankFrame()).apply {
            Imgproc.rectangle(
                this,
                CvPoint(-100.0, -100.0),
                CvPoint(149.0, 149.0),
                Scalar(220.0),
                -1,
            )
        }

        val result = assertNotNull(detector.detect(frame), "an L-corner should be detected")
        assertFalse(result.isComplete)
        assertEquals(1, result.corners.size)
        assertTrue(norm(result.corners[0], Point(149.0, 149.0)) < 12.0)
        // The isolated corner still shows its two arms so the overlay can
        // draw the L.
        assertEquals(2, result.openArms.size)
    }

    @Test
    fun `a blank scene reports nothing`() {
        assertNull(detector.detect(finish(blankFrame())))
    }
}
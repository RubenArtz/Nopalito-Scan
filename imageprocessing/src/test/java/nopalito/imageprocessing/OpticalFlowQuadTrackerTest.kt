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
import kotlin.test.*
import org.opencv.core.Point as CvPoint

/**
 * Exercises [OpticalFlowQuadTracker] with synthetic grayscale frames: a
 * textured document rectangle that moves between frames. Runs Lucas-Kanade
 * against the real OpenCV native library (org.openpnp desktop build).
 */
class OpticalFlowQuadTrackerTest {

    companion object {
        init {
            runCatching { nu.pattern.OpenCV.loadShared() }
        }
    }

    private val frameWidth = 320.0
    private val frameHeight = 240.0

    private val documentQuad = Quad(
        Point(60.0, 40.0),
        Point(260.0, 40.0),
        Point(260.0, 200.0),
        Point(60.0, 200.0),
    )

    @BeforeTest
    fun checkNatives() {
        // Fail fast if the native library is unavailable in this environment.
        Mat().release()
    }

    /** 320x240 grayscale frame with a textured rectangle at (60,40)+(dx,dy). */
    private fun frameWithDocument(offsetX: Int, offsetY: Int): Mat {
        val frame = Mat(240, 320, CvType.CV_8UC1, Scalar(60.0))
        val x0 = 60 + offsetX
        val y0 = 40 + offsetY
        Imgproc.rectangle(
            frame,
            CvPoint(x0.toDouble(), y0.toDouble()),
            CvPoint(x0 + 200.0, y0 + 160.0),
            Scalar(200.0),
            -1,
        )
        // Checkerboard texture inside the document so the tracker has gradients
        // (real documents have text and content).
        for (cellY in 0 until 8) {
            for (cellX in 0 until 10) {
                if ((cellX + cellY) % 2 == 0) {
                    Imgproc.rectangle(
                        frame,
                        CvPoint(x0 + cellX * 20.0, y0 + cellY * 20.0),
                        CvPoint(x0 + cellX * 20.0 + 20.0, y0 + cellY * 20.0 + 20.0),
                        Scalar(150.0),
                        -1,
                    )
                }
            }
        }
        Imgproc.GaussianBlur(frame, frame, Size(5.0, 5.0), 0.0)
        return frame
    }

    private fun emptyFrame(): Mat = Mat(240, 320, CvType.CV_8UC1, Scalar(60.0))

    private fun shifted(quad: Quad, dx: Double, dy: Double) = Quad(
        Point(quad.topLeft.x + dx, quad.topLeft.y + dy),
        Point(quad.topRight.x + dx, quad.topRight.y + dy),
        Point(quad.bottomRight.x + dx, quad.bottomRight.y + dy),
        Point(quad.bottomLeft.x + dx, quad.bottomLeft.y + dy),
    )

    private fun assertPointClose(expected: Point, actual: Point, tolerance: Double) {
        val d = norm(expected, actual)
        assertTrue(d <= tolerance, "expected $expected ± $tolerance, got $actual (dist $d)")
    }

    @Test
    fun `starts by requesting a full detection`() {
        val tracker = OpticalFlowQuadTracker()
        assertTrue(tracker.shouldRunFullDetection())
    }

    @Test
    fun `full detection anchors the tracker`() {
        val tracker = OpticalFlowQuadTracker()
        val frame = frameWithDocument(0, 0)

        val update = tracker.update(frame, true, documentQuad, frameWidth, frameHeight)

        assertEquals(TrackMode.FULL_DETECTION, update.mode)
        assertEquals(documentQuad, update.quadInMask)
        assertEquals(4, update.validCornerCount)
        assertFalse(tracker.shouldRunFullDetection())
    }

    @Test
    fun `optical flow follows a slowly moving document`() {
        val tracker = OpticalFlowQuadTracker()
        tracker.update(frameWithDocument(0, 0), true, documentQuad, frameWidth, frameHeight)

        val update = tracker.update(
            frameWithDocument(3, 2),
            false,
            null,
            frameWidth,
            frameHeight,
        )

        assertEquals(TrackMode.OPTICAL_FLOW, update.mode)
        assertEquals(4, update.validCornerCount)
        assertNull(update.fullDetectionReason)
        val tracked = assertNotNull(update.quadInMask)
        val expected = shifted(documentQuad, 3.0, 2.0)
        assertPointClose(expected.topLeft, tracked.topLeft, 5.0)
        assertPointClose(expected.topRight, tracked.topRight, 5.0)
        assertPointClose(expected.bottomRight, tracked.bottomRight, 5.0)
        assertPointClose(expected.bottomLeft, tracked.bottomLeft, 5.0)
    }

    @Test
    fun `tracking keeps working across consecutive frames`() {
        val tracker = OpticalFlowQuadTracker(fullDetectionEveryFrames = 3)
        tracker.update(frameWithDocument(0, 0), true, documentQuad, frameWidth, frameHeight)

        for (i in 1..3) {
            val update = tracker.update(
                frameWithDocument(i * 2, i),
                false,
                null,
                frameWidth,
                frameHeight,
            )
            assertEquals(TrackMode.OPTICAL_FLOW, update.mode, "frame $i")
        }
        // After everyFrames tracking updates, a periodic refresh is due.
        assertTrue(tracker.shouldRunFullDetection())
    }

    @Test
    fun `tracking loss holds the last quad and requests a full detection`() {
        val tracker = OpticalFlowQuadTracker()
        tracker.update(frameWithDocument(0, 0), true, documentQuad, frameWidth, frameHeight)

        val update = tracker.update(emptyFrame(), false, null, frameWidth, frameHeight)

        assertEquals(FullDetectionReason.TRACK_LOST, update.fullDetectionReason)
        assertNotNull(update.quadInMask, "last valid quad must be held to avoid flicker")
        assertTrue(tracker.shouldRunFullDetection())
    }

    @Test
    fun `a full detection without a document clears the state`() {
        val tracker = OpticalFlowQuadTracker()
        tracker.update(frameWithDocument(0, 0), true, documentQuad, frameWidth, frameHeight)

        val update = tracker.update(emptyFrame(), true, null, frameWidth, frameHeight)

        assertEquals(TrackMode.FULL_DETECTION, update.mode)
        assertNull(update.quadInMask)
        assertEquals(FullDetectionReason.TRACK_LOST, update.fullDetectionReason)
        assertTrue(tracker.shouldRunFullDetection())
    }

    @Test
    fun `recovers after loss with a new full detection`() {
        val tracker = OpticalFlowQuadTracker()
        tracker.update(frameWithDocument(0, 0), true, documentQuad, frameWidth, frameHeight)
        tracker.update(emptyFrame(), false, null, frameWidth, frameHeight)

        val moved = shifted(documentQuad, 50.0, 30.0)
        val update = tracker.update(
            frameWithDocument(50, 30),
            true,
            moved,
            frameWidth,
            frameHeight,
        )

        assertEquals(TrackMode.FULL_DETECTION, update.mode)
        assertEquals(moved, update.quadInMask)
        assertFalse(tracker.shouldRunFullDetection())
    }

    @Test
    fun `an invalid detected quad is rejected`() {
        val tracker = OpticalFlowQuadTracker()
        val bowtie = Quad(
            Point(60.0, 40.0),
            Point(260.0, 200.0),
            Point(260.0, 40.0),
            Point(60.0, 200.0),
        )

        val update = tracker.update(frameWithDocument(0, 0), true, bowtie, frameWidth, frameHeight)

        assertEquals(FullDetectionReason.INVALID_QUAD, update.fullDetectionReason)
        assertNull(update.quadInMask)
        assertTrue(tracker.shouldRunFullDetection())
    }

    @Test
    fun `a periodic refresh publishes the previous quad when the detection is close`() {
        val tracker = OpticalFlowQuadTracker(fullDetectionEveryFrames = 3)
        tracker.update(frameWithDocument(0, 0), true, documentQuad, frameWidth, frameHeight)

        // Two tracking frames move the corners slightly.
        var lastTracked: Quad? = null
        for (i in 1..2) {
            val update = tracker.update(
                frameWithDocument(i * 2, i),
                false,
                null,
                frameWidth,
                frameHeight,
            )
            lastTracked = update.quadInMask
        }

        // A full detection that only refines the quad slightly must publish
        // the previous quad (no jitter for the auto-capture stability).
        val refined = shifted(documentQuad, 6.0, 3.0)
        val update = tracker.update(
            frameWithDocument(6, 3),
            true,
            refined,
            frameWidth,
            frameHeight,
        )
        assertEquals(lastTracked, update.quadInMask)

        // A detection that moved significantly must be adopted.
        val moved = shifted(documentQuad, 60.0, 40.0)
        val update2 = tracker.update(
            frameWithDocument(60, 40),
            true,
            moved,
            frameWidth,
            frameHeight,
        )
        assertEquals(moved, update2.quadInMask)
    }

    @Test
    fun `reset clears all tracking state`() {
        val tracker = OpticalFlowQuadTracker()
        tracker.update(frameWithDocument(0, 0), true, documentQuad, frameWidth, frameHeight)
        assertFalse(tracker.shouldRunFullDetection())

        tracker.reset()

        assertTrue(tracker.shouldRunFullDetection())
        val update = tracker.update(frameWithDocument(0, 0), false, null, frameWidth, frameHeight)
        assertEquals(FullDetectionReason.INITIAL, update.fullDetectionReason)
        assertNull(update.quadInMask)
    }
}

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

import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.video.Video

/** How the quad of the current frame was obtained. */
enum class TrackMode {
    /** Full pipeline: segmentation + contour/quad detection. */
    FULL_DETECTION,

    /** Cheap corner tracking of the previously detected quad (Lucas-Kanade). */
    OPTICAL_FLOW,
}

/** Why a full detection pass is (or should be) run. */
enum class FullDetectionReason {
    /** No document has been detected yet in this session. */
    INITIAL,

    /** Optical-flow tracking failed its validation. */
    TRACK_LOST,

    /** The detection produced a quad that fails geometric validation. */
    INVALID_QUAD,
}

/** Result of one tracker update: the quad to display, in mask coordinates. */
data class TrackUpdate(
    val quadInMask: Quad?,
    val mode: TrackMode,
    val meanTrackingError: Float,
    val validCornerCount: Int,
    val fullDetectionReason: FullDetectionReason?,
)

/**
 * Two-path document tracking, vFlat-style:
 *
 * - full detections (quad in mask coordinates, produced by segmentation +
 *   contour detection) anchor the tracker every [fullDetectionEveryFrames]
 *   frames and whenever tracking fails;
 * - in between, the four corners are followed with `calcOpticalFlowPyrLK`
 *   (Lucas-Kanade) on grayscale frames, which costs a fraction of a full
 *   detection and keeps the overlay updating at camera frame rate.
 *
 * Every tracked quad is validated: corner order is preserved from the anchor
 * quad, the polygon must stay convex (no self-crossing), the area must stay
 * reasonable, the per-frame corner travel must stay below
 * [maxFrameShiftRatio] of the frame diagonal, and the mean LK error must stay
 * below [maxLkError]. On failure the last valid quad is held for one frame
 * and the next frame runs a full detection, so the overlay never jumps.
 */
class OpticalFlowQuadTracker(
    private val fullDetectionEveryFrames: Int = 10,
    private val lkWinSize: Int = 21,
    private val lkMaxLevel: Int = 3,
    private val maxLkError: Float = 15f,
    private val maxFrameShiftRatio: Double = QuadValidator.DEFAULT_MAX_FRAME_SHIFT_RATIO,
    /**
     * When a full detection only refines the current quad by less than this
     * distance (frame pixels), the previous quad is published instead of the
     * fresh one. This keeps periodic refreshes from producing small jumps in
     * the published quad, which would reset the auto-capture stability
     * countdown every [fullDetectionEveryFrames] frames.
     */
    private val refreshAdoptDistance: Double = 30.0,
) {

    private var prevGray: Mat? = null
    private var prevQuadInFrame: Quad? = null
    private var framesSinceFullDetection = 0
    private var pendingFullDetection = false

    private val prevCorners = MatOfPoint2f()
    private val nextCorners = MatOfPoint2f()
    private val status = MatOfByte()
    private val err = MatOfFloat()
    private val criteria = TermCriteria(TermCriteria.COUNT or TermCriteria.EPS, 20, 0.03)

    fun reset() {
        prevGray?.release()
        prevGray = null
        prevQuadInFrame = null
        framesSinceFullDetection = 0
        pendingFullDetection = false
    }

    fun release() = reset()

    /** Whether the caller should run the expensive full detection this frame. */
    fun shouldRunFullDetection(): Boolean =
        prevGray == null || prevQuadInFrame == null || pendingFullDetection ||
                framesSinceFullDetection >= fullDetectionEveryFrames

    /**
     * Advances the tracker with the current grayscale frame.
     *
     * @param frameGray grayscale version of the analysis frame (sensor orientation).
     * @param fullDetectionRan whether the caller ran a full detection this frame.
     * @param fullDetectionQuadInMask the freshly detected quad (mask coordinates,
     *   sensor orientation) when [fullDetectionRan], `null` otherwise or when
     *   nothing was detected.
     * @param maskWidth/maskHeight dimensions of the segmentation mask that
     *   produced the quads (for the frame<->mask coordinate mapping).
     */
    fun update(
        frameGray: Mat,
        fullDetectionRan: Boolean,
        fullDetectionQuadInMask: Quad?,
        maskWidth: Double,
        maskHeight: Double,
    ): TrackUpdate {
        val frameWidth = frameGray.cols().toDouble()
        val frameHeight = frameGray.rows().toDouble()

        if (fullDetectionRan) {
            framesSinceFullDetection = 0
            pendingFullDetection = false

            val quad = fullDetectionQuadInMask
            if (quad == null) {
                prevQuadInFrame = null
                return TrackUpdate(
                    null,
                    TrackMode.FULL_DETECTION,
                    0f,
                    0,
                    FullDetectionReason.TRACK_LOST
                )
            }

            val quadInFrame = quad.scaledTo(maskWidth, maskHeight, frameWidth, frameHeight)
            if (!QuadValidator.isValid(
                    quadInFrame, frameWidth, frameHeight,
                    maxFrameShiftRatio = maxFrameShiftRatio,
                )
            ) {
                prevQuadInFrame = null
                return TrackUpdate(
                    null,
                    TrackMode.FULL_DETECTION,
                    0f,
                    0,
                    FullDetectionReason.INVALID_QUAD
                )
            }

            // Temporal consistency: when the fresh detection is a small
            // refinement of the current quad, publish the previous one so
            // periodic refreshes don't jitter the overlay or the auto-capture
            // stability. The tracker itself re-anchors on the fresh detection.
            val previousQuadInFrame = prevQuadInFrame
            val publishedQuad = if (previousQuadInFrame != null &&
                QuadValidator.maxCornerDistance(
                    quadInFrame,
                    previousQuadInFrame
                ) <= refreshAdoptDistance
            ) {
                previousQuadInFrame.scaledTo(frameWidth, frameHeight, maskWidth, maskHeight)
            } else {
                quad
            }

            prevQuadInFrame = quadInFrame
            setPrevGray(frameGray)
            setPrevCorners(quadInFrame)
            return TrackUpdate(publishedQuad, TrackMode.FULL_DETECTION, 0f, 4, null)
        }

        framesSinceFullDetection++

        val prevQuad = prevQuadInFrame
        val prevGrayMat = prevGray
        if (prevQuad == null || prevGrayMat == null) {
            return TrackUpdate(null, TrackMode.OPTICAL_FLOW, 0f, 0, FullDetectionReason.INITIAL)
        }

        val tracked = trackWithOpticalFlow(prevGrayMat, frameGray)
        if (tracked != null &&
            QuadValidator.isValid(
                tracked, frameWidth, frameHeight,
                previousQuad = prevQuad,
                maxFrameShiftRatio = maxFrameShiftRatio,
            )
        ) {
            prevQuadInFrame = tracked
            setPrevGray(frameGray)
            setPrevCorners(tracked)
            val quadInMask = tracked.scaledTo(frameWidth, frameHeight, maskWidth, maskHeight)
            return TrackUpdate(quadInMask, TrackMode.OPTICAL_FLOW, meanError(), 4, null)
        }

        // Tracking failed: hold the last valid quad for this frame and force a
        // full detection on the next one so the overlay does not flicker.
        pendingFullDetection = true
        val quadInMask = prevQuad.scaledTo(frameWidth, frameHeight, maskWidth, maskHeight)
        return TrackUpdate(
            quadInMask,
            TrackMode.OPTICAL_FLOW,
            meanError(),
            validCornerCount(),
            FullDetectionReason.TRACK_LOST,
        )
    }

    private fun trackWithOpticalFlow(prevGrayMat: Mat, nextGrayMat: Mat): Quad? {
        Video.calcOpticalFlowPyrLK(
            prevGrayMat,
            nextGrayMat,
            prevCorners,
            nextCorners,
            status,
            err,
            Size(lkWinSize.toDouble(), lkWinSize.toDouble()),
            lkMaxLevel,
            criteria,
        )

        val statusArray = ByteArray(4)
        status.get(0, 0, statusArray)
        if (statusArray.any { it.toInt() == 0 }) return null

        val errArray = FloatArray(4)
        err.get(0, 0, errArray)
        if ((errArray.maxOrNull() ?: Float.MAX_VALUE) > maxLkError) return null

        val trackedPoints = nextCorners.toArray()
        if (trackedPoints.size != 4) return null

        // Corner order is preserved by LK; do not re-sort.
        return Quad(
            Point(trackedPoints[0].x, trackedPoints[0].y),
            Point(trackedPoints[1].x, trackedPoints[1].y),
            Point(trackedPoints[2].x, trackedPoints[2].y),
            Point(trackedPoints[3].x, trackedPoints[3].y),
        )
    }

    private fun setPrevGray(frameGray: Mat) {
        prevGray?.release()
        prevGray = frameGray.clone()
    }

    private fun setPrevCorners(quadInFrame: Quad) {
        prevCorners.fromList(
            listOf(
                quadInFrame.topLeft.toCv(),
                quadInFrame.topRight.toCv(),
                quadInFrame.bottomRight.toCv(),
                quadInFrame.bottomLeft.toCv(),
            )
        )
    }

    private fun meanError(): Float {
        val errArray = FloatArray(4)
        err.get(0, 0, errArray)
        return if (errArray.all { it == 0f }) 0f else errArray.average().toFloat()
    }

    private fun validCornerCount(): Int {
        val statusArray = ByteArray(4)
        status.get(0, 0, statusArray)
        return statusArray.count { it.toInt() != 0 }
    }
}

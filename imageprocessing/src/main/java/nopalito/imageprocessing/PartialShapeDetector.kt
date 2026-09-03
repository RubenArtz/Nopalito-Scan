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
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Progressive document-shape feedback for the live preview.
 *
 * While only part of a document is recognizable the detector reports the
 * corners it can support so the overlay can draw the shape progressively:
 *
 * - 1 L-corner (two near-perpendicular straight edges meeting) -> draw that L;
 * - 2 linked corners -> draw the edge between them;
 * - 3 corners -> draw the lines available;
 * - 4 corners forming a validated convex quadrilateral -> [isComplete], the
 *   caller draws the full frame.
 *
 * Only straight structure is considered: edges come from Canny + probabilistic
 * Hough lines and curved objects cannot produce long straight collinear
 * segments, so circles/rounded shapes are naturally ignored.
 */
data class PartialShape(
    /** Detected corners ordered clockwise around the document. */
    val corners: List<Point>,
    /**
     * Index pairs into [corners] whose connecting edge is backed by straight
     * pixels; only these are drawn as solid lines.
     */
    val linkedEdges: List<Pair<Int, Int>>,
    /**
     * Standalone segments drawn without two detected corners — e.g. the two
     * arms of a single detected L-corner.
     */
    val openArms: List<Pair<Point, Point>> = emptyList(),
    /** True when [corners] forms a validated quadrilateral (full document). */
    val isComplete: Boolean = false,
) {
    companion object {
        fun full(quad: Quad): PartialShape = PartialShape(
            corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft),
            linkedEdges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0),
            isComplete = true,
        )
    }
}

/** Scales every point of [this] from one coordinate space to another. */
fun PartialShape.scaledTo(
    fromWidth: Double,
    fromHeight: Double,
    toWidth: Double,
    toHeight: Double,
): PartialShape {
    fun scale(p: Point) = p.scaled(toWidth / fromWidth, toHeight / fromHeight)
    return PartialShape(
        corners = corners.map(::scale),
        linkedEdges = linkedEdges,
        openArms = openArms.map { scale(it.first) to scale(it.second) },
        isComplete = isComplete,
    )
}

/**
 * Detects partial document geometry on a grayscale frame:
 *
 * - Canny edges + probabilistic Hough lines provide straight support segments;
 * - Harris corner response ([HarrisCorners.goodFeaturesToTrack] with
 *   `useHarrisDetector = true`) marks candidate vertices;
 * - a vertex becomes a document **L-corner** when two supporting segments with
 *   roughly perpendicular directions meet near it;
 * - four L-corners forming a validated convex quadrilateral produce the
 *   complete shape; anything less is reported progressively.
 */
class PartialShapeDetector(
    private val cannyLow: Double = 50.0,
    private val cannyHigh: Double = 150.0,
    private val harrisBlockSize: Int = 3,
    private val harrisGradientSize: Int = 3,
    private val harrisK: Double = 0.04,
    private val harrisQuality: Double = 0.08,
    /** Harris corners closer than this fraction of the diagonal merge into one. */
    private val mergeRadiusRatio: Double = 0.05,
    /** Minimum Hough segment length (fraction of the diagonal) to act as support. */
    private val minLineLengthRatio: Double = 0.07,
    /** Max distance from a segment to a corner for the segment to support it. */
    private val lineSupportRadiusRatio: Double = 0.03,
    /** Interior angle window (degrees) for two segments to form an L-corner. */
    private val lAngleMinDeg: Double = 55.0,
    private val lAngleMaxDeg: Double = 125.0,
    /** Length cap for the arms drawn around an isolated L-corner. */
    private val maxArmRatio: Double = 0.22,
) {

    /** A document corner backed by two near-perpendicular straight segments. */
    private data class LCorner(val point: Point, val arms: List<Segment>)

    private data class Segment(val a: Point, val b: Point) {
        val length: Double = norm(a, b)

        fun directionDeg(): Double = Math.toDegrees(atan2(b.y - a.y, b.x - a.x))
    }

    fun release() {
        // Stateless across frames; kept for symmetry with other detectors.
    }

    fun detect(frameGray: Mat): PartialShape? {
        val width = frameGray.cols().toDouble()
        val height = frameGray.rows().toDouble()
        val diagonal = hypot(width, height)
        if (diagonal <= 0.0) return null

        val blurred = Mat()
        Imgproc.GaussianBlur(frameGray, blurred, Size(5.0, 5.0), 0.0)

        // --- Straight structure: Canny + probabilistic Hough -----------------
        val edges = Mat()
        Imgproc.Canny(blurred, edges, cannyLow, cannyHigh)
        val linesMat = Mat()
        Imgproc.HoughLinesP(
            edges,
            linesMat,
            1.0,
            PI / 180.0,
            HOUGH_VOTES,
            max(MIN_HOUGH_LINE_PX, minLineLengthRatio * diagonal),
            HOUGH_MAX_GAP_PX,
        )
        edges.release()

        val segments = mutableListOf<Segment>()
        if (!linesMat.empty()) {
            // HoughLinesP outputs a Nx1 CV_32SC4 matrix: read it as ints.
            for (row in 0 until linesMat.rows()) {
                val line = IntArray(4)
                linesMat.get(row, 0, line)
                segments += Segment(
                    Point(line[0].toDouble(), line[1].toDouble()),
                    Point(line[2].toDouble(), line[3].toDouble()),
                )
            }
        }
        linesMat.release()
        if (segments.isEmpty()) {
            blurred.release()
            return null
        }

        // --- Harris corner candidates ---------------------------------------
        val cornersMat = MatOfPoint()
        HarrisCorners.goodFeaturesToTrack(
            blurred,
            cornersMat,
            MAX_CANDIDATE_CORNERS,
            harrisQuality,
            mergeRadiusRatio * diagonal,
            Mat(),
            harrisBlockSize,
            harrisGradientSize,
            true, // useHarrisDetector — classic Harris response.
            harrisK,
        )
        blurred.release()
        val candidates = cornersMat.toArray().map { Point(it.x, it.y) }
        cornersMat.release()
        if (candidates.isEmpty()) return null

        val supportRadius = lineSupportRadiusRatio * diagonal
        val maxArmLength = maxArmRatio * diagonal

        // --- Keep vertices supported by at least one real segment ------------
        val lCorners = mutableListOf<LCorner>()
        for (candidate in candidates) {
            val near = segments.filter { distancePointSegment(candidate, it) <= supportRadius }
            if (near.size < MIN_SUPPORTING_SEGMENTS) continue
            // Best pair of near-perpendicular supporting segments = the L arms.
            var bestPair: Pair<Segment, Segment>? = null
            var bestCombined = -1.0
            for (i in near.indices) {
                for (j in i + 1 until near.size) {
                    val diff = absAngleDiff(near[i].directionDeg(), near[j].directionDeg())
                    if (diff in lAngleMinDeg..lAngleMaxDeg && near[i].length + near[j].length > bestCombined) {
                        bestCombined = near[i].length + near[j].length
                        bestPair = near[i] to near[j]
                    }
                }
            }
            if (bestPair != null) {
                lCorners += LCorner(candidate, listOf(bestPair.first, bestPair.second))
                if (lCorners.size >= MAX_L_CORNERS) break
            }
        }
        if (lCorners.isEmpty()) return null

        // --- Try to assemble a complete quadrilateral first ------------------
        if (lCorners.size >= 4) {
            val quad =
                assembleQuad(lCorners.map { it.point }, width, height, segments, supportRadius)
            if (quad != null) return PartialShape.full(quad)
        }

        // --- Progressive fallback --------------------------------------------
        return buildPartial(lCorners, segments, supportRadius, maxArmLength)
    }

    /**
     * Picks the 4-corner combination whose consecutive edges are best backed
     * by detected segments and passes geometric validation.
     */
    private fun assembleQuad(
        corners: List<Point>,
        width: Double,
        height: Double,
        segments: List<Segment>,
        supportRadius: Double,
    ): Quad? {
        require(corners.size >= 4)
        var bestQuad: Quad? = null
        var bestSupported = -1
        for (a in 0 until corners.size - 3) {
            for (b in a + 1 until corners.size - 2) {
                for (c in b + 1 until corners.size - 1) {
                    for (d in c + 1 until corners.size) {
                        val ordered =
                            createQuad(listOf(corners[a], corners[b], corners[c], corners[d]))
                        if (!QuadValidator.isValid(ordered, width, height)) continue
                        val points = listOf(
                            ordered.topLeft,
                            ordered.topRight,
                            ordered.bottomRight,
                            ordered.bottomLeft,
                        )
                        var supportedEdges = 0
                        for (i in points.indices) {
                            val start = points[i]
                            val end = points[(i + 1) % 4]
                            if (hasConnectingSegment(
                                    start,
                                    end,
                                    segments,
                                    supportRadius
                                )
                            ) supportedEdges++
                        }
                        // A complete document must still show most of its outline;
                        // the spec allows drawing it even when some sides are
                        // barely visible.
                        if (supportedEdges >= MIN_SUPPORTED_QUAD_EDGES && supportedEdges > bestSupported) {
                            bestSupported = supportedEdges
                            bestQuad = ordered
                        }
                    }
                }
            }
        }
        return bestQuad
    }

    /** Builds the progressive shape out of fewer than four corners. */
    private fun buildPartial(
        lCorners: List<LCorner>,
        segments: List<Segment>,
        supportRadius: Double,
        maxArmLength: Double,
    ): PartialShape? {
        val chosen = lCorners.take(MAX_PARTIAL_CORNERS).map { it.point }
        val centroidX = chosen.map { it.x }.average()
        val centroidY = chosen.map { it.y }.average()
        // Same ordering convention as createQuad (atan2 ascending).
        val ordered = chosen.sortedWith(compareBy { atan2(it.y - centroidY, it.x - centroidX) })

        val linkedEdges = mutableListOf<Pair<Int, Int>>()
        val linkedIndexes = mutableSetOf<Int>()
        for (i in ordered.indices) {
            val j = (i + 1) % ordered.size
            if (j == i) continue
            if (hasConnectingSegment(ordered[i], ordered[j], segments, supportRadius)) {
                linkedEdges += i to j
                linkedIndexes += i
                linkedIndexes += j
            }
        }

        // Corners without a partner still show their own L arms.
        val openArms = mutableListOf<Pair<Point, Point>>()
        lCorners.take(MAX_PARTIAL_CORNERS).forEachIndexed { index, corner ->
            if (index !in linkedIndexes) {
                for (segment in corner.arms) openArms += trimArm(
                    corner.point,
                    segment,
                    maxArmLength
                )
            }
        }

        if (linkedEdges.isEmpty() && openArms.isEmpty()) return null
        return PartialShape(corners = ordered, linkedEdges = linkedEdges, openArms = openArms)
    }

    private fun trimArm(corner: Point, segment: Segment, maxLength: Double): Pair<Point, Point> {
        val far = if (norm(corner, segment.a) >= norm(corner, segment.b)) segment.a else segment.b
        val length = norm(corner, far)
        return if (length <= maxLength || length == 0.0) {
            corner to far
        } else {
            val ratio = maxLength / length
            corner to Point(
                corner.x + (far.x - corner.x) * ratio,
                corner.y + (far.y - corner.y) * ratio
            )
        }
    }

    private fun hasConnectingSegment(
        a: Point,
        b: Point,
        segments: List<Segment>,
        supportRadius: Double,
    ): Boolean {
        val chordDirection = Math.toDegrees(atan2(b.y - a.y, b.x - a.x))
        val chordLength = norm(a, b)
        for (segment in segments) {
            if (distancePointSegment(a, segment) > supportRadius) continue
            if (distancePointSegment(b, segment) > supportRadius) continue
            val diff = absAngleDiff(segment.directionDeg(), chordDirection)
            if (diff <= EDGE_DIRECTION_TOLERANCE_DEG && segment.length >= chordLength * MIN_CHORD_COVERAGE) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val MAX_CANDIDATE_CORNERS = 48
        private const val MAX_L_CORNERS = 8
        private const val MAX_PARTIAL_CORNERS = 3
        private const val MIN_SUPPORTED_QUAD_EDGES = 2
        private const val MIN_SUPPORTING_SEGMENTS = 2
        private const val EDGE_DIRECTION_TOLERANCE_DEG = 35.0
        private const val MIN_CHORD_COVERAGE = 0.6
        private const val HOUGH_VOTES = 40
        private const val HOUGH_MAX_GAP_PX = 10.0
        private const val MIN_HOUGH_LINE_PX = 24.0

        /** Absolute angle difference folded into [0, 90]. */
        private fun absAngleDiff(a: Double, b: Double): Double {
            val raw = abs(a - b) % 180.0
            return min(raw, 180.0 - raw)
        }

        private fun distancePointSegment(p: Point, segment: Segment): Double {
            val abx = segment.b.x - segment.a.x
            val aby = segment.b.y - segment.a.y
            val lengthSquared = abx * abx + aby * aby
            if (lengthSquared == 0.0) return norm(p, segment.a)
            val t = (((p.x - segment.a.x) * abx + (p.y - segment.a.y) * aby) / lengthSquared)
                .coerceIn(0.0, 1.0)
            return norm(p, Point(segment.a.x + t * abx, segment.a.y + t * aby))
        }
    }
}
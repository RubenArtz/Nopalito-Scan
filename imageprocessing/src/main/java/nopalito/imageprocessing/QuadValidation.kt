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

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Geometric validation of document quadrilaterals, shared by the detection
 * path and the optical-flow tracker so both reject the same malformed shapes.
 */
object QuadValidator {

    /** Minimum share of the frame a detected document must cover. */
    const val DEFAULT_MIN_AREA_RATIO = 0.02

    /** Maximum share of the frame a document may cover before it is suspicious. */
    const val DEFAULT_MAX_AREA_RATIO = 0.98

    /** Max corner travel between two consecutive frames, relative to the frame diagonal. */
    const val DEFAULT_MAX_FRAME_SHIFT_RATIO = 0.2

    /**
     * Minimum interior angle (degrees) allowed at any corner before the quad
     * looks like a needle/sliver artifact instead of a document.
     */
    const val DEFAULT_MIN_CORNER_ANGLE_DEG = 25.0

    /**
     * Minimum distance between any two corners, relative to the frame diagonal;
     * catches collapsing corners (thin strips, slivers, optical-flow drift).
     */
    const val DEFAULT_MIN_CORNER_SEPARATION_RATIO = 0.02

    /** Corners may stick out of the frame by this many pixels and still be valid. */
    const val EDGE_MARGIN_PX = 10.0

    /** Shoelace area of a quadrilateral. */
    fun quadArea(quad: Quad): Double {
        val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        var area = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            area += points[i].x * points[j].y
            area -= points[j].x * points[i].y
        }
        return abs(area) / 2.0
    }

    /** Largest distance between matching corners of two quads. */
    fun maxCornerDistance(a: Quad, b: Quad): Double {
        return maxOf(
            norm(a.topLeft, b.topLeft),
            norm(a.topRight, b.topRight),
            norm(a.bottomRight, b.bottomRight),
            norm(a.bottomLeft, b.bottomLeft),
        )
    }

    /**
     * Smallest interior angle of [quad], in degrees. Returns 0 when any edge is
     * degenerate (two corners at the same position).
     */
    fun minInteriorAngleDeg(quad: Quad): Double {
        val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        var minAngle = 180.0
        for (i in points.indices) {
            val prev = points[(i + 3) % 4]
            val cur = points[i]
            val next = points[(i + 1) % 4]
            val toPrevX = prev.x - cur.x
            val toPrevY = prev.y - cur.y
            val toNextX = next.x - cur.x
            val toNextY = next.y - cur.y
            val lenPrev = hypot(toPrevX, toPrevY)
            val lenNext = hypot(toNextX, toNextY)
            if (lenPrev == 0.0 || lenNext == 0.0) return 0.0
            val cosine = ((toPrevX * toNextX + toPrevY * toNextY) / (lenPrev * lenNext))
                .coerceIn(-1.0, 1.0)
            val angle = Math.toDegrees(acos(cosine))
            if (angle < minAngle) minAngle = angle
        }
        return minAngle
    }

    /** Smallest distance between any two corners of [quad]. */
    fun minCornerSeparation(quad: Quad): Double {
        val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        var minDistance = Double.MAX_VALUE
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val distance = norm(points[i], points[j])
                if (distance < minDistance) minDistance = distance
            }
        }
        return minDistance
    }

    private fun isInsideFrame(quad: Quad, frameWidth: Double, frameHeight: Double): Boolean {
        val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        return points.all {
            it.x >= -EDGE_MARGIN_PX && it.x <= frameWidth + EDGE_MARGIN_PX &&
                    it.y >= -EDGE_MARGIN_PX && it.y <= frameHeight + EDGE_MARGIN_PX
        }
    }

    /**
     * Returns `true` when [quad] looks like a real document in a
     * [frameWidth]x[frameHeight] frame:
     *
     * - corners ordered consistently and inside (or almost inside) the frame;
     * - strictly convex polygon (this also rules out self-crossing sides);
     * - no degenerate geometry: interior angles of at least
     *   [minCornerAngleDeg] and corners separated by at least
     *   [minCornerSeparationRatio] of the frame diagonal (rules out slivers,
     *   thin strips and collapsing-corner artifacts);
     * - area within [minAreaRatio]..[maxAreaRatio] of the frame area;
     * - no corner moved further than [maxFrameShiftRatio] of the frame diagonal
     *   since [previousQuad], when given.
     */
    fun isValid(
        quad: Quad,
        frameWidth: Double,
        frameHeight: Double,
        previousQuad: Quad? = null,
        minAreaRatio: Double = DEFAULT_MIN_AREA_RATIO,
        maxAreaRatio: Double = DEFAULT_MAX_AREA_RATIO,
        maxFrameShiftRatio: Double = DEFAULT_MAX_FRAME_SHIFT_RATIO,
        minCornerAngleDeg: Double = DEFAULT_MIN_CORNER_ANGLE_DEG,
        minCornerSeparationRatio: Double = DEFAULT_MIN_CORNER_SEPARATION_RATIO,
    ): Boolean {
        if (!isInsideFrame(quad, frameWidth, frameHeight)) return false
        if (!quad.isConvex()) return false

        // Shape sanity: needle-like corners or collapsing corners are
        // detection/tracking artifacts, not documents.
        if (minInteriorAngleDeg(quad) < minCornerAngleDeg) return false

        val diagonal = hypot(frameWidth, frameHeight)
        if (minCornerSeparation(quad) < minCornerSeparationRatio * diagonal) return false

        val frameArea = frameWidth * frameHeight
        val area = quadArea(quad)
        if (area < minAreaRatio * frameArea || area > maxAreaRatio * frameArea) return false

        if (previousQuad != null) {
            if (maxCornerDistance(previousQuad, quad) > maxFrameShiftRatio * diagonal) return false
        }
        return true
    }
}

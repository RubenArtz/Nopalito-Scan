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

package nopalito.app.ui.screens.camera

import android.util.Log
import nopalito.imageprocessing.Point
import nopalito.imageprocessing.Quad
import nopalito.imageprocessing.norm

class QuadStabilizer {

    private var stableCount = 0
    private var lastRawQuad: Quad? = null
    private var maskArea: Double = 0.0

    /**
     * Updates the stabilizer with a new raw quad detection.
     * Returns a stabilized quad only when the document is sufficiently stable.
     * 
     * @param rawQuad The quad detected in the current frame (mask coordinates), or null
     * @param frameWidth Width of the mask/probmap (used for minArea calculation)
     * @param frameHeight Height of the mask/probmap
     * @return A stabilized quad if stable, null otherwise
     */
    fun update(rawQuad: Quad?, frameWidth: Int = 0, frameHeight: Int = 0): Quad? {
        if (frameWidth > 0 && frameHeight > 0) {
            maskArea = frameWidth.toDouble() * frameHeight.toDouble()
        }
        val previousQuad = lastRawQuad
        lastRawQuad = rawQuad

        if (rawQuad == null) {
            stableCount = 0
            return null
        }

        // Minimum area validation: document must occupy at least 15% of the frame
        if (maskArea > 0 && rawQuad.areaRatio(maskArea) < 0.15) {
            Log.w(
                "QuadStabilizer",
                "Quad too small: areaRatio=${rawQuad.areaRatio(maskArea)}, threshold=0.15"
            )
            stableCount = 0
            return null
        }

        if (previousQuad == null) {
            stableCount = 1
            return null
        }

        val dist = previousQuad.maxCornerDistanceTo(rawQuad)
        Log.d("QuadStabilizer", "dist=$dist, stableCount=$stableCount")

        // 30f in mask coordinates (e.g. 192x256) allows ~12-15% movement which is reasonable
        if (dist < 30f) {
            stableCount++
        } else {
            stableCount = 1
        }

        val isStable = stableCount >= 3
        if (isStable) {
            Log.d("QuadStabilizer", "STABLE after $stableCount frames, dist=$dist")
        }
        return if (isStable) rawQuad else null
    }
}

private fun Quad.maxCornerDistanceTo(other: Quad): Float {
    return listOf(
        norm(topLeft, other.topLeft),
        norm(topRight, other.topRight),
        norm(bottomRight, other.bottomRight),
        norm(bottomLeft, other.bottomLeft),
    ).max().toFloat()
}

private fun Quad.areaRatio(totalArea: Double): Double {
    // Approximate area using the shoelace formula on the quad corners
    val points = listOf(topLeft, topRight, bottomRight, bottomLeft)
    var area = 0.0
    val n = points.size
    for (i in 0 until n) {
        val j = (i + 1) % n
        area += points[i].x * points[j].y
        area -= points[j].x * points[i].y
    }
    area = kotlin.math.abs(area) / 2.0
    return area / totalArea
}

fun lerp(a: Point, b: Point, alpha: Float): Point {
    return Point(
        x = a.x + alpha * (b.x - a.x),
        y = a.y + alpha * (b.y - a.y)
    )
}

fun lerpQuad(a: Quad, b: Quad, alpha: Float): Quad {
    return Quad(
        topLeft = lerp(a.topLeft, b.topLeft, alpha),
        topRight = lerp(a.topRight, b.topRight, alpha),
        bottomRight = lerp(a.bottomRight, b.bottomRight, alpha),
        bottomLeft = lerp(a.bottomLeft, b.bottomLeft, alpha),
    )
}

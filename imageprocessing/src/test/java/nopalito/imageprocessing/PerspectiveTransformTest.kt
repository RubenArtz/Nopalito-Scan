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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerspectiveTransformTest {

    companion object {
        init {
            runCatching { nu.pattern.OpenCV.loadShared() }
        }
    }

    private fun apply(m: Mat, p: Point): Point {
        val h = DoubleArray(9)
        m.get(0, 0, h)
        val w = h[6] * p.x + h[7] * p.y + h[8]
        return Point(
            (h[0] * p.x + h[1] * p.y + h[2]) / w,
            (h[3] * p.x + h[4] * p.y + h[5]) / w,
        )
    }

    @Test
    fun `maps the four source corners exactly onto the destination`() {
        val src = listOf(
            Point(100.0, 80.0),
            Point(620.0, 130.0),
            Point(540.0, 880.0),
            Point(60.0, 700.0),
        )
        val dst = listOf(
            Point(0.0, 0.0),
            Point(1240.0, 0.0),
            Point(1240.0, 1754.0),
            Point(0.0, 1754.0),
        )

        val m = getPerspectiveTransform(src, dst)

        assertEquals(CvType.CV_64FC1, m.type())
        assertEquals(3, m.rows())
        assertEquals(3, m.cols())

        for (i in src.indices) {
            val mapped = apply(m, src[i])
            assertTrue(hypot(mapped.x - dst[i].x, mapped.y - dst[i].y) < 1e-6,
                "corner $i mapped to ($mapped) but expected ${dst[i]}")
        }
    }

    @Test
    fun `reproduces the identity for a quad mapped onto itself`() {
        val quad = listOf(
            Point(10.0, 10.0),
            Point(300.0, 10.0),
            Point(300.0, 400.0),
            Point(10.0, 400.0),
        )
        val m = getPerspectiveTransform(quad, quad)
        val h = DoubleArray(9)
        m.get(0, 0, h)
        for (i in quad.indices) {
            val p = apply(m, quad[i])
            assertEquals(quad[i].x, p.x, 1e-9)
            assertEquals(quad[i].y, p.y, 1e-9)
        }
        assertTrue(abs(h[8] - 1.0) < 1e-9)
    }

    @Test
    fun `rejects quads that are not 4 points`() {
        try {
            getPerspectiveTransform(listOf(Point(0.0, 0.0)), listOf(Point(1.0, 1.0)))
            assertTrue(false, "expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuadFrameAlignmentTest {

    // Square segmentation mask for a 4:3 analysis frame (the real project setup).
    private val maskSize = ImageSize(256.0, 256.0)
    private val analysisSize = ImageSize(640.0, 480.0)
    private val previewSize = ImageSize(1080.0, 1920.0)

    // Guide frame: centered 85%-width card frame (1.6:1) on a portrait preview,
    // same proportions as the INE guide overlay.
    private val frameW = previewSize.width * 0.85
    private val frameH = frameW * (5.0 / 8.0)
    private val frameLeft = (previewSize.width - frameW) / 2.0
    private val frameTop = (previewSize.height - frameH) / 2.0 - previewSize.height * 0.09
    private val frameRight = frameLeft + frameW
    private val frameBottom = frameTop + frameH

    // A card covering 60% of the frame area, centered on it.
    private val centeredCard = Quad(
        Point(96.0, 85.33), Point(160.0, 85.33), Point(160.0, 170.67), Point(96.0, 170.67)
    )

    private fun aligned(
        quad: Quad,
        rotation: Int = 0,
        minCoverage: Double = 0.2,
        preview: ImageSize = previewSize,
        left: Double = frameLeft,
        top: Double = frameTop,
        right: Double = frameRight,
        bottom: Double = frameBottom,
    ) = isQuadAlignedWithFrame(
        quad, maskSize, analysisSize, rotation, preview, left, top, right, bottom, minCoverage
    )

    @Test
    fun `card centered in the frame is aligned`() {
        assertTrue(aligned(centeredCard))
    }

    @Test
    fun `card off to the side of the frame is not aligned`() {
        // Same size card shifted so its center sits outside the frame.
        val offCenter = Quad(
            Point(20.0, 85.33), Point(84.0, 85.33), Point(84.0, 170.67), Point(20.0, 170.67)
        )
        assertFalse(aligned(offCenter))
    }

    @Test
    fun `tiny far-away card inside the frame is not aligned`() {
        // Analysis px: 300..340 x 210..250 (1.5% of frame area).
        val tiny = Quad(
            Point(120.0, 112.0), Point(136.0, 112.0), Point(136.0, 133.33), Point(120.0, 133.33)
        )
        assertFalse(aligned(tiny))
    }

    @Test
    fun `card covering the whole frame is aligned`() {
        // Mask corners = the whole analysis frame -> maps to the whole preview.
        val full = Quad(Point(0.0, 0.0), Point(256.0, 0.0), Point(256.0, 256.0), Point(0.0, 256.0))
        assertTrue(aligned(full))
    }

    @Test
    fun `alignment holds under portrait rotation`() {
        // The centered card stays centered on the preview after a 90-degree
        // rotation of the analysis frame.
        assertTrue(aligned(centeredCard, rotation = 90))
    }

    @Test
    fun `coverage threshold is configurable`() {
        // The tiny card covers ~1.5%: fails at 20%, passes at 1%.
        val tiny = Quad(
            Point(120.0, 112.0), Point(136.0, 112.0), Point(136.0, 133.33), Point(120.0, 133.33)
        )
        assertFalse(aligned(tiny, minCoverage = 0.2))
        assertTrue(aligned(tiny, minCoverage = 0.01))
    }

    @Test
    fun `null-safe on degenerate frame`() {
        val full = Quad(Point(0.0, 0.0), Point(256.0, 0.0), Point(256.0, 256.0), Point(0.0, 256.0))
        assertFalse(aligned(full, left = 10.0, top = 10.0, right = 10.0, bottom = 10.0))
    }
}
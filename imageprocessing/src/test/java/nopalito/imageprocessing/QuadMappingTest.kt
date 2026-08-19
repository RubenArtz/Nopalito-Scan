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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuadMappingTest {

    // Square segmentation mask for a 4:3 analysis frame (the real project setup).
    private val maskSize = ImageSize(256.0, 256.0)
    private val analysisSize = ImageSize(640.0, 480.0)

    private fun assertPointClose(expected: Point, actual: Point, tolerance: Double) {
        assertTrue(
            norm(expected, actual) <= tolerance,
            "expected $expected ± $tolerance, got $actual"
        )
    }

    @Test
    fun `a corner of the frame maps to the corresponding preview corner`() {
        // Continuous mask corners: (0,0)..(256,256) span the whole 640x480 frame.
        val quad = Quad(Point(0.0, 0.0), Point(256.0, 0.0), Point(256.0, 256.0), Point(0.0, 256.0))
        val preview = ImageSize(800.0, 600.0) // same 4:3 aspect as analysis

        val mapped = mapAnalysisQuadToPreview(quad, maskSize, analysisSize, preview, 0)

        assertPointClose(Point(0.0, 0.0), mapped.topLeft, 0.001)
        assertPointClose(Point(800.0, 0.0), mapped.topRight, 0.001)
        assertPointClose(Point(800.0, 600.0), mapped.bottomRight, 0.001)
        assertPointClose(Point(0.0, 600.0), mapped.bottomLeft, 0.001)
    }

    @Test
    fun `square mask un-squishes to the 4-3 analysis frame`() {
        // A document of 320x240 analysis px (half the frame) -> mask coords
        // squished: x = 320*256/640 = 128, y = 240*256/480 = 128.
        val quad = Quad(Point(0.0, 0.0), Point(128.0, 0.0), Point(128.0, 128.0), Point(0.0, 128.0))
        val preview = ImageSize(640.0, 480.0) // same aspect, identity-ish scale

        val mapped = mapAnalysisQuadToPreview(quad, maskSize, analysisSize, preview, 0)

        // The drawn quad must be 4:3 (320x240), NOT the square the mask suggests.
        assertPointClose(Point(0.0, 0.0), mapped.topLeft, 0.001)
        assertPointClose(Point(320.0, 0.0), mapped.topRight, 0.001)
        assertPointClose(Point(320.0, 240.0), mapped.bottomRight, 0.001)
        assertPointClose(Point(0.0, 240.0), mapped.bottomLeft, 0.001)
    }

    @Test
    fun `portrait rotation keeps the quad aligned with the preview`() {
        // Landscape document of 320x240 analysis px at the frame origin.
        val quad = Quad(
            Point(0.0, 0.0), Point(128.0, 0.0), Point(128.0, 128.0), Point(0.0, 128.0)
        )
        // Portrait preview: 480x640 would be identity scale for rotated frame
        // (480x640). Use it so the mapping is exact.
        val preview = ImageSize(480.0, 640.0)

        val mapped = mapAnalysisQuadToPreview(quad, maskSize, analysisSize, preview, 90)

        // Frame (0,0)-(320,240) rotated 90 deg CW: x' = 480 - y, y' = x.
        // Rotated points: (480,0), (480,320), (240,320), (240,0); the quad is
        // re-ordered as TL(240,0), TR(480,0), BR(480,320), BL(240,320).
        assertPointClose(Point(240.0, 0.0), mapped.topLeft, 0.001)
        assertPointClose(Point(480.0, 0.0), mapped.topRight, 0.001)
        assertPointClose(Point(480.0, 320.0), mapped.bottomRight, 0.001)
        assertPointClose(Point(240.0, 320.0), mapped.bottomLeft, 0.001)
    }

    @Test
    fun `portrait rotation with a portrait preview crops the landscape frame`() {
        // Typical phone canvas: 1080x1920 (9:16), landscape analysis frame 4:3.
        val quad = Quad(
            Point(70.0, 30.0), Point(186.0, 30.0), Point(186.0, 170.0), Point(70.0, 170.0)
        ) // analysis px: 175..465 x 56.25..318.75 after un-squish
        val preview = ImageSize(1080.0, 1920.0)

        val mapped = mapAnalysisQuadToPreview(quad, maskSize, analysisSize, preview, 90)

        // Expected: un-squish -> (175,56.25)-(465,318.75); rotate 90 CW ->
        // (423.75,175),(423.75,465),(161.25,465),(161.25,175), re-ordered as
        // TL(161.25,175); scale = max(1080/480, 1920/640) = 3; offsetX = -180.
        val expected = Quad(
            Point(161.25 * 3.0 - 180.0, 175.0 * 3.0),
            Point(423.75 * 3.0 - 180.0, 175.0 * 3.0),
            Point(423.75 * 3.0 - 180.0, 465.0 * 3.0),
            Point(161.25 * 3.0 - 180.0, 465.0 * 3.0),
        )
        assertPointClose(expected.topLeft, mapped.topLeft, 0.001)
        assertPointClose(expected.topRight, mapped.topRight, 0.001)
        assertPointClose(expected.bottomRight, mapped.bottomRight, 0.001)
        assertPointClose(expected.bottomLeft, mapped.bottomLeft, 0.001)
    }

    @Test
    fun `landscape rotation zero center-crops vertically on a wide preview`() {
        // Wide preview (e.g. 2340x1080): the 4:3 frame is cropped vertically.
        val quad = Quad(Point(0.0, 0.0), Point(256.0, 0.0), Point(256.0, 256.0), Point(0.0, 256.0))
        val preview = ImageSize(2340.0, 1080.0)

        val mapped = mapAnalysisQuadToPreview(quad, maskSize, analysisSize, preview, 0)

        // scale = max(2340/640, 1080/480) = 3.65625; offsetY = (1080 - 480*scale)/2 = -337.5
        val scale = 2340.0 / 640.0
        val offsetY = (1080.0 - 480.0 * scale) / 2.0
        assertPointClose(Point(0.0, offsetY), mapped.topLeft, 0.001)
        assertPointClose(Point(2340.0, offsetY), mapped.topRight, 0.001)
        assertPointClose(Point(2340.0, 1080.0 - offsetY), mapped.bottomRight, 0.001)
        assertPointClose(Point(0.0, 1080.0 - offsetY), mapped.bottomLeft, 0.001)
    }

    @Test
    fun `rotation does not mirror left and right`() {
        // Two markers: left half and right half of the frame.
        val left = Quad(Point(0.0, 0.0), Point(64.0, 0.0), Point(64.0, 128.0), Point(0.0, 128.0))
        val right = Quad(Point(192.0, 0.0), Point(256.0, 0.0), Point(256.0, 128.0), Point(192.0, 128.0))
        val preview = ImageSize(480.0, 640.0)

        val mappedLeft = mapAnalysisQuadToPreview(left, maskSize, analysisSize, preview, 90)
        val mappedRight = mapAnalysisQuadToPreview(right, maskSize, analysisSize, preview, 90)

        // 90 deg clockwise sends the left side of the landscape frame to the
        // TOP of the portrait preview and the right side to the BOTTOM. A
        // mirroring bug would swap them.
        assertTrue(
            mappedLeft.topLeft.y < mappedRight.topLeft.y,
            "left must map above right (left=${mappedLeft.topLeft}, right=${mappedRight.topLeft})"
        )
        assertTrue(
            mappedLeft.topLeft.x < mappedLeft.topRight.x,
            "left region must not be mirrored horizontally"
        )
    }

    @Test
    fun `centered quad stays centered for all rotations`() {
        // Centered document: analysis (240,160)-(400,320) -> mask (96,85.3)-(160,170.7)
        val quad = Quad(
            Point(96.0, 85.33), Point(160.0, 85.33), Point(160.0, 170.67), Point(96.0, 170.67)
        )
        val preview = ImageSize(1080.0, 1920.0)

        for (rotation in listOf(0, 90, 180, 270)) {
            val mapped = mapAnalysisQuadToPreview(quad, maskSize, analysisSize, preview, rotation)
            val cx = (mapped.topLeft.x + mapped.topRight.x + mapped.bottomRight.x + mapped.bottomLeft.x) / 4.0
            val cy = (mapped.topLeft.y + mapped.topRight.y + mapped.bottomRight.y + mapped.bottomLeft.y) / 4.0
            assertEquals(540.0, cx, 0.5, "rotation=$rotation center x")
            assertEquals(960.0, cy, 0.5, "rotation=$rotation center y")
        }
    }

    @Test
    fun `mapping is independent of preview aspect ratio`() {
        // The same centered document must stay centered in both aspect ratios.
        val quad = Quad(
            Point(96.0, 85.33), Point(160.0, 85.33), Point(160.0, 170.67), Point(96.0, 170.67)
        )
        for (preview in listOf(ImageSize(1080.0, 1920.0), ImageSize(2340.0, 1080.0))) {
            val mapped = mapAnalysisQuadToPreview(quad, maskSize, analysisSize, preview, 90)
            val cx = (mapped.topLeft.x + mapped.topRight.x + mapped.bottomRight.x + mapped.bottomLeft.x) / 4.0
            val cy = (mapped.topLeft.y + mapped.topRight.y + mapped.bottomRight.y + mapped.bottomLeft.y) / 4.0
            assertTrue(abs(cx - preview.width / 2.0) <= 0.5, "preview=$preview cx=$cx")
            assertTrue(abs(cy - preview.height / 2.0) <= 0.5, "preview=$preview cy=$cy")
        }
    }
}

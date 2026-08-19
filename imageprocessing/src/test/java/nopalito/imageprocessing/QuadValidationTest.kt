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

class QuadValidationTest {

    private val frameWidth = 320.0
    private val frameHeight = 240.0

    private val validQuad = Quad(
        Point(60.0, 40.0),
        Point(260.0, 40.0),
        Point(260.0, 200.0),
        Point(60.0, 200.0),
    )

    private fun shifted(quad: Quad, dx: Double, dy: Double) = Quad(
        Point(quad.topLeft.x + dx, quad.topLeft.y + dy),
        Point(quad.topRight.x + dx, quad.topRight.y + dy),
        Point(quad.bottomRight.x + dx, quad.bottomRight.y + dy),
        Point(quad.bottomLeft.x + dx, quad.bottomLeft.y + dy),
    )

    @Test
    fun `a valid document quad passes`() {
        assertTrue(QuadValidator.isValid(validQuad, frameWidth, frameHeight))
    }

    @Test
    fun `a self-crossing bowtie fails`() {
        val bowtie = Quad(
            Point(60.0, 40.0),
            Point(260.0, 200.0),
            Point(260.0, 40.0),
            Point(60.0, 200.0),
        )
        assertFalse(QuadValidator.isValid(bowtie, frameWidth, frameHeight))
    }

    @Test
    fun `a quad smaller than the minimum area fails`() {
        val tiny = Quad(
            Point(158.0, 118.0),
            Point(162.0, 118.0),
            Point(162.0, 122.0),
            Point(158.0, 122.0),
        )
        assertFalse(QuadValidator.isValid(tiny, frameWidth, frameHeight))
    }

    @Test
    fun `a quad covering almost the whole frame fails`() {
        val huge = Quad(
            Point(1.0, 1.0),
            Point(319.0, 1.0),
            Point(319.0, 239.0),
            Point(1.0, 239.0),
        )
        assertFalse(QuadValidator.isValid(huge, frameWidth, frameHeight))
    }

    @Test
    fun `corners far outside the frame fail`() {
        assertFalse(QuadValidator.isValid(shifted(validQuad, -100.0, 0.0), frameWidth, frameHeight))
    }

    @Test
    fun `corners slightly outside the frame still pass`() {
        assertTrue(QuadValidator.isValid(shifted(validQuad, -4.0, -4.0), frameWidth, frameHeight))
    }

    @Test
    fun `impossible movement between frames fails`() {
        val previous = shifted(validQuad, 0.0, 0.0)
        val current = shifted(validQuad, 200.0, 0.0)
        assertFalse(
            QuadValidator.isValid(current, frameWidth, frameHeight, previousQuad = previous)
        )
    }

    @Test
    fun `small movement between frames passes`() {
        val previous = shifted(validQuad, 0.0, 0.0)
        val current = shifted(validQuad, 8.0, 4.0)
        assertTrue(
            QuadValidator.isValid(current, frameWidth, frameHeight, previousQuad = previous)
        )
    }

    @Test
    fun `quad area matches the shoelace formula`() {
        assertEqualsTol(200.0 * 160.0, QuadValidator.quadArea(validQuad), 0.001)
    }

    private fun assertEqualsTol(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected $expected ± $tolerance, got $actual"
        )
    }
}

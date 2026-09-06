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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuadOrderingTest {

    private val topLeft = Point(80.0, 60.0)
    private val topRight = Point(240.0, 50.0)
    private val bottomRight = Point(260.0, 190.0)
    private val bottomLeft = Point(60.0, 180.0)

    @Test
    fun `corners are ordered top-left top-right bottom-right bottom-left`() {
        val ordered = createQuad(listOf(bottomRight, topLeft, bottomLeft, topRight))
        assertEquals(
            Quad(topLeft, topRight, bottomRight, bottomLeft),
            ordered
        )
    }

    @Test
    fun `ordering works for any rotation of the input list`() {
        val expected = Quad(topLeft, topRight, bottomRight, bottomLeft)
        assertEquals(expected, createQuad(listOf(topLeft, topRight, bottomRight, bottomLeft)))
        assertEquals(expected, createQuad(listOf(topRight, bottomRight, bottomLeft, topLeft)))
        assertEquals(expected, createQuad(listOf(bottomLeft, topLeft, topRight, bottomRight)))
    }

    @Test
    fun `a valid ordered quad is convex`() {
        assertTrue(Quad(topLeft, topRight, bottomRight, bottomLeft).isConvex())
    }

    @Test
    fun `a crossed bowtie quad is not convex`() {
        val bowtie = Quad(
            Point(60.0, 40.0),
            Point(260.0, 200.0),
            Point(260.0, 40.0),
            Point(60.0, 200.0),
        )
        assertFalse(bowtie.isConvex())
    }

    @Test
    fun `a degenerate collinear quad is not convex`() {
        val degenerate = Quad(
            Point(0.0, 0.0),
            Point(100.0, 0.0),
            Point(200.0, 0.0),
            Point(100.0, 100.0),
        )
        assertFalse(degenerate.isConvex())
    }
}

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuadSpringTest {

    private val frameWidth = 320.0
    private val frameHeight = 240.0
    private val dt = 1f / 60f

    private val quad = Quad(
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

    private fun distance(a: Quad, b: Quad): Double = maxOf(
        norm(a.topLeft, b.topLeft),
        norm(a.topRight, b.topRight),
        norm(a.bottomRight, b.bottomRight),
        norm(a.bottomLeft, b.bottomLeft),
    )

    @Test
    fun `returns null while no target was ever set`() {
        val spring = QuadSpring()
        assertNull(spring.update(null, dt, frameWidth, frameHeight))
    }

    @Test
    fun `first target is adopted instantly`() {
        val spring = QuadSpring()
        assertEquals(quad, spring.update(quad, dt, frameWidth, frameHeight))
    }

    @Test
    fun `converges to a nearby target without jumping`() {
        val spring = QuadSpring()
        spring.update(quad, dt, frameWidth, frameHeight)

        val target = shifted(quad, 6.0, 4.0)
        var displayed = spring.update(target, dt, frameWidth, frameHeight)!!

        // First step moves partway: no teleport, no standstill.
        val afterFirstStep = distance(displayed, quad)
        val gap = distance(target, quad)
        assertTrue(afterFirstStep > 0.0, "spring must move towards the target")
        assertTrue(afterFirstStep < gap, "spring must not overshoot the whole gap in one step")

        repeat(120) {
            displayed = spring.update(target, dt, frameWidth, frameHeight)!!
        }
        assertTrue(
            distance(displayed, target) < 0.5,
            "spring should converge, residual=${distance(displayed, target)}"
        )
    }

    @Test
    fun `far target snaps immediately`() {
        val spring = QuadSpring()
        spring.update(quad, dt, frameWidth, frameHeight)

        val far = shifted(quad, 200.0, 150.0)
        assertEquals(far, spring.update(far, dt, frameWidth, frameHeight))
    }

    @Test
    fun `null target holds the last position for the fade-out`() {
        val spring = QuadSpring()
        spring.update(quad, dt, frameWidth, frameHeight)
        assertEquals(quad, spring.update(null, dt, frameWidth, frameHeight))
        assertEquals(quad, spring.update(null, dt, frameWidth, frameHeight))
    }

    @Test
    fun `reset clears the state`() {
        val spring = QuadSpring()
        spring.update(quad, dt, frameWidth, frameHeight)
        spring.reset()
        assertNull(spring.update(null, dt, frameWidth, frameHeight))
    }

    @Test
    fun `reappearing target after loss is adopted instantly`() {
        val spring = QuadSpring()
        spring.update(quad, dt, frameWidth, frameHeight)
        spring.update(null, dt, frameWidth, frameHeight)
        spring.reset()

        val reappeared = shifted(quad, 50.0, 30.0)
        assertEquals(reappeared, spring.update(reappeared, dt, frameWidth, frameHeight))
    }
}

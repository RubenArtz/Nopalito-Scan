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

class QuadStabilityMonitorTest {

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

    @Test
    fun `never fires on a single detection`() {
        val monitor = QuadStabilityMonitor()
        assertFalse(monitor.update(quad, nowMs = 0L))
    }

    @Test
    fun `becomes stable only after the required time and frames`() {
        val monitor = QuadStabilityMonitor()
        val stepMs = 30L
        var firstTriggerIndex = -1
        for (i in 0 until 80) {
            if (monitor.update(quad, nowMs = i * stepMs)) {
                firstTriggerIndex = i
                break
            }
        }
        assertTrue(firstTriggerIndex > 0, "monitor should eventually trigger")
        val elapsedMs = firstTriggerIndex * stepMs
        assertTrue(elapsedMs >= 1200, "triggered too early: ${elapsedMs}ms")
        assertTrue(firstTriggerIndex >= 6, "triggered without enough stable frames")
    }

    @Test
    fun `movement beyond the tolerance restarts the countdown`() {
        val monitor = QuadStabilityMonitor()
        val stepMs = 30L
        // Feed a stable quad for a while, then move it.
        for (i in 0 until 30) {
            assertFalse(monitor.update(quad, nowMs = i * stepMs))
        }
        val moved = shifted(quad, 10.0, 10.0)
        assertFalse(monitor.update(moved, nowMs = 30 * stepMs))

        // After the movement, a full stability window is required again.
        var triggers = 0
        var t = 30 * stepMs
        for (i in 0 until 80) {
            t += stepMs
            if (monitor.update(moved, nowMs = t)) triggers++
            if (triggers > 0) break
        }
        val settleTime = t - 30 * stepMs
        assertTrue(
            settleTime >= 1200,
            "stability should restart after movement, got ${settleTime}ms"
        )
    }

    @Test
    fun `small movement under the tolerance still counts as stable`() {
        val monitor = QuadStabilityMonitor()
        val stepMs = 30L
        // Center moves 2px per update: below the 3px tolerance.
        var t = 0L
        var current = quad
        var triggered = false
        for (i in 0 until 80) {
            current = shifted(current, 1.0, 1.0)
            if (monitor.update(current, nowMs = t)) {
                triggered = true
                break
            }
            t += stepMs
        }
        assertTrue(triggered, "small drift must not prevent stabilization")
    }

    @Test
    fun `losing the quad resets the monitor`() {
        val monitor = QuadStabilityMonitor()
        val stepMs = 30L
        for (i in 0 until 50) {
            monitor.update(quad, nowMs = i * stepMs)
        }
        assertFalse(monitor.update(null, nowMs = 50 * stepMs))

        // A fresh appearance starts a brand-new stability window.
        for (i in 0 until 39) {
            assertFalse(monitor.update(quad, nowMs = 60 * stepMs + i * stepMs))
        }
    }
}

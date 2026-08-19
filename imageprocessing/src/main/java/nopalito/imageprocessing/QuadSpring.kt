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

import kotlin.math.hypot

/**
 * Smooths a moving quadrilateral with a critically-damped spring per corner.
 *
 * The detected/tracked quad is the *target*; [update] integrates a
 * spring-damper system per corner component (stiffness/damping in s^-2 and
 * s^-1) and returns the *displayed* quad. This makes the overlay follow the
 * document quickly without vibrating, and avoids visible jumps when a fresh
 * detection corrects the corners slightly.
 *
 * Recovery behavior: the first target after a loss is adopted instantly, and
 * targets further than [snapDistanceRatio] of the frame diagonal are snapped
 * to, so the overlay re-acquires the document quickly after losing it.
 */
class QuadSpring(
    private val stiffness: Float = 180f,
    private val damping: Float = 25f,
    private val snapDistanceRatio: Float = 0.25f,
    private val maxDtSeconds: Float = 1f / 30f,
) {

    private var current: Quad? = null
    private val velocities = FloatArray(8)

    /**
     * Advances the spring by [dtSeconds] and returns the displayed quad.
     * A `null` target holds the last position (fading is handled by the UI).
     */
    fun update(target: Quad?, dtSeconds: Float, frameWidth: Double, frameHeight: Double): Quad? {
        if (target == null) return current
        val dt = dtSeconds.coerceIn(0f, maxDtSeconds)

        val cur = current
        if (cur == null) {
            current = target
            velocities.fill(0f)
            return target
        }

        val diagonal = hypot(frameWidth, frameHeight).toFloat()
        if (QuadValidator.maxCornerDistance(cur, target) > snapDistanceRatio * diagonal) {
            current = target
            velocities.fill(0f)
            return target
        }

        val values = toArray(cur)
        val targets = toArray(target)
        for (i in 0 until 8) {
            val acceleration = stiffness * (targets[i] - values[i]) - damping * velocities[i]
            velocities[i] += acceleration * dt
            values[i] += velocities[i] * dt
        }
        val updated = Quad(
            Point(values[0].toDouble(), values[1].toDouble()),
            Point(values[2].toDouble(), values[3].toDouble()),
            Point(values[4].toDouble(), values[5].toDouble()),
            Point(values[6].toDouble(), values[7].toDouble()),
        )
        current = updated
        return updated
    }

    fun reset() {
        current = null
        velocities.fill(0f)
    }

    private fun toArray(quad: Quad): FloatArray = floatArrayOf(
        quad.topLeft.x.toFloat(), quad.topLeft.y.toFloat(),
        quad.topRight.x.toFloat(), quad.topRight.y.toFloat(),
        quad.bottomRight.x.toFloat(), quad.bottomRight.y.toFloat(),
        quad.bottomLeft.x.toFloat(), quad.bottomLeft.y.toFloat(),
    )
}

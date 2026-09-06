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

/**
 * Decides when a document is stable enough for auto-capture.
 *
 * Auto-capture never fires on a single detection: the quad must be present
 * for at least [minStableDurationMs] while its center moves less than
 * [maxCenterMovementPx] between updates, and [requiredStableFrames]
 * consecutive updates must satisfy that condition. Any movement or loss of
 * the quad restarts the countdown.
 */
class QuadStabilityMonitor(
    private val minStableDurationMs: Long = 1200L,
    private val requiredStableFrames: Int = 5,
    private val maxCenterMovementPx: Double = 3.0,
) {

    private var previousQuadWasNull = true
    private var stableSinceMs = 0L
    private var stableFrameCount = 0
    private var lastCenter: Point? = null

    /**
     * Feeds the current quad (displayed coordinates) and the current time.
     * Returns `true` exactly when the stability criteria are met and a capture
     * may be triggered.
     */
    fun update(quad: Quad?, nowMs: Long): Boolean {
        if (quad == null) {
            reset()
            return false
        }

        if (previousQuadWasNull) {
            previousQuadWasNull = false
            stableSinceMs = nowMs
            stableFrameCount = 0
            lastCenter = center(quad)
            return false
        }

        val currentCenter = center(quad)
        val prevCenter = lastCenter
        val moved = prevCenter == null || norm(currentCenter, prevCenter) > maxCenterMovementPx
        if (moved) {
            stableFrameCount = 0
            stableSinceMs = nowMs
        } else {
            stableFrameCount++
        }
        lastCenter = currentCenter

        return nowMs - stableSinceMs >= minStableDurationMs && stableFrameCount >= requiredStableFrames
    }

    fun reset() {
        previousQuadWasNull = true
        stableSinceMs = 0L
        stableFrameCount = 0
        lastCenter = null
    }

    private fun center(quad: Quad): Point = Point(
        (quad.topLeft.x + quad.topRight.x + quad.bottomRight.x + quad.bottomLeft.x) / 4.0,
        (quad.topLeft.y + quad.topRight.y + quad.bottomRight.y + quad.bottomLeft.y) / 4.0,
    )
}

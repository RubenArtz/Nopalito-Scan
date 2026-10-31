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

package nopalito.app.ui.screens.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrDetectionTrackerTest {

    private fun square(cx: Float, cy: Float, half: Float = 0.1f): List<QrPoint> = listOf(
        QrPoint(cx - half, cy - half),
        QrPoint(cx + half, cy - half),
        QrPoint(cx + half, cy + half),
        QrPoint(cx - half, cy + half),
    )

    private fun candidate(
        content: String,
        cx: Float = 0.5f,
        cy: Float = 0.5f,
        type: QrDetected.Type = QrDetected.Type.Text,
    ) = QrCandidate(content, "QR Code", type, square(cx, cy))

    private fun frame(
        vararg candidates: QrCandidate,
        nowMs: Long,
    ) = QrFrame(
        candidates = candidates.toList(),
        frameWidth = 640,
        frameHeight = 480,
        rotationDegrees = 0,
        timestampMs = nowMs,
        isStill = false,
    )

    @Test
    fun primarySurvivesShortDetectionLoss() {
        val tracker = QrDetectionTracker()
        val first = tracker.update(frame(candidate("hello"), nowMs = 1_000L), 1_000L)
        assertNotNull(first.primary)
        // No new frame: ticker-style snapshot inside the popup grace period keeps primary.
        val kept = tracker.snapshot(1_000L + 500L)
        assertNotNull(kept.primary)
        assertEquals("hello", kept.primary?.content)
    }

    @Test
    fun tracksAreRemovedAfterGracePeriod() {
        val tracker = QrDetectionTracker()
        tracker.update(frame(candidate("gone"), nowMs = 0L), 0L)
        val after = tracker.snapshot(2_500L)
        assertTrue(after.tracks.isEmpty())
        assertNull(after.primary)
    }

    @Test
    fun prefersCenteredStableCode() {
        val tracker = QrDetectionTracker()
        var now = 0L
        repeat(10) {
            now += 50L
            tracker.update(
                frame(
                    candidate("edge", cx = 0.1f, cy = 0.1f),
                    candidate("center", cx = 0.5f, cy = 0.5f),
                    nowMs = now,
                ),
                now,
            )
        }
        val state = tracker.snapshot(now)
        assertEquals("center", state.primary?.content)
        assertEquals(2, state.freshTrackCount)
    }

    @Test
    fun movingCodeKeepsSameTrackId() {
        val tracker = QrDetectionTracker()
        val first = tracker.update(frame(candidate("move", cx = 0.5f, cy = 0.5f), nowMs = 0L), 0L)
        val id = first.tracks.single().id
        val second = tracker.update(frame(candidate("move", cx = 0.52f, cy = 0.5f), nowMs = 100L), 100L)
        assertEquals(id, second.tracks.single().id)
        // EMA smoothing: the stored center moves toward the new position but not all the way.
        assertTrue(second.tracks.single().corners.isNotEmpty())
    }

    @Test
    fun cornersAreSmoothedNotJumping() {
        val tracker = QrDetectionTracker(overlayHoldMs = 350L, popupHoldMs = 850L, removalMs = 1_000L, smoothing = 0.28f)
        tracker.update(frame(candidate("smooth", cx = 0.5f, cy = 0.5f), nowMs = 0L), 0L)
        val before = tracker.snapshot(0L).tracks.single().corners.first()
        tracker.update(frame(candidate("smooth", cx = 0.6f, cy = 0.5f), nowMs = 100L), 100L)
        val after = tracker.snapshot(100L).tracks.single().corners.first()
        // New corner would be at 0.5; smoothed value must be between old and new.
        assertTrue(after.x > before.x)
        assertTrue(after.x < 0.5f)
    }

    @Test
    fun freshTracksExposeOneEntryPerCode() {
        val tracker = QrDetectionTracker()
        val state = tracker.update(
            frame(
                candidate("a", cx = 0.3f, cy = 0.5f),
                candidate("b", cx = 0.5f, cy = 0.5f),
                candidate("c", cx = 0.7f, cy = 0.5f),
                nowMs = 0L,
            ),
            0L,
        )
        assertEquals(3, state.freshTracks.size)
        assertEquals(3, state.freshTrackCount)
        assertTrue(state.isCrowded)
        // Primary stays on top for the popup stack.
        assertTrue(state.freshTracks.first().isPrimary)
    }

    @Test
    fun twoCodesAreNotCrowded() {
        val tracker = QrDetectionTracker()
        val state = tracker.update(
            frame(
                candidate("a", cx = 0.4f, cy = 0.5f),
                candidate("b", cx = 0.6f, cy = 0.5f),
                nowMs = 0L,
            ),
            0L,
        )
        assertEquals(2, state.freshTracks.size)
        assertTrue(!state.isCrowded)
    }

    @Test
    fun popupOrderIsStableAcrossFrames() {
        val tracker = QrDetectionTracker()
        val first = tracker.update(
            frame(
                candidate("a", cx = 0.4f, cy = 0.5f),
                candidate("b", cx = 0.6f, cy = 0.5f),
                nowMs = 0L,
            ),
            0L,
        )
        val order = first.freshTracks.map { it.content }
        // Same detections one frame later: same order, no slot swapping.
        val second = tracker.update(
            frame(
                candidate("a", cx = 0.41f, cy = 0.5f),
                candidate("b", cx = 0.59f, cy = 0.5f),
                nowMs = 100L,
            ),
            100L,
        )
        assertEquals(order, second.freshTracks.map { it.content })
    }

    @Test
    fun nearbyDifferentContentCreatesNewTrack() {        val tracker = QrDetectionTracker()
        val first = tracker.update(
            frame(
                candidate("a", cx = 0.30f, cy = 0.5f),
                candidate("b", cx = 0.70f, cy = 0.5f),
                nowMs = 0L,
            ),
            0L,
        )
        val idA = first.tracks.first { it.content == "a" }.id
        val idB = first.tracks.first { it.content == "b" }.id
        // "a" disappears; a NEW content "c" appears near a's old spot.
        // It must not hijack a's (or b's) track: new content, new track.
        val second = tracker.update(
            frame(candidate("c", cx = 0.32f, cy = 0.5f), nowMs = 100L),
            100L,
        )
        val trackC = second.tracks.first { it.content == "c" }
        assertTrue(trackC.id != idA)
        assertTrue(trackC.id != idB)
        // a's stale track keeps its old corners instead of jumping to c.
        val staleA = second.tracks.first { it.id == idA }
        assertEquals(0.30f, staleA.centerX, 0.001f)
    }

    @Test
    fun primaryDoesNotFlickerBetweenCloseCodes() {
        val tracker = QrDetectionTracker()
        var state = tracker.update(
            frame(
                candidate("a", cx = 0.45f, cy = 0.5f),
                candidate("b", cx = 0.55f, cy = 0.5f),
                nowMs = 0L,
            ),
            0L,
        )
        val firstPrimary = state.primary?.content
        // Tiny jitter around the same positions must not steal primary.
        repeat(5) { i ->
            val now = 100L * (i + 1)
            state = tracker.update(
                frame(
                    candidate("a", cx = 0.45f, cy = 0.5f),
                    candidate("b", cx = 0.55f, cy = 0.5f),
                    nowMs = now,
                ),
                now,
            )
        }
        assertEquals(firstPrimary, state.primary?.content)
    }

    @Test
    fun predictionGlidesBetweenSlowAnalyses() {
        val tracker = QrDetectionTracker()
        tracker.update(frame(candidate("a", cx = 0.40f, cy = 0.5f), nowMs = 0L), 0L)
        // Steady motion to the right over 300 ms: velocity ≈ +0.1/300ms.
        tracker.update(frame(candidate("a", cx = 0.50f, cy = 0.5f), nowMs = 300L), 300L)
        val atUpdate = tracker.snapshot(300L).tracks.single().corners.first().x
        // 200 ms later with no new frame: prediction must have carried the
        // corners forward instead of freezing them.
        val predicted = tracker.snapshot(500L).tracks.single().corners.first().x
        assertTrue(predicted > atUpdate)
    }

    @Test
    fun predictionStaysPutWithoutMotion() {
        val tracker = QrDetectionTracker()
        tracker.update(frame(candidate("a", cx = 0.40f, cy = 0.5f), nowMs = 0L), 0L)
        val still = tracker.snapshot(900L).tracks.single().corners.first().x
        // Single sighting: velocity is zero, no drift while fading.
        assertEquals(0.30f, still, 0.001f)
    }

    @Test
    fun predictionIsCappedForStaleTracks() {
        val tracker = QrDetectionTracker()
        tracker.update(frame(candidate("a", cx = 0.40f, cy = 0.5f), nowMs = 0L), 0L)
        tracker.update(frame(candidate("a", cx = 0.50f, cy = 0.5f), nowMs = 100L), 100L)
        val raw = tracker.snapshot(100L).tracks.single().corners.first().x
        // 850 ms later, still alive (removalMs = 1000): prediction active but
        // displacement bounded, never flying across the screen.
        val predicted = tracker.snapshot(950L).tracks.single().corners.first().x
        assertTrue(predicted > raw)
        assertTrue(predicted - raw <= 0.061f)
    }
}

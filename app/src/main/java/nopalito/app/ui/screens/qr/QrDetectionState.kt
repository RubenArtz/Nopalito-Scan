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

import kotlin.math.exp
import kotlin.math.hypot

/** A tracked code with smoothed, normalized corners in the upright analysis frame. */
data class QrTrack(
    val id: Long,
    val content: String,
    val format: String?,
    val type: QrDetected.Type,
    val corners: List<QrPoint>,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val seenCount: Int,
    val stability: Float,
    val centerX: Float,
    val centerY: Float,
    val area: Float,
    val isPrimary: Boolean = false,
)

/** Everything the live QR overlay and the compact popup need for one frame. */
data class QrDetectionState(
    val tracks: List<QrTrack> = emptyList(),
    val primary: QrTrack? = null,
    val freshTrackCount: Int = 0,
    val freshTracks: List<QrTrack> = emptyList(),
    val frameWidth: Int? = null,
    val frameHeight: Int? = null,
    val rotationDegrees: Int = 0,
    val updatedAtMs: Long = 0,
    val overlayHoldMs: Long = 350L,
    val popupHoldMs: Long = 850L,
    val isStill: Boolean = false,
) {
    val hasMultiple: Boolean get() = freshTrackCount > 1

    /** Three or more live codes: the frame is crowded, warn with yellow brackets. */
    val isCrowded: Boolean get() = freshTrackCount >= CROWDED_COUNT

    companion object {
        const val CROWDED_COUNT = 3
    }
}

/**
 * Debounces detections and keeps one stable primary code.
 *
 * - Overlay tracks survive [overlayHoldMs] of detection loss and fade out
 *   during the remaining time before [removalMs].
 * - The popup keeps the last primary code for [popupHoldMs], so a brief
 *   detection miss never makes the UI flicker.
 * - Corners are smoothed with an exponential moving average, which removes
 *   the per-frame jitter from ML Kit without hiding real movement.
 * - Between analyses (slow with many codes: ML Kit can take ~1 s for 10),
 *   displayed corners are extrapolated from per-corner velocity with decay,
 *   so brackets glide instead of freezing-then-jumping. Matching always uses
 *   the raw stored corners, never the prediction.
 * - Matching is same-content only: a new content never hijacks a nearby
 *   track, so brackets can't teleport between codes.
 */
class QrDetectionTracker(
    private val overlayHoldMs: Long = 350L,
    private val popupHoldMs: Long = 850L,
    private val removalMs: Long = 1_000L,
    private val smoothing: Float = 0.28f,
    private val matchDistance: Float = 0.35f,
) {
    private data class MutableTrack(
        val id: Long,
        val content: String,
        var format: String?,
        var type: QrDetected.Type,
        var corners: List<QrPoint>,
        var firstSeenMs: Long,
        var lastSeenMs: Long,
        var seenCount: Int,
        var stability: Float,
        var centerX: Float,
        var centerY: Float,
        var area: Float,
        /** Per-corner velocity in normalized units per ms (EMA, deadbanded). */
        var velocity: List<QrPoint> = List(4) { QrPoint(0f, 0f) },
    )

    private val tracks = mutableListOf<MutableTrack>()
    private var nextId = 1L
    private var primaryId: Long? = null
    private var frameWidth: Int? = null
    private var frameHeight: Int? = null
    private var rotationDegrees: Int = 0
    private var isStill: Boolean = false

    fun update(frame: QrFrame, nowMs: Long = frame.timestampMs): QrDetectionState {
        frameWidth = frame.frameWidth
        frameHeight = frame.frameHeight
        rotationDegrees = frame.rotationDegrees
        isStill = frame.isStill

        val candidates = frame.candidates.filter { it.content.isNotBlank() }
        val unmatched = tracks.toMutableList()

        for (candidate in candidates) {
            val center = candidate.center()
            // Same content only: never adopt a nearby code with different
            // content into an existing track. That adoption made brackets
            // teleport between codes (and showed stale popup text on a new
            // position) whenever ML Kit missed a code for a frame. A new
            // content always starts a new track with its entrance pop; stale
            // tracks fade out on their own grace period.
            val match = unmatched
                .filter { it.content == candidate.content }
                .minByOrNull { it.centerDistanceTo(center) }
                ?.takeIf { it.centerDistanceTo(center) <= matchDistance }

            if (match == null) {
                val newTrack = MutableTrack(
                    id = nextId++,
                    content = candidate.content,
                    format = candidate.format,
                    type = candidate.type,
                    corners = candidate.corners,
                    firstSeenMs = nowMs,
                    lastSeenMs = nowMs,
                    seenCount = 1,
                    stability = 0.55f,
                    centerX = center.first,
                    centerY = center.second,
                    area = candidate.area(),
                )
                tracks += newTrack
            } else {
                unmatched.remove(match)
                val movement = match.averageCornerDistance(candidate.corners)
                val dtMs = (nowMs - match.lastSeenMs).coerceAtLeast(1L).toFloat()
                // Track per-corner velocity so the UI can glide between slow
                // analyses. Instantaneous velocity below the deadband is ML Kit
                // jitter, not motion: bleed velocity off instead of learning it.
                if (candidate.corners.size == 4 && match.corners.size == 4) {
                    if (movement < VELOCITY_DEADBAND) {
                        match.velocity = match.velocity.map { v ->
                            QrPoint(v.x * 0.5f, v.y * 0.5f)
                        }
                    } else {
                        match.velocity = match.corners.mapIndexed { i, old ->
                            val new = candidate.corners[i]
                            val prev = match.velocity.getOrElse(i) { QrPoint(0f, 0f) }
                            val instX = (new.x - old.x) / dtMs
                            val instY = (new.y - old.y) / dtMs
                            QrPoint(
                                prev.x + (instX - prev.x) * VELOCITY_ALPHA,
                                prev.y + (instY - prev.y) * VELOCITY_ALPHA,
                            )
                        }
                    }
                }
                match.corners = if (candidate.corners.size == 4 && match.corners.size == 4) {
                    match.corners.zip(candidate.corners) { old, new ->
                        QrPoint(
                            x = old.x + (new.x - old.x) * smoothing,
                            y = old.y + (new.y - old.y) * smoothing,
                        )
                    }
                } else {
                    candidate.corners
                }
                match.format = candidate.format
                match.type = candidate.type
                match.lastSeenMs = nowMs
                match.seenCount += 1
                match.stability = (
                        match.stability * 0.7f +
                                (1f - (movement * 6f).coerceIn(0f, 1f)) * 0.3f
                        ).coerceIn(0f, 1f)
                match.centerX = center.first
                match.centerY = center.second
                match.area = candidate.area()
            }
        }

        tracks.removeAll { nowMs - it.lastSeenMs > removalMs }
        return snapshot(nowMs)
    }

    /** Recomputes age-dependent fields without a new camera frame (UI ticker). */
    fun snapshot(nowMs: Long): QrDetectionState {
        tracks.removeAll { nowMs - it.lastSeenMs > removalMs }
        val fresh = tracks.filter { nowMs - it.lastSeenMs <= popupHoldMs }
        // Sticky primary: the incumbent keeps its slot until a challenger beats
        // it by a clear margin, so the popup stack never reorders every frame
        // when several codes score almost the same.
        val scores = fresh.associateWith { score(it, nowMs) }
        val best = scores.maxByOrNull { it.value }?.key
        val incumbent = fresh.firstOrNull { it.id == primaryId }
        val newPrimary = when {
            best == null -> null
            incumbent == null -> best
            best.id == incumbent.id -> best
            else -> {
                val bestScore = scores.getValue(best)
                val incumbentScore = scores.getValue(incumbent)
                if (bestScore > incumbentScore + PRIMARY_SWITCH_MARGIN) best else incumbent
            }
        }
        primaryId = newPrimary?.id
        val primary = newPrimary?.id

        val uiTracks = tracks.map { track ->
            track.toUiTrack(isPrimary = track.id == primary, nowMs = nowMs)
        }
        // Stable order: primary on top, the rest in detection order. Never
        // sort by a per-frame value (stability/center) or the popup stack
        // swaps slots and the texts look like they replace each other.
        val uiFresh = fresh
            .map { track -> track.toUiTrack(isPrimary = track.id == primary, nowMs = nowMs) }
            .sortedWith(compareByDescending<QrTrack> { it.isPrimary }.thenBy { it.firstSeenMs })
        return QrDetectionState(
            tracks = uiTracks,
            primary = uiTracks.firstOrNull { it.isPrimary },
            freshTrackCount = fresh.size,
            freshTracks = uiFresh,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            rotationDegrees = rotationDegrees,
            updatedAtMs = nowMs,
            overlayHoldMs = overlayHoldMs,
            popupHoldMs = popupHoldMs,
            isStill = isStill,
        )
    }

    fun reset() {
        tracks.clear()
        nextId = 1L
        primaryId = null
        frameWidth = null
        frameHeight = null
        rotationDegrees = 0
        isStill = false
    }

    private fun score(track: MutableTrack, nowMs: Long): Float {
        val centerDistance =
            hypot(track.centerX - 0.5f, track.centerY - 0.5f).coerceAtMost(MAX_CENTER_DISTANCE)
        val centerScore = 1f - centerDistance / MAX_CENTER_DISTANCE
        val areaScore = (track.area * 10f).coerceIn(0f, 1f)
        val ageScore = ((nowMs - track.firstSeenMs) / 1_000f).coerceIn(0f, 1f)
        return 0.50f * centerScore +
                0.25f * track.stability +
                0.15f * areaScore +
                0.10f * ageScore
    }

    private fun MutableTrack.toUiTrack(isPrimary: Boolean, nowMs: Long) = QrTrack(
        id = id,
        content = content,
        format = format,
        type = type,
        corners = predictedCorners(nowMs),
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
        seenCount = seenCount,
        stability = stability,
        centerX = centerX,
        centerY = centerY,
        area = area,
        isPrimary = isPrimary,
    )

    /**
     * Extrapolates corners from velocity so the overlay glides between slow
     * analyses. Decays with time since the last sighting and the displacement
     * is capped, so a stale track never flies across the screen.
     */
    private fun MutableTrack.predictedCorners(nowMs: Long): List<QrPoint> {
        if (corners.size != 4 || velocity.size != 4) return corners
        val dt = (nowMs - lastSeenMs).coerceAtLeast(0L).toFloat()
        if (dt <= 0f) return corners
        val decay = exp(-dt / VELOCITY_DECAY_MS).toFloat()
        return corners.mapIndexed { i, c ->
            val v = velocity[i]
            val dx = (v.x * dt * decay).coerceIn(-MAX_PREDICT, MAX_PREDICT)
            val dy = (v.y * dt * decay).coerceIn(-MAX_PREDICT, MAX_PREDICT)
            QrPoint(
                x = (c.x + dx).coerceIn(0f, 1f),
                y = (c.y + dy).coerceIn(0f, 1f),
            )
        }
    }

    private fun QrCandidate.center(): Pair<Float, Float> {
        if (corners.size != 4) return 0.5f to 0.5f
        return corners.sumOf { it.x.toDouble() }.toFloat() / 4f to
                corners.sumOf { it.y.toDouble() }.toFloat() / 4f
    }

    private fun QrCandidate.area(): Float {
        if (corners.size != 4) return 0.05f
        var area = 0f
        for (i in corners.indices) {
            val current = corners[i]
            val next = corners[(i + 1) % corners.size]
            area += current.x * next.y - next.x * current.y
        }
        return (kotlin.math.abs(area) / 2f).coerceIn(0f, 1f)
    }

    private fun MutableTrack.centerDistanceTo(center: Pair<Float, Float>): Float =
        hypot(centerX - center.first, centerY - center.second)

    private fun MutableTrack.averageCornerDistance(other: List<QrPoint>): Float {
        if (corners.size != 4 || other.size != 4) return 1f
        return corners.zip(other).sumOf { (a, b) ->
            hypot(a.x - b.x, a.y - b.y).toDouble()
        }.toFloat() / 4f
    }

    companion object {
        private const val MAX_CENTER_DISTANCE = 0.707f

        /** A challenger must beat the incumbent by this margin to steal primary. */
        private const val PRIMARY_SWITCH_MARGIN = 0.10f

        /** Per-frame corner movement below this is jitter, not motion. */
        private const val VELOCITY_DEADBAND = 0.004f

        /** EMA weight for corner velocity updates. */
        private const val VELOCITY_ALPHA = 0.35f

        /** Prediction decay horizon in ms; displacement vanishes past ~3x this. */
        private const val VELOCITY_DECAY_MS = 800f

        /** Hard cap on predicted drift per axis (normalized units). */
        private const val MAX_PREDICT = 0.06f
    }
}

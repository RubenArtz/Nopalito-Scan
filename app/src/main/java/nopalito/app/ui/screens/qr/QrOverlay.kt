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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * One UI style QR brackets: only the four corners of each detected code are
 * drawn, with a subtle dark shadow for contrast over any camera background.
 *
 * Coordinates are normalized to the upright analysis frame. [mapToPreview]
 * reproduces PreviewView's FILL_CENTER crop, so the brackets stay glued to the
 * code for every screen aspect ratio. Perspective is respected by drawing each
 * corner along its two real edges instead of using an axis-aligned rectangle.
 *
 * A single full-screen [Canvas] draws every track: one canvas per code would
 * multiply overdraw by N and kill the frame rate with many codes. Only the
 * [MAX_OVERLAY_TRACKS] most relevant tracks are drawn.
 */
@Composable
fun QrOverlay(
    state: QrDetectionState,
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
) {
    val frameWidth = state.frameWidth ?: return
    val frameHeight = state.frameHeight ?: return
    if (state.isStill) return
    if (state.tracks.isEmpty()) return

    // Primary first, then biggest: bounded work no matter how many codes.
    val visible = remember(state.tracks) {
        state.tracks
            .sortedWith(compareByDescending<QrTrack> { it.isPrimary }.thenByDescending { it.area })
            .take(MAX_OVERLAY_TRACKS)
    }
    if (visible.isEmpty()) return
    val anims = visible.map { track ->
        key(track.id) { rememberTrackAnim(track, state) }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        anims.forEach { anim ->
            drawTrackBracket(
                anim,
                frameWidth,
                frameHeight,
                mirrored,
                state.isCrowded
            )
        }
    }
}

/** Per-track animated values; the single overlay canvas reads them to draw. */
private data class TrackAnim(
    val track: QrTrack,
    val xs: List<State<Float>>,
    val ys: List<State<Float>>,
    val entrance: Animatable<Float, *>,
    val fade: State<Float>,
)

@Composable
private fun rememberTrackAnim(track: QrTrack, state: QrDetectionState): TrackAnim {
    if (track.corners.size != 4) {
        return TrackAnim(
            track, emptyList(), emptyList(), remember(track.id) { Animatable(0f) },
            remember { mutableStateOf(0f) })
    }
    // Tracker ticks at ~10 Hz; springs interpolate to display rate with a soft
    // bounce so the brackets glide instead of jumping between frames.
    val points = track.corners.map { corner -> animatedPoint(corner) }
    val entrance = remember(track.id) { Animatable(0f) }
    LaunchedEffect(track.id) {
        entrance.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }
    val fresh = state.updatedAtMs - track.lastSeenMs <= state.overlayHoldMs
    val fade = animateFloatAsState(
        targetValue = if (fresh) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "qrBracketFade",
    )
    return TrackAnim(
        track = track,
        xs = points.map { it.first },
        ys = points.map { it.second },
        entrance = entrance,
        fade = fade,
    )
}

private fun DrawScope.drawTrackBracket(
    anim: TrackAnim,
    frameWidth: Int,
    frameHeight: Int,
    mirrored: Boolean,
    crowded: Boolean,
) {
    val track = anim.track
    if (anim.xs.size != 4 || anim.ys.size != 4) return
    val rawAlpha = anim.fade.value * anim.entrance.value * if (track.isPrimary) 1f else 0.85f
    val alpha = rawAlpha.coerceIn(0f, 1f)
    if (alpha <= 0.01f) return

    // Entrance overshoots past 1 with the bouncy spring: reuse it as a
    // pop scale so new brackets spring outward instead of fading flat.
    val pop = (0.55f + 0.45f * anim.entrance.value).coerceIn(0.55f, 1.12f)
    // Crowded frame (3+ codes): every bracket turns warning-yellow so the
    // overload reads at a glance. Otherwise primary is white, secondaries
    // stay white but dimmer.
    val bracket = if (crowded) QrCrowdedYellow else Color.White

    val mapped = List(4) { i ->
        mapToPreview(anim.xs[i].value, anim.ys[i].value, frameWidth, frameHeight, mirrored)
    }
    val center = Offset(
        x = mapped.sumOf { it.x.toDouble() }.toFloat() / 4f,
        y = mapped.sumOf { it.y.toDouble() }.toFloat() / 4f,
    )
    val baseArm = minOf(
        (mapped[0] - mapped[1]).getDistance(),
        (mapped[1] - mapped[2]).getDistance(),
        (mapped[2] - mapped[3]).getDistance(),
        (mapped[3] - mapped[0]).getDistance(),
    ) * 0.30f
    val armLength = (baseArm * pop).coerceIn(9.dp.toPx(), 38.dp.toPx())
    val expansion = (3.dp.toPx() * pop).coerceAtLeast(1.5.dp.toPx())
    val strokeWidth = 3.dp.toPx()
    val shadowWidth = strokeWidth + 2.5.dp.toPx()

    for (i in 0..3) {
        val corner = mapped[i]
        val previous = mapped[(i + 3) % 4]
        val next = mapped[(i + 1) % 4]
        val outward = (corner - center).normalized()
        val origin = corner + outward * expansion
        val toPrevious = (previous - corner).normalized()
        val toNext = (next - corner).normalized()
        val edgeLimit = min(
            (previous - corner).getDistance(),
            (next - corner).getDistance(),
        ) * 0.46f
        val arm = min(armLength, edgeLimit)

        // Dark halo keeps the bracket readable on bright paper.
        drawLine(
            color = Color.Black.copy(alpha = 0.38f * alpha),
            start = origin,
            end = origin + toPrevious * arm,
            strokeWidth = shadowWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.38f * alpha),
            start = origin,
            end = origin + toNext * arm,
            strokeWidth = shadowWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = bracket.copy(alpha = alpha),
            start = origin,
            end = origin + toPrevious * arm,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = bracket.copy(alpha = alpha),
            start = origin,
            end = origin + toNext * arm,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun animatedPoint(point: QrPoint): Pair<State<Float>, State<Float>> {
    // Rebote fluido: el glide dura más que el intervalo entre análisis
    // (~300 ms con varios códigos), así el bracket viaja continuo en vez
    // de moverse-pararse-moverse. El trabajo pesado ya corre fuera del main
    // (parse con guard, tracker en Default, análisis pautados), así que el
    // redraw del canvas —solo líneas— no traba el teléfono.
    val x = animateFloatAsState(
        targetValue = point.x,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "qrCornerX",
    )
    val y = animateFloatAsState(
        targetValue = point.y,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "qrCornerY",
    )
    return x to y
}

/** Warning yellow used when 3+ codes crowd the frame. */
private val QrCrowdedYellow = Color(0xFFFFE14D)

/** Hard bound on drawn brackets: O(1) canvas work however many codes ML Kit finds. */
private const val MAX_OVERLAY_TRACKS = 12

private fun DrawScope.mapToPreview(
    x: Float,
    y: Float,
    frameWidth: Int,
    frameHeight: Int,
    mirrored: Boolean,
): Offset {
    val scale = max(size.width / frameWidth, size.height / frameHeight)
    val cropX = (frameWidth * scale - size.width) / 2f
    val cropY = (frameHeight * scale - size.height) / 2f
    var mappedX = x * frameWidth * scale - cropX
    if (mirrored) mappedX = size.width - mappedX
    return Offset(mappedX, y * frameHeight * scale - cropY)
}

private fun Offset.normalized(): Offset {
    val length = getDistance()
    return if (length < 0.001f) Offset.Zero else this / length
}

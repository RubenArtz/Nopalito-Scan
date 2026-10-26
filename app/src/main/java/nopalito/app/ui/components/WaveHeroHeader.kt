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

package nopalito.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.PI
import kotlin.math.sin

/**
 * Purple monochrome animated wave background (left -> right).
 *
 * Same wave language as the maintenance screen, but strictly purple: a light
 * purple region on top melts fluidly into a deep purple below, with the
 * sine-wave boundary traveling left -> right (mirrored vs. maintenance).
 * Used as the header background of the cloud files and trash screens.
 */
@Composable
fun PurpleWaveBackground(
    modifier: Modifier = Modifier,
    topColor: Color = Color(0xFF9B8CD4),
    bottomColor: Color = Color(0xFF453A6E),
    content: @Composable BoxScope.() -> Unit
) {
    val waveTransition = rememberInfiniteTransition(label = "purple_wave")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(4500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawRect(bottomColor)
            val baseY = h * 0.55f
            val amplitude = h * 0.08f
            val wavelength = w * 0.8f
            // Melt band: solid light purple up top, blending fluidly into
            // deep purple right at the wave edge.
            val blend = h * 0.30f
            drawPath(
                purpleWavePath(
                    width = w,
                    baseY = baseY + blend * 0.45f,
                    amplitude = amplitude,
                    wavelength = wavelength,
                    phase = wavePhase
                ),
                Brush.verticalGradient(
                    0f to topColor,
                    ((baseY - blend).coerceAtLeast(0f) / h) to topColor,
                    ((baseY + blend * 0.45f) / h) to bottomColor,
                    startY = 0f,
                    endY = h
                )
            )
        }
        content()
    }
}

/**
 * Purple wave hero header: same slots as [GradientHeroHeader] (back button,
 * white title + subtitle, white actions) over the animated purple wave.
 */
@Composable
fun PurpleWaveHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {}
) {
    PurpleWaveBackground(modifier = modifier) {
        HeroHeaderLayout(
            onBack = onBack,
            background = null,
            leading = {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            actions = actions,
        )
    }
}

/**
 * Top region path with a sine-wave bottom edge. Phase grows with time, so the
 * wave travels left -> right and loops seamlessly every 2*PI (mirrored vs.
 * the maintenance screen wave).
 */
private fun purpleWavePath(
    width: Float,
    baseY: Float,
    amplitude: Float,
    wavelength: Float,
    phase: Float,
    stepPx: Float = 8f
): Path = Path().apply {
    val k = (2 * PI).toFloat() / wavelength
    moveTo(0f, 0f)
    lineTo(width, 0f)
    var x = width
    while (x >= 0f) {
        lineTo(x, baseY + amplitude * sin(k * x - phase))
        x -= stepPx
    }
    lineTo(0f, 0f)
    close()
}
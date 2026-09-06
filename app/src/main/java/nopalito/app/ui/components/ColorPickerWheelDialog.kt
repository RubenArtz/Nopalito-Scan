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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nopalito.app.R
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HSV color picker dialog with a circular hue/saturation wheel and a value (brightness) slider.
 * Reusable across signature editors, date editors, etc.
 */
@Composable
fun ColorPickerWheelDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    var hsv by remember {
        mutableStateOf(floatArrayOf(0f, 0f, 0f).also { ColorToHSV(initialColor, it) })
    }
    val currentColor = Color.hsv(hsv[0], hsv[1], hsv[2])

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                stringResource(R.string.select_color),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Preview
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(currentColor)
                        .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Spacer(Modifier.height(12.dp))

                // Hue-Saturation wheel
                HueSaturationWheel(
                    hue = hsv[0],
                    saturation = hsv[1],
                    onHueSaturationChanged = { h, s ->
                        hsv = floatArrayOf(h, s, hsv[2])
                    },
                    modifier = Modifier.size(220.dp),
                )
                Spacer(Modifier.height(12.dp))

                // Value slider
                Text(
                    stringResource(R.string.brightness),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                // Gradient from black to full color
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Black, Color.hsv(hsv[0], hsv[1], 1f))
                            )
                        )
                        .pointerInput(Unit) {
                            // Ponytail: single-finger drag on value bar
                        },
                )
                Slider(
                    value = hsv[2],
                    onValueChange = { hsv = floatArrayOf(hsv[0], hsv[1], it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = currentColor,
                        activeTrackColor = currentColor,
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(currentColor) },
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Draws a circular hue-saturation wheel and tracks finger position to set hue (angle) and saturation (radius).
 */
@Composable
private fun HueSaturationWheel(
    hue: Float,
    saturation: Float,
    onHueSaturationChanged: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // ponytail: using pointerInput with awaitPointerEventScope for continuous drag
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = minOf(cx, cy)

                    fun updateFromPosition(pos: Offset) {
                        val dx = pos.x - cx
                        val dy = pos.y - cy
                        val dist = sqrt(dx * dx + dy * dy).coerceAtMost(radius)
                        val angle = (atan2(dy, dx) * 180f / PI).toFloat()
                        val h = if (angle < 0) angle + 360f else angle
                        val s = dist / radius
                        onHueSaturationChanged(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f))
                    }

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (change.pressed) {
                                updateFromPosition(change.position)
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = minOf(cx, cy)

            // Draw the wheel pixel by pixel using concentric rings
            // ponytail: simplified using canvas drawCircle per hue sector — fast enough for <300dp
            val steps = 120
            for (i in 0 until steps) {
                val angle = i * 360f / steps
                // Draw radial gradient from center (white) to edge (full hue)
                val hueColor = Color.hsv(angle, 1f, 1f)
                drawArc(
                    color = hueColor,
                    startAngle = angle,
                    sweepAngle = 360f / steps + 0.5f,
                    useCenter = true,
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height),
                )
            }
            // Overlay: white center to transparent edge for saturation
            // Done by drawing concentric circles
            val satSteps = 60
            for (s in satSteps downTo 1) {
                val r = radius * s / satSteps
                val alpha = 1f - (s.toFloat() / satSteps)
                drawCircle(
                    color = Color.White.copy(alpha = alpha * 0.02f),
                    radius = r,
                    center = Offset(cx, cy),
                )
            }

            // Indicator dot
            val indicatorRad = saturation * radius
            val indicatorAngleRad = hue * PI.toFloat() / 180f
            val ix = cx + indicatorRad * cos(indicatorAngleRad)
            val iy = cy + indicatorRad * sin(indicatorAngleRad)
            drawCircle(Color.Black, radius = 8f, center = Offset(ix, iy))
            drawCircle(Color.White, radius = 6f, center = Offset(ix, iy))
            drawCircle(
                Color.hsv(hue, saturation, 1f),
                radius = 5f,
                center = Offset(ix, iy),
            )
        }
    }
}

private fun ColorToHSV(color: Color, hsv: FloatArray) {
    val r = (color.red * 255).toInt()
    val g = (color.green * 255).toInt()
    val b = (color.blue * 255).toInt()
    android.graphics.Color.RGBToHSV(r, g, b, hsv)
}
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

package nopalito.app.ui.screens.cloud.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * "Success" animation: a green circle springs in, a white checkmark draws
 * itself inside it, and the whole badge bounces gently. Optionally shows a
 * [message] below. Callers decide when to dismiss it (auto-dismiss or a button).
 */
@Composable
fun CloudSuccessAnimation(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val circleScale = remember { Animatable(0f) }
    val checkProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        circleScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            )
        )
        checkProgress.animateTo(1f, tween(durationMillis = 500, easing = FastOutSlowInEasing))
    }

    val bounce = rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "checkBounce",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer {
                    scaleX = circleScale.value
                    scaleY = circleScale.value
                    translationY = -bounce.value * 7.dp.toPx()
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(54.dp)) {
                val strokeWidth = 6.dp.toPx()
                val p1 = Offset(size.width * 0.22f, size.height * 0.52f)
                val p2 = Offset(size.width * 0.44f, size.height * 0.72f)
                val p3 = Offset(size.width * 0.80f, size.height * 0.28f)
                val first = (checkProgress.value.coerceAtMost(0.5f)) / 0.5f
                val second = ((checkProgress.value - 0.5f).coerceAtLeast(0f)) / 0.5f
                if (first > 0f) {
                    drawLine(
                        color = Color.White,
                        start = p1,
                        end = lerp(p1, p2, first),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
                if (second > 0f) {
                    drawLine(
                        color = Color.White,
                        start = p2,
                        end = lerp(p2, p3, second),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
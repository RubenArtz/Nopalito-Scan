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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nopalito.app.R

/**
 * Premium purchase success animation: elegant green check with fluid spring.
 * - Circle scales with bouncy spring (not harsh)
 * - Check draws progressively (two strokes)
 * - Soft shadow + gradient for modern feel
 * - Message fades in after check completes
 * Duration ~2s total: circle 400ms + check 500ms + hold 1100ms before auto-dismiss (caller controls dismiss)
 */
@Composable
fun BillingPurchaseSuccessAnimation(
    planName: String? = null,
    modifier: Modifier = Modifier
) {
    val circleScale = remember { Animatable(0f) }
    val checkProgress = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        circleScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
        checkProgress.animateTo(1f, tween(durationMillis = 520, easing = FastOutSlowInEasing))
        textAlpha.animateTo(1f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Soft shadow + gradient circle
        Box(
            modifier = Modifier
                .size(108.dp)
                .graphicsLayer {
                    scaleX = circleScale.value
                    scaleY = circleScale.value
                }
                .shadow(16.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF14C76B), // light elegant green
                            Color(0xFF0CAD55) // primary green
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // Inner highlight for premium feel
            Box(
                modifier = Modifier
                    .size(108.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.18f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Canvas(Modifier.size(56.dp)) {
                val strokeWidth = 6.5.dp.toPx()
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
                        cap = StrokeCap.Round
                    )
                }
                if (second > 0f) {
                    drawLine(
                        color = Color.White,
                        start = p2,
                        end = lerp(p2, p3, second),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Title with fade
        Column(
            modifier = Modifier.graphicsLayer { alpha = textAlpha.value },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.billing_purchase_success),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            val subtitle = when (planName?.uppercase()) {
                "PERSONAL" -> stringResource(R.string.premium_personal)
                "PLUS" -> stringResource(R.string.premium_plus)
                else -> planName ?: ""
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = stringResource(R.string.billing_purchase_success_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/**
 * Compact verifying overlay: centered card with progress + message.
 */
@Composable
fun BillingVerifyingOverlay(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.billing_purchase_processing),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.billing_purchase_processing_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
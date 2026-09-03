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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nopalito.app.R

@Composable
fun SignatureToolChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    height: Dp = 44.dp,
    containerColor: Color? = null,
    contentColor: Color? = null,
    iconTint: Color? = null,
) {
    val haptic = LocalHapticFeedback.current
    val bgColor by animateColorAsState(
        targetValue = if (selected) {
            containerColor ?: MaterialTheme.colorScheme.primaryContainer
        } else {
            containerColor ?: MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(200),
        label = "chipBg",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) {
            contentColor ?: MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "chipText",
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) {
            iconTint ?: (contentColor ?: MaterialTheme.colorScheme.onPrimaryContainer)
        } else {
            iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "chipIcon",
    )

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (enabled) iconColor else iconColor.copy(alpha = 0.3f),
            )
            if (text.isNotBlank()) Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = if (enabled) textColor else textColor.copy(alpha = 0.3f),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
fun SignatureSizeSelector(
    renderScale: Float,
    onScaleSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.signature_size),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${(renderScale * 100).toInt()}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = renderScale,
            onValueChange = onScaleSelected,
            valueRange = 0.5f..2.0f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
    }
}

@Composable
fun SignatureThicknessSelector(
    strokeWidth: Float,
    onThicknessSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.stroke_thickness),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${strokeWidth.toInt()}px",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
            )
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = strokeWidth,
            onValueChange = onThicknessSelected,
            valueRange = 1f..10f,
            colors = SliderDefaults.colors(
                thumbColor = primaryColor,
                activeTrackColor = primaryColor,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(8.dp),
            ) {
                drawLine(
                    color = primaryColor,
                    start = androidx.compose.ui.geometry.Offset(20f, 4f),
                    end = androidx.compose.ui.geometry.Offset(140f, 4f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
fun SignatureColorSelector(
    strokeColor: Color,
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = listOf(
        Color(0xFF1A1A1A),
        Color.Black,
        Color(0xFF2563EB),
        Color(0xFF7C3AED),
        Color(0xFFDC2626),
    )
    var showCustomPicker by remember { mutableStateOf(false) }
    // ponytail: rainbow gradient via Brush.sweepGradient — needs Brush import if upgraded
    val isCustom = colors.none { it == strokeColor }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.signature_color_label),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            colors.forEach { color ->
                val isSelected = strokeColor == color
                val borderColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            if (isSelected) 2.dp else 1.dp,
                            borderColor,
                            CircleShape,
                        )
                        .clickable { onColorSelected(color) },
                )
            }
            // Custom color button (rainbow palette)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (isCustom) strokeColor else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        if (isCustom) 2.dp else 1.dp,
                        if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
                    .clickable { showCustomPicker = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = stringResource(R.string.custom_color),
                    modifier = Modifier.size(16.dp),
                    tint = if (isCustom) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showCustomPicker) {
        ColorPickerWheelDialog(
            initialColor = strokeColor,
            onDismiss = { showCustomPicker = false },
            onConfirm = { color ->
                onColorSelected(color)
                showCustomPicker = false
            },
        )
    }
}
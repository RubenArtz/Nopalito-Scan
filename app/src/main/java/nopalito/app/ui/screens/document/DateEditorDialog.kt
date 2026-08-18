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

package nopalito.app.ui.screens.document

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nopalito.app.R
import nopalito.app.ui.components.ColorPickerWheelDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// ─────────────────────────────────────────────
// Date editor dialog — unified picker + style (premium)
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateEditorDialog(
    currentStyle: DateOverlayStyle,
    onDismiss: () -> Unit,
    onConfirm: (dateText: String, style: DateOverlayStyle) -> Unit,
) {
    // ── Date picker state ──
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis()
    )
    // ── Style state ──
    var textColor by remember { mutableStateOf(Color(currentStyle.textColor)) }
    var fontSize by remember { mutableFloatStateOf(currentStyle.fontSize) }
    var bgStyle by remember { mutableStateOf(currentStyle.backgroundStyle) }
    var bgColor by remember { mutableStateOf(Color(currentStyle.backgroundColor)) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.EditCalendar,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.date_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            // ── Live preview ──
            val previewDate = remember(datePickerState.selectedDateMillis) {
                val millis = datePickerState.selectedDateMillis ?: System.currentTimeMillis()
                val date = Instant.ofEpochMilli(millis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(6.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(14.dp)
                    )
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                val bgModifier = when (bgStyle) {
                    DateBackgroundStyle.NONE -> Modifier
                    DateBackgroundStyle.CAPSULE -> Modifier
                        .background(bgColor, RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 7.dp)

                    DateBackgroundStyle.RECTANGLE -> Modifier
                        .background(bgColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp)

                    DateBackgroundStyle.SOFT_RECTANGLE -> Modifier
                        .background(bgColor, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                }
                Text(
                    text = previewDate,
                    color = textColor,
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = bgModifier,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Date picker ──
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
            ) {
                DatePicker(
                    state = datePickerState,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = null,
                    headline = null,
                    showModeToggle = false,
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── Style section ──
            Text(
                text = stringResource(R.string.date_style_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.height(8.dp))

            // Text color chips
            var showTextColorPicker by remember { mutableStateOf(false) }
            val presetTextColors = listOf(
                Color(0xFF1A1A1A) to stringResource(R.string.color_black),
                Color(0xFF1A56DB) to stringResource(R.string.color_blue),
                Color(0xFFC81A1A) to stringResource(R.string.color_red),
                Color(0xFF047857) to stringResource(R.string.color_green),
                Color(0xFF6D28D9) to stringResource(R.string.color_purple),
                Color(0xFFB45309) to stringResource(R.string.color_amber),
            )
            val isCustomText = presetTextColors.none { it.first == textColor }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                presetTextColors.forEach { (color, _) ->
                    val selected = textColor == color
                    Surface(
                        onClick = { textColor = color },
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(
                            1.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                    ) {
                        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(color))
                    }
                }
                // Custom text color
                Surface(
                    onClick = { showTextColorPicker = true },
                    shape = CircleShape,
                    color = if (isCustomText) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    border = if (isCustomText) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(
                        1.5.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                ) {
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = stringResource(R.string.custom_color),
                            modifier = Modifier.size(16.dp),
                            tint = if (isCustomText) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (showTextColorPicker) {
                ColorPickerWheelDialog(
                    initialColor = textColor,
                    onDismiss = { showTextColorPicker = false },
                    onConfirm = { textColor = it; showTextColorPicker = false },
                )
            }

            Spacer(Modifier.height(12.dp))

            // Font size slider
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "A",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(18.dp)
                )
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    valueRange = 12f..48f,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                Text(
                    "A",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(22.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Background style chips
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val bgOptions = listOf(
                    DateBackgroundStyle.NONE to stringResource(R.string.bg_none),
                    DateBackgroundStyle.CAPSULE to stringResource(R.string.bg_capsule),
                    DateBackgroundStyle.RECTANGLE to stringResource(R.string.bg_rectangle),
                    DateBackgroundStyle.SOFT_RECTANGLE to stringResource(R.string.bg_soft),
                )
                bgOptions.forEach { (style, label) ->
                    FilterChip(
                        selected = bgStyle == style,
                        onClick = { bgStyle = style },
                        label = { Text(label, fontSize = 11.sp) },
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }

            // BG color chips (only when bg is active)
            AnimatedVisibility(visible = bgStyle != DateBackgroundStyle.NONE) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    var showBgColorPicker by remember { mutableStateOf(false) }
                    val presetBgColors = listOf(
                        Color(0xFFFFCDD2) to stringResource(R.string.color_red),
                        Color(0xFFC8E6C9) to stringResource(R.string.color_green),
                        Color(0xFFBBDEFB) to stringResource(R.string.color_blue),
                        Color(0xFFFFF9C4) to stringResource(R.string.color_yellow),
                        Color(0xFFE1BEE7) to stringResource(R.string.color_purple),
                        Color(0xFFFFFFFF) to stringResource(R.string.color_white),
                        Color(0x33000000) to stringResource(R.string.color_subtle),
                    )
                    val isCustomBg = presetBgColors.none { it.first == bgColor }
                    presetBgColors.forEach { (color, _) ->
                        val selected = bgColor == color
                        Surface(
                            onClick = { bgColor = color },
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = if (selected) BorderStroke(
                                2.5.dp,
                                MaterialTheme.colorScheme.primary
                            ) else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        ) {
                            Box(modifier = Modifier.size(26.dp).clip(CircleShape).background(color))
                        }
                    }
                    // Custom bg color
                    Surface(
                        onClick = { showBgColorPicker = true },
                        shape = CircleShape,
                        color = Color.Transparent,
                        border = if (isCustomBg) BorderStroke(
                            2.5.dp,
                            MaterialTheme.colorScheme.primary
                        ) else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    ) {
                        Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Palette,
                                contentDescription = stringResource(R.string.custom_color),
                                modifier = Modifier.size(14.dp),
                                tint = if (isCustomBg) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (showBgColorPicker) {
                        ColorPickerWheelDialog(
                            initialColor = bgColor,
                            onDismiss = { showBgColorPicker = false },
                            onConfirm = { bgColor = it; showBgColorPicker = false },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Action buttons ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
                Button(
                    onClick = {
                        onConfirm(
                            previewDate, DateOverlayStyle(
                                textColor = textColor.toArgb().toLong(),
                                fontSize = fontSize,
                                backgroundStyle = bgStyle,
                                backgroundColor = bgColor.toArgb().toLong(),
                            )
                        )
                    },
                    modifier = Modifier.weight(1.3f).heightIn(min = 46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.done), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
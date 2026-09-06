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

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import nopalito.app.R

@SuppressLint("UnrememberedMutableState")
@Composable
fun SignatureCanvas(
    state: SignatureFlowState,
    onSizeChanged: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasContent by derivedStateOf { state.hasDrawContent() }
    val currentStroke = remember { mutableStateOf<List<Offset>>(emptyList()) }
    val canvasSizeRef = remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(state.canvasSize.value) {
        canvasSizeRef.value = state.canvasSize.value
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFFFEFEFE), Color(0xFFF8F9FA), Color(0xFFF3F4F6))
                    )
                )
                .border(
                    width = if (hasContent) 2.dp else 1.dp,
                    color = if (hasContent) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    },
                    shape = RoundedCornerShape(20.dp),
                )
                .onSizeChanged { size ->
                    state.canvasSize.value = size
                    canvasSizeRef.value = size
                    onSizeChanged(size)
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size ->
                            canvasSizeRef.value = size
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    state.undrawnStrokesClear()
                                    val size = canvasSizeRef.value
                                    val cw = size.width.coerceAtLeast(1).toFloat()
                                    val ch = size.height.coerceAtLeast(1).toFloat()
                                    val scale = state.renderScale.floatValue
                                    val cx = cw / 2f
                                    val cy = ch / 2f
                                    // Inverse transform: screen → stored
                                    val storedX = (offset.x - cx) / scale + cx
                                    val storedY = (offset.y - cy) / scale + cy
                                    currentStroke.value = listOf(
                                        Offset(
                                            storedX.coerceIn(4f, cw - 4f),
                                            storedY.coerceIn(4f, ch - 4f),
                                        )
                                    )
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val size = canvasSizeRef.value
                                    val cw = size.width.coerceAtLeast(1).toFloat()
                                    val ch = size.height.coerceAtLeast(1).toFloat()
                                    val scale = state.renderScale.floatValue
                                    val cx = cw / 2f
                                    val cy = ch / 2f
                                    // Inverse transform: screen → stored
                                    val storedX = (change.position.x - cx) / scale + cx
                                    val storedY = (change.position.y - cy) / scale + cy
                                    currentStroke.value += Offset(
                                        storedX.coerceIn(4f, cw - 4f),
                                        storedY.coerceIn(4f, ch - 4f),
                                    )
                                },
                                onDragEnd = {
                                    val pts = currentStroke.value
                                    if (pts.size > 1) {
                                        state.addStroke(pts)
                                    }
                                    currentStroke.value = emptyList()
                                },
                            )
                        },
                ) {
                    val scale = state.renderScale.floatValue
                    val cw = size.width
                    val ch = size.height
                    val cx = cw / 2f
                    val cy = ch / 2f

                    state.strokes.forEach { stroke ->
                        val scaled = scaleStrokeFromCenter(stroke, scale, cx, cy)
                        drawStrokePath(
                            scaled,
                            state.strokeColor.value,
                            state.strokeWidth.floatValue
                        )
                    }
                    if (currentStroke.value.size > 1) {
                        val scaled = scaleStrokeFromCenter(currentStroke.value, scale, cx, cy)
                        drawStrokePath(
                            scaled,
                            state.strokeColor.value,
                            state.strokeWidth.floatValue
                        )
                    }
                }
            }

            if (!hasContent) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Gesture,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = Color(0xFF8B95A1).copy(alpha = 0.25f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.sign_here),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF6B757F),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SignatureDrawToolbar(
            canUndo = state.strokes.isNotEmpty(),
            canRedo = state.undoneStrokes.isNotEmpty(),
            canClear = state.strokes.isNotEmpty(),
            onUndo = { state.undoDraw() },
            onRedo = { state.redoDraw() },
            onClear = { state.clearDraw() },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        SignatureThicknessSelector(
            strokeWidth = state.strokeWidth.floatValue,
            onThicknessSelected = { state.strokeWidth.floatValue = it },
        )

        Spacer(Modifier.height(10.dp))

        SignatureColorSelector(
            strokeColor = state.strokeColor.value,
            onColorSelected = { state.strokeColor.value = it },
        )

        Spacer(Modifier.height(10.dp))

        SignatureSizeSelector(
            renderScale = state.renderScale.floatValue,
            onScaleSelected = { state.renderScale.floatValue = it },
        )

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = {
                // Render the bitmap from the current strokes + editor settings.
                // The complete SignatureState is built and passed back through onConfirm.
                val bmp = if (state.strokes.isNotEmpty()) {
                    val size = canvasSizeRef.value
                    val cw = size.width.coerceAtLeast(1).toFloat()
                    val ch = size.height.coerceAtLeast(1).toFloat()
                    val cx = cw / 2f
                    val cy = ch / 2f
                    val scale = state.renderScale.floatValue
                    val scaledStrokes = state.strokes.map { stroke ->
                        scaleStrokeFromCenter(stroke, scale, cx, cy)
                    }
                    renderSignatureToBitmap(
                        strokes = scaledStrokes,
                        color = state.strokeColor.value,
                        strokeWidth = state.strokeWidth.floatValue,
                        scale = 1f,
                        size = 512,
                    )
                } else {
                    null
                }
                if (bmp != null) {
                    // Build the full persistent state and pass it along with the bitmap.
                    val existing = state.config.initialState
                    val sigState = state.buildSignatureState(
                        overlayScale = existing?.overlayScale ?: 1.0f,
                        positionFractionX = existing?.positionFractionX ?: 0.05f,
                        positionFractionY = existing?.positionFractionY ?: 0.05f,
                    )
                    state.onConfirm(sigState, bmp)
                }
            },
            enabled = state.hasDrawContent(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.confirm_signature),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = state.onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.cancel),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SignatureDrawToolbar(
    canUndo: Boolean,
    canRedo: Boolean,
    canClear: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignatureToolChip(
            text = stringResource(R.string.signature_undo),
            selected = false,
            enabled = canUndo,
            onClick = onUndo,
            modifier = Modifier.weight(1f),
            height = 40.dp,
        )
        SignatureToolChip(
            text = stringResource(R.string.signature_redo),
            selected = false,
            enabled = canRedo,
            onClick = onRedo,
            modifier = Modifier.weight(1f),
            height = 40.dp,
        )
        SignatureToolChip(
            text = stringResource(R.string.clear),
            selected = false,
            enabled = canClear,
            onClick = onClear,
            iconTint = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
            height = 40.dp,
        )
    }
}


private fun scaleStrokeFromCenter(
    stroke: List<Offset>,
    scale: Float,
    centerX: Float,
    centerY: Float,
): List<Offset> {
    if (scale == 1f) return stroke
    return stroke.map { p ->
        Offset(
            (p.x - centerX) * scale + centerX,
            (p.y - centerY) * scale + centerY,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStrokePath(
    stroke: List<Offset>,
    color: Color,
    strokeWidth: Float,
) {
    if (stroke.size < 2) return
    val path = Path().apply {
        moveTo(stroke.first().x, stroke.first().y)
        for (i in 1 until stroke.size) {
            val prev = stroke[i - 1]
            val curr = stroke[i]
            val midX = (prev.x + curr.x) / 2f
            val midY = (prev.y + curr.y) / 2f
            quadraticTo(prev.x, prev.y, midX, midY)
        }
        lineTo(stroke.last().x, stroke.last().y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

fun renderSignatureToBitmap(
    strokes: List<List<Offset>>,
    color: Color,
    strokeWidth: Float,
    scale: Float = 1.0f,
    size: Int = 512,
): Bitmap {
    val outputSize = (size * scale).toInt().coerceIn(128, 2048)
    val allPoints = strokes.flatten()
    if (allPoints.isEmpty()) {
        val empty = createBitmap(1, 1)
        empty.eraseColor(android.graphics.Color.TRANSPARENT)
        return empty
    }

    val minX = allPoints.minOf { it.x }
    val maxX = allPoints.maxOf { it.x }
    val minY = allPoints.minOf { it.y }
    val maxY = allPoints.maxOf { it.y }
    val rangeX = (maxX - minX).coerceAtLeast(1f)
    val rangeY = (maxY - minY).coerceAtLeast(1f)
    val padding = 32f
    val drawStrokeWidth = strokeWidth * 1.5f

    val contentWidth = rangeX + 2 * padding
    val contentHeight = rangeY + 2 * padding
    val aspect = contentWidth / contentHeight.coerceAtLeast(1f)

    val outWidth: Int
    val outHeight: Int
    if (aspect >= 1f) {
        outWidth = outputSize.coerceAtLeast(contentWidth.toInt())
        outHeight = (outWidth / aspect).toInt().coerceAtLeast(1)
    } else {
        outHeight = outputSize.coerceAtLeast(contentHeight.toInt())
        outWidth = (outHeight * aspect).toInt().coerceAtLeast(1)
    }

    val bitmap = createBitmap(outWidth, outHeight)
    bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        this.strokeWidth = drawStrokeWidth
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    strokes.forEach { stroke ->
        if (stroke.size > 1) {
            for (i in 1 until stroke.size) {
                val sx = stroke[i - 1].x - minX + padding
                val sy = stroke[i - 1].y - minY + padding
                val ex = stroke[i].x - minX + padding
                val ey = stroke[i].y - minY + padding
                canvas.drawLine(sx, sy, ex, ey, paint)
            }
        }
    }
    return bitmap
}
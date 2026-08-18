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

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import nopalito.app.R
import nopalito.app.ui.screens.document.SignatureSource
import nopalito.app.ui.screens.document.SignatureState
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color as ComposeColor

// ─── Editor tool enum ───
private enum class EditorTool(val label: Int) {
    ROTATE(R.string.signature_tool_rotate),
    BACKGROUND(R.string.signature_tool_background),
    ERASER(R.string.signature_tool_eraser),
    COLOR(R.string.signature_tool_color),
    BRIGHTNESS(R.string.signature_tool_brightness),
    SMOOTH(R.string.signature_tool_smooth),
    SIZE(R.string.signature_tool_size),
}

private data class ImportedEditorSnapshot(
    val bitmap: Bitmap,
    val backgroundRemoved: Boolean,
    val backgroundThreshold: Float,
    val brightness: Float,
    val contrast: Float,
    val colorArgb: Int?,
    val outputScale: Float,
)

/**
 * Full-screen signature editor for imported images.
 * Professional UI with: undo/redo, pinch-zoom, drag-pan, checkerboard,
 * eraser with live feedback, background removal, brightness/contrast,
 * rotate, color picker, smooth edges, clean noise, and output scale.
 */
@Composable
fun ImportedSignatureEditor(
    sourceBitmap: Bitmap,
    onConfirm: (SignatureState, Bitmap) -> Unit,
    onBack: () -> Unit,
    initialState: SignatureState? = null,
) {
    // ── State ──
    val originalBitmap = remember(sourceBitmap) {
        ImportedSignatureProcessor.copyArgb8888(sourceBitmap)
    }
    var workingBitmap by remember(sourceBitmap) {
        mutableStateOf(ImportedSignatureProcessor.copyArgb8888(sourceBitmap))
    }
    var activeTool by remember { mutableStateOf(EditorTool.BACKGROUND) }

    // Background removal — enabled by default for a new import so the white
    // paper is removed immediately, matching what users expect from a
    // signature editor; the switch can still turn it off.
    val freshImport = initialState == null
    var bgThreshold by remember(sourceBitmap, initialState) {
        mutableFloatStateOf(
            if (freshImport) {
                ImportedSignatureProcessor.estimateBackgroundThreshold(sourceBitmap).toFloat()
            } else {
                160f
            }
        )
    }
    var bgThresholdTouched by remember { mutableStateOf(freshImport) }
    var bgRemoved by remember { mutableStateOf(freshImport) }

    Log.d(
        "ImportedSignature",
        "editor init source=${sourceBitmap.width}x${sourceBitmap.height} " +
                "alpha=${ImportedSignatureProcessor.alphaBounds(sourceBitmap)} " +
                "freshImport=$freshImport threshold=${bgThreshold.roundToInt()}",
    )

    // Eraser
    var eraserSize by remember { mutableFloatStateOf(24f) }

    // Color — null means "keep the original ink"; a color is only applied when chosen.
    var selectedColor by remember { mutableStateOf<ComposeColor?>(null) }
    val colorOptions = listOf(
        ComposeColor(0xFF1A1A1A) to stringResource(R.string.color_black),
        ComposeColor(0xFF1565C0) to stringResource(R.string.color_blue),
        ComposeColor(0xFFC62828) to stringResource(R.string.color_red),
        ComposeColor(0xFF2E7D32) to stringResource(R.string.color_green),
        ComposeColor(0xFF4E342E) to stringResource(R.string.color_brown),
        ComposeColor(0xFF37474F) to stringResource(R.string.color_gray),
    )

    // Brightness / Contrast (preview-only, applied destructively on first change)
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var bcUndoPushed by remember { mutableStateOf(false) } // track if undo pushed for current drag

    // Output scale
    var outputScale by remember(sourceBitmap, initialState) {
        mutableFloatStateOf(
            initialState?.overlayScale?.coerceIn(
                SignatureState.MIN_OVERLAY_SCALE,
                SignatureState.MAX_OVERLAY_SCALE,
            ) ?: 1f
        )
    }

    // Undo / Redo stacks
    var undoStack by remember { mutableStateOf(listOf<ImportedEditorSnapshot>()) }
    var redoStack by remember { mutableStateOf(listOf<ImportedEditorSnapshot>()) }

    // View transform for zoom/pan
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Canvas size
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Render tick: forces displayBitmap recomposition when bitmap is mutated in-place
    var renderTick by remember { mutableIntStateOf(0) }

    fun snapshot(): ImportedEditorSnapshot {
        return ImportedEditorSnapshot(
            bitmap = ImportedSignatureProcessor.copyArgb8888(workingBitmap),
            backgroundRemoved = bgRemoved,
            backgroundThreshold = bgThreshold,
            brightness = brightness,
            contrast = contrast,
            colorArgb = selectedColor?.toArgb(),
            outputScale = outputScale,
        )
    }

    fun pushUndo() {
        undoStack = undoStack + snapshot()
        if (undoStack.size > 30) {
            undoStack.first().bitmap.recycle()
            undoStack = undoStack.drop(1)
        }
        redoStack.forEach { it.bitmap.recycle() }
        redoStack = emptyList()
    }

    fun restore(snapshot: ImportedEditorSnapshot) {
        workingBitmap = snapshot.bitmap
        bgRemoved = snapshot.backgroundRemoved
        bgThreshold = snapshot.backgroundThreshold
        brightness = snapshot.brightness
        contrast = snapshot.contrast
        selectedColor = snapshot.colorArgb?.let { ComposeColor(it) }
        outputScale = snapshot.outputScale
        bcUndoPushed = false
        renderTick++
    }

    fun doUndo() {
        if (undoStack.isEmpty()) return
        redoStack = redoStack + snapshot()
        val restored = undoStack.last()
        undoStack = undoStack.dropLast(1)
        restore(restored)
    }

    fun doRedo() {
        if (redoStack.isEmpty()) return
        undoStack = undoStack + snapshot()
        val restored = redoStack.last()
        redoStack = redoStack.dropLast(1)
        restore(restored)
    }

    // Build the display bitmap: apply bg removal + brightness + contrast + color
    val displayBitmap by remember(
        workingBitmap, bgRemoved, bgThreshold, brightness, contrast, selectedColor, renderTick
    ) {
        derivedStateOf {
            var bmp = workingBitmap.copy(Bitmap.Config.ARGB_8888, true)!!
            if (bgRemoved) {
                // 1) Threshold removes the bright paper wherever it is, even
                //    paper islands that do not touch the border, and carves
                //    the ink out of the paper.
                // 2) Connectivity flood then erases every border-connected
                //    leftover (dark desk, shadows, any background color),
                //    while the interior islands — the black ink — survive.
                bmp = ImportedSignatureProcessor.removeBackground(bmp, bgThreshold.toInt())
                bmp = ImportedSignatureProcessor.removeBorderConnected(bmp)
            }
            if (brightness != 0f || contrast != 0f) {
                bmp = applyBrightnessContrast(bmp, brightness, contrast)
            }
            selectedColor?.let {
                bmp = ImportedSignatureProcessor.applyColor(bmp, it.toArgb())
            }
            bmp
        }
    }

    /**
     * Bakes the current preview into [workingBitmap]. Brightness/contrast are
     * reset (they are now baked), but background removal and color stay active
     * so the "Remove background" switch and color selection do not silently reset.
     */
    fun commitDisplayedBitmap(): Bitmap {
        val committed = ImportedSignatureProcessor.copyArgb8888(displayBitmap)
        workingBitmap = committed
        brightness = 0f
        contrast = 0f
        bcUndoPushed = false
        renderTick++
        return committed
    }

    fun resetAll() {
        undoStack.forEach { it.bitmap.recycle() }
        redoStack.forEach { it.bitmap.recycle() }
        undoStack = emptyList()
        redoStack = emptyList()
        val fresh = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)!!
        workingBitmap = fresh
        bgRemoved = false
        bgThreshold = 160f
        bgThresholdTouched = false
        brightness = 0f
        contrast = 0f
        selectedColor = null
        zoom = 1f
        panOffset = Offset.Zero
        outputScale = 1f
        renderTick++
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // ═══════════════ TOP BAR ═══════════════
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                workingBitmap.recycle()
                undoStack.forEach { it.bitmap.recycle() }
                redoStack.forEach { it.bitmap.recycle() }
                onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.AutoFixHigh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.edit_signature_existing_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // Reset button
            IconButton(onClick = { resetAll() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, stringResource(R.string.reset), modifier = Modifier.size(20.dp))
            }
            // Undo
            IconButton(
                onClick = { doUndo() },
                enabled = undoStack.isNotEmpty(),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    stringResource(R.string.signature_undo),
                    modifier = Modifier.size(20.dp)
                )
            }
            // Redo
            IconButton(
                onClick = { doRedo() },
                enabled = redoStack.isNotEmpty(),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo,
                    stringResource(R.string.signature_redo),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // ═══════════════ CANVAS AREA ═══════════════
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ComposeColor(0xFFE0E0E0), RoundedCornerShape(12.dp))
                .border(1.dp, ComposeColor(0xFFBDBDBD), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Checkerboard pattern for transparency
            CheckerboardBackground()

            // The image canvas with zoom/pan
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(activeTool) {
                        if (activeTool == EditorTool.ERASER) return@pointerInput
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            zoom = (zoom * gestureZoom).coerceIn(0.5f, 5f)
                            panOffset += pan
                        }
                    }
                    .pointerInput(activeTool, eraserSize, outputScale, zoom, panOffset) {
                        if (activeTool != EditorTool.ERASER) return@pointerInput
                        var previousPoint: Offset? = null
                        detectDragGestures(
                            onDragStart = {
                                pushUndo()
                                previousPoint = null
                            },
                            onDragEnd = { previousPoint = null },
                            onDragCancel = { previousPoint = null },
                        ) { change, _ ->
                            change.consume()
                            if (canvasSize.width > 0 && canvasSize.height > 0) {
                                val bmp = workingBitmap
                                val scale = minOf(
                                    size.width.toFloat() / bmp.width,
                                    size.height.toFloat() / bmp.height,
                                ) * zoom * outputScale
                                if (scale <= 0f) return@detectDragGestures

                                val drawW = bmp.width * scale
                                val drawH = bmp.height * scale
                                val offsetX = (size.width - drawW) / 2f + panOffset.x
                                val offsetY = (size.height - drawH) / 2f + panOffset.y
                                val px = ((change.position.x - offsetX) / scale).roundToInt()
                                val py = ((change.position.y - offsetY) / scale).roundToInt()
                                val r = (eraserSize / 2f).roundToInt()
                                if (px in 0 until bmp.width && py in 0 until bmp.height) {
                                    ImportedSignatureProcessor.eraseLine(
                                        bitmap = bmp,
                                        fromX = previousPoint?.x?.roundToInt(),
                                        fromY = previousPoint?.y?.roundToInt(),
                                        toX = px,
                                        toY = py,
                                        radius = r,
                                    )
                                    previousPoint = Offset(px.toFloat(), py.toFloat())
                                    renderTick++ // force real-time display update
                                }
                            }
                        }
                    }
            ) {
                val bmp = displayBitmap
                if (bmp.width > 0 && bmp.height > 0) {
                    val baseScale = minOf(
                        size.width / bmp.width.toFloat(),
                        size.height / bmp.height.toFloat(),
                    )
                    val s = baseScale * zoom * outputScale
                    val drawW = bmp.width * s
                    val drawH = bmp.height * s
                    val ox = (size.width - drawW) / 2f + panOffset.x
                    val oy = (size.height - drawH) / 2f + panOffset.y

                    clipRect(0f, 0f, size.width, size.height) {
                        val imgBmp = bmp.asImageBitmap()
                        drawImage(
                            image = imgBmp,
                            dstOffset = androidx.compose.ui.unit.IntOffset(ox.toInt(), oy.toInt()),
                            dstSize = IntSize(drawW.toInt(), drawH.toInt()),
                        )
                    }
                }
            }

            // Eraser cursor indicator overlay
            if (activeTool == EditorTool.ERASER && canvasSize.width > 0) {
                val density = LocalDensity.current
                val bmp = displayBitmap
                val baseScale = minOf(
                    canvasSize.width.toFloat() / bmp.width,
                    canvasSize.height.toFloat() / bmp.height,
                )
                val s = baseScale * zoom * outputScale
                val cursorSizeDp = with(density) { (eraserSize * s).toDp() }
                Box(
                    modifier = Modifier
                        .size(cursorSizeDp)
                        .border(2.dp, ComposeColor.White.copy(alpha = 0.8f), CircleShape)
                        .border(1.dp, ComposeColor.Black.copy(alpha = 0.5f), CircleShape)
                )
            }

            // Zoom indicator
            if (zoom != 1f) {
                Text(
                    "${(zoom * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = ComposeColor.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(ComposeColor.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ═══════════════ TOOL SELECTOR BAR ═══════════════
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EditorTool.entries.forEach { tool ->
                val isActive = activeTool == tool
                val icon = when (tool) {
                    EditorTool.ROTATE -> Icons.AutoMirrored.Filled.RotateRight
                    EditorTool.BACKGROUND -> Icons.Default.AutoFixHigh
                    EditorTool.ERASER -> Icons.Default.Brush
                    EditorTool.COLOR -> Icons.Default.Palette
                    EditorTool.BRIGHTNESS -> Icons.Default.WbSunny
                    EditorTool.SMOOTH -> Icons.Default.BlurOn
                    EditorTool.SIZE -> Icons.Default.AspectRatio
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = {
                            activeTool = tool
                            if (tool == EditorTool.ROTATE) {
                                pushUndo()
                                workingBitmap = rotateBitmap90(commitDisplayedBitmap())
                                renderTick++
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = if (isActive) 0.dp else 1.dp,
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                icon,
                                contentDescription = stringResource(tool.label),
                                tint = if (isActive) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(tool.label),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ═══════════════ CONTEXTUAL TOOL PANEL ═══════════════
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                when (activeTool) {
                    EditorTool.BACKGROUND -> BackgroundToolPanel(
                        bgRemoved = bgRemoved,
                        onToggleBg = {
                            pushUndo()
                            bgRemoved = !bgRemoved
                            if (bgRemoved && !bgThresholdTouched) {
                                // Auto-tune the sensitivity from the photo's
                                // dominant color so removal works on dim paper.
                                val estimated = ImportedSignatureProcessor
                                    .estimateBackgroundThreshold(workingBitmap)
                                Log.d(
                                    "ImportedSignature",
                                    "toggleBg ON: auto-estimated threshold=$estimated",
                                )
                                bgThreshold = estimated.toFloat()
                            }
                            Log.d(
                                "ImportedSignature",
                                "toggleBg -> bgRemoved=$bgRemoved, " +
                                        "threshold=${bgThreshold.roundToInt()}, touched=$bgThresholdTouched",
                            )
                            renderTick++
                        },
                        threshold = bgThreshold,
                        onThresholdChange = {
                            bgThreshold = it
                            bgThresholdTouched = true
                        },
                        onAutoCrop = {
                            pushUndo()
                            workingBitmap = autoCropTransparent(commitDisplayedBitmap())
                            renderTick++
                        },
                        onApplyNoiseClean = { intensity ->
                            pushUndo()
                            cleanNoise(commitDisplayedBitmap(), intensity)
                            renderTick++
                        },
                    )

                    EditorTool.ERASER -> EraserToolPanel(
                        size = eraserSize,
                        onSizeChange = { eraserSize = it },
                    )

                    EditorTool.COLOR -> ColorToolPanel(
                        colors = colorOptions,
                        selected = selectedColor,
                        onSelect = {
                            pushUndo()
                            selectedColor = it
                            renderTick++
                        },
                        onSelectOriginal = {
                            pushUndo()
                            selectedColor = null
                            renderTick++
                        },
                    )

                    EditorTool.BRIGHTNESS -> BrightnessToolPanel(
                        brightness = brightness,
                        onBrightnessChange = { newBrightness ->
                            if (!bcUndoPushed) {
                                pushUndo(); bcUndoPushed = true
                            }
                            brightness = newBrightness
                            renderTick++
                        },
                        contrast = contrast,
                        onContrastChange = { newContrast ->
                            if (!bcUndoPushed) {
                                pushUndo(); bcUndoPushed = true
                            }
                            contrast = newContrast
                            renderTick++
                        },
                        onCommit = { bcUndoPushed = false },
                    )

                    EditorTool.SMOOTH -> SmoothToolPanel(
                        onSmooth = { passes ->
                            pushUndo()
                            val committed = commitDisplayedBitmap()
                            repeat(passes) { smoothEdges(committed) }
                            renderTick++
                        },
                        onCleanNoise = { intensity ->
                            pushUndo()
                            cleanNoise(commitDisplayedBitmap(), intensity)
                            renderTick++
                        },
                    )

                    EditorTool.SIZE -> SizeToolPanel(
                        scale = outputScale,
                        onScaleChange = { outputScale = it },
                    )

                    EditorTool.ROTATE -> {
                        Text(
                            stringResource(R.string.signature_rotate_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ═══════════════ CONFIRM BUTTON ═══════════════
        Button(
            onClick = {
                val finalBitmap = ImportedSignatureProcessor.freshCopy(displayBitmap)
                val existing = initialState
                val alphaBounds = ImportedSignatureProcessor.alphaBounds(finalBitmap)
                Log.d(
                    "ImportedSignature",
                    "final bitmap ${finalBitmap.width}x${finalBitmap.height}, " +
                            "alphaBounds=$alphaBounds, overlayScale=" +
                            (existing?.overlayScale ?: outputScale) +
                            ", bgRemoved=$bgRemoved, threshold=${bgThreshold.roundToInt()}",
                )
                onConfirm(
                    SignatureState(
                        strokes = emptyList(),
                        renderScale = outputScale,
                        overlayScale = existing?.overlayScale ?: outputScale,
                        positionFractionX = existing?.positionFractionX ?: 0.05f,
                        positionFractionY = existing?.positionFractionY ?: 0.05f,
                        source = SignatureSource.IMPORTED,
                        importedImageBytes = ImportedSignatureProcessor.toPngBytes(finalBitmap),
                    ),
                    finalBitmap,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.confirm_signature),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ═══════════════════════════════════════════════
// Tool panels
// ═══════════════════════════════════════════════

@Composable
private fun BackgroundToolPanel(
    bgRemoved: Boolean,
    onToggleBg: () -> Unit,
    threshold: Float,
    onThresholdChange: (Float) -> Unit,
    onAutoCrop: () -> Unit,
    onApplyNoiseClean: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.remove_background),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.weight(1f))
        Switch(checked = bgRemoved, onCheckedChange = { onToggleBg() })
    }
    if (bgRemoved) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.background_sensitivity), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = threshold,
            onValueChange = onThresholdChange,
            valueRange = 60f..240f,
        )
        Text(
            stringResource(R.string.background_threshold, threshold.roundToInt()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.clean_paper_noise),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium
    )
    Text(
        stringResource(R.string.clean_paper_noise_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onApplyNoiseClean(60) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(stringResource(R.string.noise_light), style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
            onClick = { onApplyNoiseClean(100) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(stringResource(R.string.noise_medium), style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
            onClick = { onApplyNoiseClean(150) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(stringResource(R.string.noise_strong), style = MaterialTheme.typography.labelMedium)
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onAutoCrop,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    ) {
        Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.auto_crop))
    }
}

@Composable
private fun EraserToolPanel(
    size: Float,
    onSizeChange: (Float) -> Unit,
) {
    Text(
        stringResource(R.string.manual_eraser),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
    )
    Text(
        stringResource(R.string.eraser_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.signature_tool_size), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = size,
            onValueChange = onSizeChange,
            valueRange = 4f..80f,
            modifier = Modifier.weight(1f),
        )
        Text("${size.roundToInt()}px", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ColorToolPanel(
    colors: List<Pair<ComposeColor, String>>,
    selected: ComposeColor?,
    onSelect: (ComposeColor) -> Unit,
    onSelectOriginal: () -> Unit,
) {
    var showCustomPicker by remember { mutableStateOf(false) }
    val isCustom = selected != null && colors.none { it.first == selected }

    Text(
        stringResource(R.string.signature_color_label),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
    )
    Text(
        stringResource(R.string.original_ink_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Original ink swatch (null selection)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .then(
                        if (selected == null) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier
                    )
                    .padding(if (selected == null) 4.dp else 0.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(ComposeColor(0xFF111111), ComposeColor(0xFFDDDDDD)))
                    )
                    .clickable { onSelectOriginal() }
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.color_original),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected == null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected == null) FontWeight.Bold else FontWeight.Normal,
            )
        }
        colors.forEach { (color, name) ->
            val isSelected = color == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .then(
                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            else Modifier
                        )
                        .padding(if (isSelected) 4.dp else 0.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { onSelect(color) }
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        // Custom color picker
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .then(
                        if (isCustom) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier
                    )
                    .padding(if (isCustom) 4.dp else 0.dp)
                    .clip(CircleShape)
                    .background(if (isCustom) selected else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { showCustomPicker = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Palette,
                    contentDescription = stringResource(R.string.custom_color),
                    modifier = Modifier.size(18.dp),
                    tint = if (isCustom) ComposeColor.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.custom),
                style = MaterialTheme.typography.labelSmall,
                color = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (isCustom) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }

    if (showCustomPicker) {
        ColorPickerWheelDialog(
            initialColor = selected ?: ComposeColor(0xFF1A1A1A),
            onDismiss = { showCustomPicker = false },
            onConfirm = { color ->
                onSelect(color)
                showCustomPicker = false
            },
        )
    }
}

@Composable
private fun BrightnessToolPanel(
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    contrast: Float,
    onContrastChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Text(
        stringResource(R.string.brightness_contrast),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
    )
    Text(
        stringResource(R.string.brightness_contrast_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(18.dp))
        Slider(
            value = brightness,
            onValueChange = { onBrightnessChange(it) },
            onValueChangeFinished = { onCommit() },
            valueRange = -100f..100f,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.LightMode, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    Text(
        stringResource(R.string.brightness_value, brightness.roundToInt()),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Contrast, contentDescription = null, modifier = Modifier.size(18.dp))
        Slider(
            value = contrast,
            onValueChange = { onContrastChange(it) },
            onValueChangeFinished = { onCommit() },
            valueRange = -100f..100f,
            modifier = Modifier.weight(1f),
        )
        Text("${contrast.roundToInt()}", style = MaterialTheme.typography.labelSmall)
    }
    Text(
        stringResource(R.string.contrast),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SmoothToolPanel(
    onSmooth: (Int) -> Unit,
    onCleanNoise: (Int) -> Unit,
) {
    Text(
        stringResource(R.string.smooth_clean),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
    )
    Text(
        stringResource(R.string.smooth_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.smooth_edges), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onSmooth(1) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("×1")
        }
        OutlinedButton(
            onClick = { onSmooth(3) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("×3")
        }
        OutlinedButton(
            onClick = { onSmooth(5) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("×5")
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.clean_noise), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onCleanNoise(30) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(stringResource(R.string.noise_light))
        }
        OutlinedButton(
            onClick = { onCleanNoise(60) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(stringResource(R.string.noise_medium))
        }
        OutlinedButton(
            onClick = { onCleanNoise(100) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(stringResource(R.string.noise_strong))
        }
    }
}

@Composable
private fun SizeToolPanel(
    scale: Float,
    onScaleChange: (Float) -> Unit,
) {
    Text(
        stringResource(R.string.signature_size),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
    )
    Text(
        stringResource(R.string.signature_size_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.size_small), style = MaterialTheme.typography.labelSmall)
        Slider(
            value = scale,
            onValueChange = onScaleChange,
            valueRange = SignatureState.MIN_OVERLAY_SCALE..SignatureState.MAX_OVERLAY_SCALE,
            steps = 6,
            modifier = Modifier.weight(1f),
        )
        Text(stringResource(R.string.size_large), style = MaterialTheme.typography.labelSmall)
    }
    Text(
        stringResource(R.string.scale_value, (scale * 100).roundToInt()),
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ═══════════════════════════════════════════════
// Checkerboard background composable
// ═══════════════════════════════════════════════

@Composable
private fun CheckerboardBackground() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cellSize = 16f
        val light = ComposeColor(0xFFEEEEEE)
        val dark = ComposeColor(0xFFCCCCCC)
        val cols = (size.width / cellSize).toInt() + 1
        val rows = (size.height / cellSize).toInt() + 1
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val c = if ((row + col) % 2 == 0) light else dark
                drawRect(
                    color = c,
                    topLeft = Offset(col * cellSize, row * cellSize),
                    size = androidx.compose.ui.geometry.Size(cellSize, cellSize),
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════
// Bitmap processing functions
// ═══════════════════════════════════════════════

private fun applyBrightnessContrast(bitmap: Bitmap, brightness: Float, contrast: Float): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val contrastFactor = (259f * (contrast + 255f)) / (255f * (259f - contrast))
    for (i in pixels.indices) {
        val p = pixels[i]
        if (Color.alpha(p) == 0) continue
        var r = Color.red(p) + brightness
        var g = Color.green(p) + brightness
        var b = Color.blue(p) + brightness
        r = contrastFactor * (r - 128f) + 128f
        g = contrastFactor * (g - 128f) + 128f
        b = contrastFactor * (b - 128f) + 128f
        pixels[i] = Color.argb(
            Color.alpha(p),
            r.roundToInt().coerceIn(0, 255),
            g.roundToInt().coerceIn(0, 255),
            b.roundToInt().coerceIn(0, 255),
        )
    }
    // Pure pass (see ImportedSignatureProcessor.removeBackground): never
    // setPixels on the result — the input may be a createBitmap(colors)
    // bitmap that some GPUs/emulators refuse to mutate.
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

private fun rotateBitmap90(bitmap: Bitmap): Bitmap {
    val m = Matrix().apply { postRotate(90f) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

private fun autoCropTransparent(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    var top = h
    var bottom = 0
    var left = w
    var right = 0
    for (y in 0 until h) for (x in 0 until w) {
        if (Color.alpha(pixels[y * w + x]) > 10) {
            if (y < top) top = y; if (y > bottom) bottom = y
            if (x < left) left = x; if (x > right) right = x
        }
    }
    if (top > bottom || left > right) return bitmap
    val pad = 8
    val l = (left - pad).coerceAtLeast(0)
    val t = (top - pad).coerceAtLeast(0)
    val r = (right + pad).coerceAtMost(w - 1)
    val b = (bottom + pad).coerceAtMost(h - 1)
    val cropped = Bitmap.createBitmap(bitmap, l, t, r - l + 1, b - t + 1)
    if (cropped !== bitmap) bitmap.recycle()
    return cropped
}

/**
 * Removes paper noise: light paper pixels (shadows, texture, notebook lines,
 * light stains), faint pixels and small isolated non-transparent dots.
 * Works both on opaque photos (luminance-based) and after background removal.
 * Higher threshold removes more aggressively.
 */
private fun cleanNoise(bitmap: Bitmap, threshold: Int) {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    // Luminance cutoff: pixels brighter than this are paper, not ink.
    val lightCutoff = (255 - threshold).coerceIn(80, 250)
    for (y in 0 until h) for (x in 0 until w) {
        val idx = y * w + x
        val a = Color.alpha(pixels[idx])
        if (a == 0) continue
        val luminance = (
                Color.red(pixels[idx]) * 0.299 +
                        Color.green(pixels[idx]) * 0.587 +
                        Color.blue(pixels[idx]) * 0.114
                ).toInt()
        // Remove light pixels (paper texture, shadows, notebook lines, stains)
        if (luminance > lightCutoff) {
            pixels[idx] = Color.TRANSPARENT
            continue
        }
        // Remove faint pixels (likely paper remnants after background removal)
        if (a < threshold) {
            pixels[idx] = Color.TRANSPARENT
            continue
        }
        // Remove isolated pixels (noise dots): count non-transparent neighbors
        var neighbors = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until w && ny in 0 until h) {
                if (Color.alpha(pixels[ny * w + nx]) > 10) neighbors++
            }
        }
        // Very isolated pixel (≤1 neighbor) is likely noise
        if (neighbors <= 1 && a < 200) {
            pixels[idx] = Color.TRANSPARENT
        }
    }
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
}

/**
 * Smooth edges with configurable passes. Each pass applies 3x3 box blur on alpha+RGB.
 */
private fun smoothEdges(bitmap: Bitmap) {
    val w = bitmap.width
    val h = bitmap.height
    val src = IntArray(w * h)
    bitmap.getPixels(src, 0, w, 0, 0, w, h)
    val dst = src.copyOf()
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        var sumA = 0
        var sumR = 0
        var sumG = 0
        var sumB = 0
        var count = 0
        for (dy in -1..1) for (dx in -1..1) {
            val p = src[(y + dy) * w + (x + dx)]
            if (Color.alpha(p) > 0) {
                sumA += Color.alpha(p); sumR += Color.red(p)
                sumG += Color.green(p); sumB += Color.blue(p)
                count++
            }
        }
        if (count > 0) {
            dst[y * w + x] = Color.argb(
                (sumA / count).coerceIn(0, 255),
                (sumR / count).coerceIn(0, 255),
                (sumG / count).coerceIn(0, 255),
                (sumB / count).coerceIn(0, 255),
            )
        }
    }
    bitmap.setPixels(dst, 0, w, 0, 0, w, h)
}
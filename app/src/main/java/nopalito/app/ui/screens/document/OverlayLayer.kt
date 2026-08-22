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

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nopalito.app.R
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

// ─────────────────────────────────────────────
// OverlayLayer — selection + pinch-to-resize
// ─────────────────────────────────────────────

@Composable
fun OverlayLayer(
    overlays: PageOverlays,
    pageId: String?,
    containerSize: IntSize,
    imageSize: IntSize,
    onSignatureMoved: (pageId: String, fraction: Offset) -> Unit,
    onDateMoved: (pageId: String, fraction: Offset) -> Unit,
    modifier: Modifier = Modifier,
    onSignatureScaleChanged: (pageId: String, scale: Float) -> Unit = { _, _ -> },
    onDateScaleChanged: (pageId: String, scale: Float) -> Unit = { _, _ -> },
    onSignatureRotationChanged: (pageId: String, degrees: Float) -> Unit = { _, _ -> },
    onDateRotationChanged: (pageId: String, degrees: Float) -> Unit = { _, _ -> },
    selectedOverlayType: OverlayType? = null,
    onOverlaySelected: (OverlayType) -> Unit = {},
    onOverlayDeselected: () -> Unit = {},
) {
    val cw = containerSize.width.coerceAtLeast(1)
    val ch = containerSize.height.coerceAtLeast(1)
    // Fitted image rect (ContentScale.Fit + Center) — overlay fractions are
    // relative to this rect so they match the export coordinate system exactly.
    val imgW = imageSize.width.coerceAtLeast(1)
    val imgH = imageSize.height.coerceAtLeast(1)
    val fitScale = minOf(cw.toFloat() / imgW, ch.toFloat() / imgH)
    val fittedW = (imgW * fitScale).toInt().coerceAtLeast(1)
    val fittedH = (imgH * fitScale).toInt().coerceAtLeast(1)
    val fittedOffsetX = (cw - fittedW) / 2
    val fittedOffsetY = (ch - fittedH) / 2
    val density = LocalDensity.current

    Box(modifier = modifier) {
        // Tap on empty area to deselect
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { onOverlayDeselected() }
                }
        )

        // Clip overlays to the actual image rect, not to the letterboxed container.
        Box(
            modifier = Modifier
                .offset { IntOffset(fittedOffsetX, fittedOffsetY) }
                .size(
                    width = with(density) { fittedW.toDp() },
                    height = with(density) { fittedH.toDp() },
                )
                .clip(RoundedCornerShape(0.dp))
        ) {
            // Signature overlay
            overlays.signatureBitmap?.let { sigBitmap ->
                val isSelected = selectedOverlayType == OverlayType.SIGNATURE
                SelectableOverlayBitmap(
                    bitmap = sigBitmap,
                    fractionX = overlays.signaturePositionFraction?.x ?: 0.05f,
                    fractionY = overlays.signaturePositionFraction?.y ?: 0.05f,
                    scale = overlays.signatureScale,
                    fittedWidth = fittedW,
                    fittedHeight = fittedH,
                    contentDescription = stringResource(R.string.sign),
                    isSelected = isSelected,
                    animateSelectionFeedback = overlays.signatureSource == SignatureSource.IMPORTED,
                    onLongPressOrTap = { onOverlaySelected(OverlayType.SIGNATURE) },
                    onDragEnd = { newFraction ->
                        if (pageId != null) onSignatureMoved(pageId, newFraction)
                    },
                    onScaleChanged = { newScale ->
                        if (pageId != null) onSignatureScaleChanged(pageId, newScale)
                    },
                    rotationDegrees = overlays.signatureRotationDegrees,
                    onRotationChanged = { newDegrees ->
                        if (pageId != null) onSignatureRotationChanged(pageId, newDegrees)
                    },
                    onRotationEnd = {},
                )
            }

            // Date overlay
            overlays.dateText?.let { dateText ->
                val isSelected = selectedOverlayType == OverlayType.DATE
                SelectableOverlayDate(
                    text = dateText,
                    fractionX = overlays.datePositionFraction?.x ?: 0.7f,
                    fractionY = overlays.datePositionFraction?.y ?: 0.05f,
                    scale = overlays.dateScale,
                    dateStyle = overlays.dateStyle,
                    fittedWidth = fittedW,
                    fittedHeight = fittedH,
                    isSelected = isSelected,
                    onLongPressOrTap = { onOverlaySelected(OverlayType.DATE) },
                    onDragEnd = { newFraction ->
                        if (pageId != null) onDateMoved(pageId, newFraction)
                    },
                    onScaleChanged = { newScale ->
                        if (pageId != null) onDateScaleChanged(pageId, newScale)
                    },
                    rotationDegrees = overlays.dateRotationDegrees,
                    onRotationChanged = { newDegrees ->
                        if (pageId != null) onDateRotationChanged(pageId, newDegrees)
                    },
                    onRotationEnd = {},
                )
            }
        }
    }
}

// ─────────────────────────────────────────────
// Selectable overlay composables — premium edition
// ─────────────────────────────────────────────

/** Common bounce+glow animation for overlay selection. */
@Composable
private fun rememberSelectionAnimation(isSelected: Boolean): SelectionAnimState {
    val haptic = LocalHapticFeedback.current
    var justSelected by remember { mutableStateOf(false) }

    LaunchedEffect(isSelected) {
        if (isSelected) {
            justSelected = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(400.milliseconds)
            justSelected = false
        }
    }

    val bounceScale by animateFloatAsState(
        targetValue = if (isSelected) 1.04f else 1f,
        animationSpec = if (justSelected) {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow,
            )
        } else {
            tween(300)
        },
        label = "bounce",
    )

    val glowAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.10f else 0f,
        animationSpec = tween(350),
        label = "glow",
    )

    // Subtle breathing pulse when selected
    val infiniteTransition = rememberInfiniteTransition(label = "breath")
    val breathPulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathPulse",
    )

    val borderAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = if (isSelected) tween(200) else tween(150),
        label = "borderAlpha",
    )

    val effectiveGlow = if (isSelected) glowAlpha * breathPulse else 0f

    return SelectionAnimState(bounceScale, effectiveGlow, borderAlpha)
}

private data class SelectionAnimState(
    val bounceScale: Float,
    val glowAlpha: Float,
    val borderAlpha: Float,
)

private enum class HandleCorner { TopStart, TopEnd, BottomStart, BottomEnd }

@Composable
private fun HandleDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 10.dp,
    corner: HandleCorner? = null,
    onCornerDrag: ((Float) -> Unit)? = null,
    /** Invoked once when a corner drag ends/cancels (the moment to persist). */
    onCornerDragEnd: (() -> Unit)? = null,
    displayWidthPx: Float = 1f,
    displayHeightPx: Float = 1f,
) {
    // Positioned by the caller via Modifier.offset — this composable only
    // draws the circle at that point.
    val boxModifier = modifier
        .size(size)
        .shadow(3.dp, CircleShape)
        .clip(CircleShape)
        .background(color)
        .border(2.dp, Color.White, CircleShape)

    val finalModifier = if (corner != null && onCornerDrag != null) {
        boxModifier.pointerInput(corner) {
            detectDragGestures(
                onDragEnd = { onCornerDragEnd?.invoke() },
                onDragCancel = { onCornerDragEnd?.invoke() },
            ) { change, dragAmount ->
                change.consume()
                val isOutward = when (corner) {
                    HandleCorner.TopStart -> dragAmount.x < 0f || dragAmount.y < 0f
                    HandleCorner.TopEnd -> dragAmount.x > 0f || dragAmount.y < 0f
                    HandleCorner.BottomStart -> dragAmount.x < 0f || dragAmount.y > 0f
                    HandleCorner.BottomEnd -> dragAmount.x > 0f || dragAmount.y > 0f
                }
                // dragAmount is already in px (pointer local space).
                // Scale delta as fraction of display size (outward = increase scale).
                val magnitudePx = kotlin.math.sqrt(dragAmount.x * dragAmount.x + dragAmount.y * dragAmount.y)
                val avgDisplaySize = (displayWidthPx + displayHeightPx) / 2f
                val scaleDelta = (if (isOutward) 1f else -1f) * magnitudePx / avgDisplaySize
                onCornerDrag(scaleDelta)
            }
        }
    } else boxModifier

    Box(modifier = finalModifier)
}

@Composable
private fun SelectableOverlayBitmap(
    bitmap: Bitmap,
    fractionX: Float,
    fractionY: Float,
    scale: Float,
    rotationDegrees: Float,
    fittedWidth: Int,
    fittedHeight: Int,
    contentDescription: String,
    isSelected: Boolean,
    animateSelectionFeedback: Boolean = false,
    onLongPressOrTap: () -> Unit,
    onDragEnd: (Offset) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onRotationChanged: (Float) -> Unit,
    onRotationEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val animatedSelectionAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(220),
        label = "signatureSelection",
    )
    val selectionAlpha = if (animateSelectionFeedback) {
        animatedSelectionAlpha
    } else if (isSelected) {
        1f
    } else {
        0f
    }

    // Use the same dimensions as the export layer so editor and export match.
    val maxWidthPx = fittedWidth * OverlayConstants.SIGNATURE_WIDTH_FRACTION
    val maxHeightPx = fittedHeight * OverlayConstants.SIGNATURE_HEIGHT_FRACTION

    // Rotation is a live graphicsLayer transform, so the bitmap stays original.
    val (baseWidthPx, baseHeightPx) = OverlayConstants.computeSignatureBaseSize(
        bitmap.width,
        bitmap.height,
        maxWidthPx,
        maxHeightPx
    )

    var currentFractionX by remember { mutableFloatStateOf(fractionX) }
    var currentFractionY by remember { mutableFloatStateOf(fractionY) }
    var currentScale by remember { mutableFloatStateOf(scale) }
    var currentDegrees by remember { mutableFloatStateOf(rotationDegrees) }
    var isDragging by remember { mutableStateOf(false) }
    var isRotating by remember { mutableStateOf(false) }

    fun naturalWidthPx(): Float = (baseWidthPx * currentScale).coerceAtLeast(1f)
    fun naturalHeightPx(): Float = (baseHeightPx * currentScale).coerceAtLeast(1f)
    fun displayWidthPx(): Float = OverlayConstants.rotatedVisualSize(
        naturalWidthPx(), naturalHeightPx(), currentDegrees
    ).first

    fun displayHeightPx(): Float = OverlayConstants.rotatedVisualSize(
        naturalWidthPx(), naturalHeightPx(), currentDegrees
    ).second

    fun clampPosition() {
        // The container may not be measured yet on the first frame (e.g. right
        // after resuming a session); clamping against a degenerate size would
        // pin the overlay to the top-left corner permanently. Skip until real.
        if (fittedWidth <= 1 || fittedHeight <= 1) return
        val visW = displayWidthPx()
        val visH = displayHeightPx()
        val maxFx = (1f - visW / fittedWidth.coerceAtLeast(1)).coerceAtLeast(0f)
        val maxFy = (1f - visH / fittedHeight.coerceAtLeast(1)).coerceAtLeast(0f)
        currentFractionX = currentFractionX.coerceIn(0f, maxFx)
        currentFractionY = currentFractionY.coerceIn(0f, maxFy)
    }

    LaunchedEffect(fractionX, fractionY, scale, rotationDegrees, fittedWidth, fittedHeight) {
        currentScale = scale
        if (!isDragging) {
            currentFractionX = fractionX
            currentFractionY = fractionY
        }
        if (!isRotating) {
            currentDegrees = rotationDegrees
        }
        clampPosition()
    }

    fun overlayPositionPx(): IntOffset {
        val displayWidth = displayWidthPx()
        val displayHeight = displayHeightPx()

        val maxX = (fittedWidth - displayWidth).coerceAtLeast(0f)
        val maxY = (fittedHeight - displayHeight).coerceAtLeast(0f)

        val x = (currentFractionX * fittedWidth).coerceIn(0f, maxX)
        val y = (currentFractionY * fittedHeight).coerceIn(0f, maxY)

        return IntOffset(
            x = x.roundToInt(),
            y = y.roundToInt()
        )
    }

    // Refs for latest state in drag gesture (must be at composable level)
    val currentFractionXRef = rememberUpdatedState(currentFractionX)
    val currentFractionYRef = rememberUpdatedState(currentFractionY)
    val currentScaleRef = rememberUpdatedState(currentScale)

    Box {
        Box(
            modifier = Modifier
                .offset { overlayPositionPx() }
                .size(
                    width = with(density) { displayWidthPx().toDp() },
                    height = with(density) { displayHeightPx().toDp() }
                )
                // The selected overlay renders (and hit-tests) on top, so it can be
                // moved freely over the other overlay even when both are large.
                .zIndex(if (isSelected) 1f else 0f)
                .pointerInput(isSelected) {
                    if (isSelected) {
                        // Pure drag (no pinch). 1:1 tracking in overlay's local space.
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                            },
                            onDragEnd = {
                                isDragging = false
                                // Persist final position and scale after gesture
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(150.milliseconds)
                                    onScaleChanged(currentScaleRef.value)
                                    onDragEnd(Offset(currentFractionXRef.value, currentFractionYRef.value))
                                }
                            },
                            onDragCancel = { isDragging = false },
                        ) { change, dragAmount ->
                            // A corner handle is being dragged: it consumes the
                            // events, so this overlay must not move at the same time.
                            if (change.isConsumed) return@detectDragGestures
                            change.consume()
                            val safeFittedWidth = fittedWidth.coerceAtLeast(1)
                            val safeFittedHeight = fittedHeight.coerceAtLeast(1)

                            // dragAmount is already in px (overlay local space). The
                            // fraction is relative to the fitted image, so 1:1 tracking
                            // means fraction delta = px delta / fitted image size.
                            val dragFractionX = dragAmount.x / safeFittedWidth
                            val dragFractionY = dragAmount.y / safeFittedHeight

                            currentFractionX = (currentFractionXRef.value + dragFractionX).coerceIn(
                                0f,
                                (1f - displayWidthPx() / safeFittedWidth).coerceAtLeast(0f)
                            )
                            currentFractionY = (currentFractionYRef.value + dragFractionY).coerceIn(
                                0f,
                                (1f - displayHeightPx() / safeFittedHeight).coerceAtLeast(0f)
                            )
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            onLongPressOrTap()
                        },
                        onLongPress = {
                            onLongPressOrTap()
                        }
                    )
                }
            // No pinch-to-zoom: the overlay size only changes when the corner
            // handles are touched precisely (or via the toolbar +/- buttons).
        ) {
            // Rotated content: the natural (unrotated) box is centered in the
            // visual box and rotated around its own center.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(
                        width = with(density) { naturalWidthPx().toDp() },
                        height = with(density) { naturalHeightPx().toDp() },
                    )
                    .graphicsLayer { rotationZ = currentDegrees }
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            if (isSelected) {
                val displayWidth = displayWidthPx()
                val displayHeight = displayHeightPx()
                val halfHandleSizePx = with(density) { 7.dp.toPx() }

                // Selection frame + resize handles sit on the axis-aligned
                // visual box, so they always wrap the rotated content.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                                .copy(alpha = 0.7f * selectionAlpha),
                            shape = RoundedCornerShape(2.dp)
                        )
                )

                val corners = listOf(
                    Offset(0f, 0f) to HandleCorner.TopStart,
                    Offset(displayWidth, 0f) to HandleCorner.TopEnd,
                    Offset(0f, displayHeight) to HandleCorner.BottomStart,
                    Offset(displayWidth, displayHeight) to HandleCorner.BottomEnd,
                )
                corners.forEach { (cornerOffset, handleCorner) ->
                    HandleDot(
                        modifier = Modifier.offset {
                            IntOffset(
                                x = (cornerOffset.x - halfHandleSizePx).roundToInt(),
                                y = (cornerOffset.y - halfHandleSizePx).roundToInt(),
                            )
                        },
                        color = MaterialTheme.colorScheme.primary,
                        size = 14.dp,
                        corner = handleCorner,
                        displayWidthPx = displayWidth,
                        displayHeightPx = displayHeight,
                        onCornerDrag = { scaleDelta ->
                            // Update the local scale immediately for a fluid
                            // resize; persisting waits until the drag ends so
                            // the ViewModel round-trip can't stutter it.
                            val newScale = (
                                    currentScale + scaleDelta
                                    ).coerceIn(
                                    SignatureState.MIN_OVERLAY_SCALE,
                                    SignatureState.MAX_OVERLAY_SCALE
                                )
                            currentScale = newScale
                            clampPosition()
                        },
                        onCornerDragEnd = {
                            onScaleChanged(currentScale)
                        }
                    )
                }
            }
        }

        // ── Floating rotation handle, anchored to the top edge of the element ──
        if (isSelected) {
            RotationHandle(
                degrees = currentDegrees,
                onDegreesChange = { newDeg ->
                    currentDegrees = newDeg
                    isRotating = true
                    clampPosition()
                    onRotationChanged(newDeg)
                },
                onRotationEnd = {
                    isRotating = false
                    onRotationEnd()
                },
                modifier = Modifier.offset {
                    val pos = overlayPositionPx()
                    IntOffset(
                        x = pos.x + (displayWidthPx() / 2f).roundToInt(),
                        y = pos.y,
                    )
                }
            )
        }
    }
}

@Composable
private fun SelectableOverlayDate(
    text: String,
    fractionX: Float,
    fractionY: Float,
    scale: Float,
    rotationDegrees: Float,
    dateStyle: DateOverlayStyle,
    fittedWidth: Int,
    fittedHeight: Int,
    isSelected: Boolean,
    onLongPressOrTap: () -> Unit,
    onDragEnd: (Offset) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onRotationChanged: (Float) -> Unit,
    onRotationEnd: () -> Unit,
) {
    // Keep the date in image pixels internally. Convert to sp only at the
    // Compose text boundary so it matches the export canvas on any density.
    val density = LocalDensity.current
    val baseFontSize = OverlayConstants.computeDateFontSizePx(
        imageWidth = fittedWidth.toFloat(),
        styleFontSize = dateStyle.fontSize,
    )

    val selAnim = rememberSelectionAnimation(isSelected)

    var currentFracX by remember { mutableFloatStateOf(fractionX) }
    var currentFracY by remember { mutableFloatStateOf(fractionY) }
    var currentScale by remember { mutableFloatStateOf(scale) }
    var currentDegrees by remember { mutableFloatStateOf(rotationDegrees) }
    var isDragging by remember { mutableStateOf(false) }
    var isRotating by remember { mutableStateOf(false) }

    fun visualSize(m: OverlayConstants.DateMetrics): Pair<Float, Float> =
        OverlayConstants.rotatedVisualSize(m.widthPx, m.heightPx, currentDegrees)

    fun clampPosition() {
        // The container may not be measured yet on the first frame (e.g. right
        // after resuming a session); clamping against a degenerate size would
        // pin the overlay to the top-left corner permanently. Skip until real.
        if (fittedWidth <= 1 || fittedHeight <= 1) return
        val metrics = OverlayConstants.dateMetrics(
            text = text,
            fontSizePx = baseFontSize * currentScale,
            backgroundStyle = dateStyle.backgroundStyle,
        )
        val (visW, visH) = visualSize(metrics)
        currentFracX = currentFracX.coerceIn(
            0f,
            (1f - visW / fittedWidth).coerceAtLeast(0f),
        )
        currentFracY = currentFracY.coerceIn(
            0f,
            (1f - visH / fittedHeight).coerceAtLeast(0f),
        )
    }

    // Scale always mirrors ViewModel state; position syncs when not dragging.
    LaunchedEffect(fractionX, fractionY, scale, rotationDegrees, fittedWidth, fittedHeight) {
        currentScale = scale
        if (!isDragging) {
            currentFracX = fractionX; currentFracY = fractionY
        }
        if (!isRotating) {
            currentDegrees = rotationDegrees
        }
        clampPosition()
    }

    val dateFontSizePx = baseFontSize * currentScale
    val dateMetrics = OverlayConstants.dateMetrics(
        text = text,
        fontSizePx = dateFontSizePx,
        backgroundStyle = dateStyle.backgroundStyle,
    )

    // Refs for latest state in drag gesture (must be at composable level)
    val currentFracXRef = rememberUpdatedState(currentFracX)
    val currentFracYRef = rememberUpdatedState(currentFracY)
    val currentScaleRef = rememberUpdatedState(currentScale)

    // Real measured size of the text box — used to position the selection
// handles on the actual bounding box instead of an estimate.
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }

    // Position formula shared by the date box and its selection box so both
    // land on the same origin.
    fun positionOffset(fs: Float): IntOffset {
        val metrics = OverlayConstants.dateMetrics(
            text = text,
            fontSizePx = fs,
            backgroundStyle = dateStyle.backgroundStyle,
        )
        val (visW, visH) = visualSize(metrics)
        val maxX = (fittedWidth - visW).coerceAtLeast(0f)
        val maxY = (fittedHeight - visH).coerceAtLeast(0f)
        val x = (currentFracX * fittedWidth).coerceAtLeast(0f).coerceAtMost(maxX)
        val y = (currentFracY * fittedHeight).coerceAtLeast(0f).coerceAtMost(maxY)
        return IntOffset(x.toInt(), y.toInt())
    }

    // Parent container lets the date box and its selection box (sibling) both
    // position themselves with offset without clipping each other.
    // The selected date renders on top so it can be moved over the signature.
    Box(modifier = Modifier.fillMaxSize().zIndex(if (isSelected) 1f else 0f)) {
        // Date box — handles drag, tap, pinch
        Box(
            modifier = Modifier
                .onSizeChanged { if (it != IntSize.Zero) measuredSize = it }
                .offset { positionOffset(baseFontSize * currentScale) }
                .size(
                    width = with(density) { visualSize(dateMetrics).first.toDp() },
                    height = with(density) { visualSize(dateMetrics).second.toDp() },
                )
                .pointerInput(isSelected) {
                    if (isSelected) {
                        // Pure drag (no pinch). 1:1 tracking in overlay's local space.
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = {
                                isDragging = false
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(150.milliseconds)
                                    onScaleChanged(currentScaleRef.value)
                                    onDragEnd(Offset(currentFracXRef.value, currentFracYRef.value))
                                }
                            },
                            onDragCancel = { isDragging = false },
                        ) { change, dragAmount ->
                            // A corner handle is being dragged: it consumes the
                            // events, so this overlay must not move at the same time.
                            if (change.isConsumed) return@detectDragGestures
                            change.consume()
                            val metrics = OverlayConstants.dateMetrics(
                                text = text,
                                fontSizePx = baseFontSize * currentScaleRef.value,
                                backgroundStyle = dateStyle.backgroundStyle,
                            )
                            val (visW, visH) = visualSize(metrics)
                            val maxFracX = (1f - visW / fittedWidth).coerceAtLeast(0f)
                            val maxFracY = (1f - visH / fittedHeight).coerceAtLeast(0f)

                            // dragAmount is already in px (date box local space).
                            // The fraction is relative to the fitted image, so
                            // 1:1 tracking means fraction delta = px / fitted size.
                            val dragFractionX = dragAmount.x / fittedWidth
                            val dragFractionY = dragAmount.y / fittedHeight

                            currentFracX = (currentFracXRef.value + dragFractionX).coerceIn(0f, maxFracX)
                            currentFracY = (currentFracYRef.value + dragFractionY).coerceIn(0f, maxFracY)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onLongPressOrTap() },
                    )
                }
            // No pinch-to-zoom: the size only changes when the corner
            // handles are touched precisely (or via the toolbar +/- buttons).
        ) {
            // Rotated content: centered, sized to the un-rotated text box,
            // rotated around its center so it fills the swapped outer box.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(
                        width = with(density) { dateMetrics.widthPx.toDp() },
                        height = with(density) { dateMetrics.heightPx.toDp() },
                    )
                    .graphicsLayer { rotationZ = currentDegrees }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = dateStyle.textColor.toInt()
                            textSize = dateFontSizePx
                            isFakeBoldText = true
                        }
                        if (dateStyle.backgroundStyle != DateBackgroundStyle.NONE) {
                            val backgroundPaint = Paint().apply {
                                color = dateStyle.backgroundColor.toInt()
                                style = Paint.Style.FILL
                            }
                            canvas.nativeCanvas.drawRoundRect(
                                RectF(0f, 0f, size.width, size.height),
                                dateMetrics.cornerRadiusPx,
                                dateMetrics.cornerRadiusPx,
                                backgroundPaint,
                            )
                        }
                        canvas.nativeCanvas.drawText(
                            text,
                            dateMetrics.horizontalPaddingPx,
                            dateMetrics.verticalPaddingPx + dateMetrics.ascentPx,
                            textPaint,
                        )
                    }
                }
            }
        }

        // Selection box — sibling of the date box with the date's exact position
        // and a FIXED size (measuredSize). No wrapContentSize, no graphicsLayer,
        // no nested zero-size Layout: the 4 handles sit exactly on the real
        // corners and render above the content (same proven pattern as the
        // signature). ponytail: overlays have no rotation today; add rotated
        // corners here if rotation support is ever introduced.
        if ((isSelected || selAnim.borderAlpha > 0.01f) && measuredSize != IntSize.Zero) {
            val density = LocalDensity.current
            val half = with(density) { 7.dp.toPx() }
            Box(
                modifier = Modifier
                    .offset { positionOffset(baseFontSize * currentScale) }
                    .size(
                        width = with(density) { measuredSize.width.toDp() },
                        height = with(density) { measuredSize.height.toDp() },
                    )
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .shadow(12.dp, RoundedCornerShape(4.dp))
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = selAnim.borderAlpha),
                            shape = RoundedCornerShape(4.dp),
                        )
                )
                if (isSelected) {
                    val displayWidth = measuredSize.width.toFloat()
                    val displayHeight = measuredSize.height.toFloat()
                    val corners = listOf(
                        Offset(0f, 0f) to HandleCorner.TopStart,
                        Offset(measuredSize.width.toFloat(), 0f) to HandleCorner.TopEnd,
                        Offset(0f, measuredSize.height.toFloat()) to HandleCorner.BottomStart,
                        Offset(measuredSize.width.toFloat(), measuredSize.height.toFloat()) to HandleCorner.BottomEnd,
                    )
                    corners.forEach { (cornerOffset, handleCorner) ->
                        HandleDot(
                            modifier = Modifier.offset {
                                IntOffset(
                                    (cornerOffset.x - half).roundToInt(),
                                    (cornerOffset.y - half).roundToInt(),
                                )
                            },
                            color = MaterialTheme.colorScheme.primary,
                            size = 14.dp,
                            corner = handleCorner,
                            displayWidthPx = displayWidth,
                            displayHeightPx = displayHeight,
                            onCornerDrag = { delta ->
                                val newScale = (currentScale + delta).coerceIn(0.5f, 2.5f)
                                currentScale = newScale
                                // Re-clamp so the resized date never leaves the image.
                                val resizedMetrics = OverlayConstants.dateMetrics(
                                    text = text,
                                    fontSizePx = baseFontSize * newScale,
                                    backgroundStyle = dateStyle.backgroundStyle,
                                )
                                val (resVisW, resVisH) = visualSize(resizedMetrics)
                                currentFracX = currentFracX.coerceIn(
                                    0f,
                                    (1f - resVisW / fittedWidth).coerceAtLeast(0f),
                                )
                                currentFracY = currentFracY.coerceIn(
                                    0f,
                                    (1f - resVisH / fittedHeight).coerceAtLeast(0f),
                                )
                            },
                            // Persist once per gesture — per-move callbacks
                            // round-trip through the ViewModel and stutter.
                            onCornerDragEnd = { onScaleChanged(currentScale) },
                        )
                    }
                }
            }
        }

        // ── Floating rotation handle, anchored to the top edge of the date ──
        if (isSelected) {
            RotationHandle(
                degrees = currentDegrees,
                onDegreesChange = { newDeg ->
                    currentDegrees = newDeg
                    isRotating = true
                    clampPosition()
                    onRotationChanged(newDeg)
                },
                onRotationEnd = {
                    isRotating = false
                    onRotationEnd()
                },
                modifier = Modifier.offset {
                    val pos = positionOffset(baseFontSize * currentScale)
                    IntOffset(
                        x = pos.x + (visualSize(dateMetrics).first / 2f).roundToInt(),
                        y = pos.y,
                    )
                }
            )
        }
    }
}

// ─────────────────────────────────────────────
// Floating contextual toolbar for overlay selection
// ─────────────────────────────────────────────

/**
 * Floating rotation handle anchored to the top edge of a selected overlay.
 * Long-press (or tap-and-drag) then drag horizontally to rotate the element
 * continuously. Feedback: the handle highlights and a live degree badge shows
 * the current angle while dragging.
 */
@Composable
private fun RotationHandle(
    degrees: Float,
    onDegreesChange: (Float) -> Unit,
    onRotationEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val handleSize = 40.dp
    var isActive by remember { mutableStateOf(false) }
    var dragStartDegrees by remember { mutableFloatStateOf(degrees) }
    var dragAccumulatedX by remember { mutableFloatStateOf(0f) }
    var currentDegrees by remember { mutableFloatStateOf(degrees) }
    // True while dragging to the right (rotate right), false otherwise.
    var draggingRight by remember { mutableStateOf(true) }

    LaunchedEffect(degrees) {
        if (!isActive) currentDegrees = degrees
    }

    // Degrees per pixel of horizontal drag. ~0.6°/px keeps a full rotation
    // within a couple of finger swipes while staying precise enough to nudge.
    val degreesPerPx = 0.6f

    val icon = @Suppress("DEPRECATION")
    if (draggingRight) Icons.Default.RotateRight else Icons.Default.RotateLeft

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = (-handleSize / 2).roundToPx(),
                    y = (-handleSize - 6.dp).roundToPx(),
                )
            }
            .size(handleSize)
            .graphicsLayer {
                val scale = if (isActive) 1.1f else 1f
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        isActive = true
                        dragStartDegrees = currentDegrees
                        dragAccumulatedX = 0f
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = {
                        isActive = false
                        onRotationEnd()
                    },
                    onDragCancel = {
                        isActive = false
                        onRotationEnd()
                    },
                ) { change, dragAmount ->
                    change.consume()
                    dragAccumulatedX += dragAmount.x
                    draggingRight = dragAccumulatedX >= 0f
                    val next = normalizeDegrees(dragStartDegrees + dragAccumulatedX * degreesPerPx)
                    currentDegrees = next
                    onDegreesChange(next)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = stringResource(
                if (draggingRight) R.string.rotate_right else R.string.rotate_left
            ),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )

        // Live degree badge while dragging
        if (isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = 46.dp)
                    .width(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${currentDegrees.roundToInt()}°",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun normalizeDegrees(degrees: Float): Float =
    ((degrees % 360f) + 360f) % 360f

@Composable
fun FloatingOverlayToolbar(
    overlayType: OverlayType?,
    onDelete: () -> Unit,
    onEditStyle: () -> Unit,
    onDone: () -> Unit,
    onEditSignature: () -> Unit = {},
    onZoomIn: () -> Unit = {},
    onZoomOut: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onMoveLeft: () -> Unit = {},
    onMoveRight: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
        shadowElevation = 8.dp,
        tonalElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Zoom in button (+)
            if (overlayType != null) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onZoomIn()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.zoom_in),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onZoomOut()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = stringResource(R.string.zoom_out),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Directional nudge cross: compact fine-positioning pad.
            if (overlayType != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                    tonalElevation = 1.dp,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        NudgeArrowButton(
                            icon = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.move_up),
                            onClick = onMoveUp,
                        )
                        Row {
                            NudgeArrowButton(
                                icon = Icons.Default.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.move_left),
                                onClick = onMoveLeft,
                            )
                            NudgeArrowButton(
                                icon = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.move_down),
                                onClick = onMoveDown,
                            )
                            NudgeArrowButton(
                                icon = Icons.Default.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.move_right),
                                onClick = onMoveRight,
                            )
                        }
                    }
                }
            }

            // Edit signature button
            if (overlayType == OverlayType.SIGNATURE) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEditSignature()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Edit style button (only for date)
            if (overlayType == OverlayType.DATE) {
                IconButton(onClick = onEditStyle, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = stringResource(R.string.date_style_title),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Delete button
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            )

            // Done button
            TextButton(
                onClick = onDone,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.done),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * Compact arrow of the overlay nudge cross. Uses a plain clickable Box
 * instead of IconButton so the 48dp minimum-touch-target inflation doesn't
 * blow up the pad size.
 */
@Composable
private fun NudgeArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

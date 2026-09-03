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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import nopalito.app.ui.screens.document.SerializableOffset
import nopalito.app.ui.screens.document.SignatureSource
import nopalito.app.ui.screens.document.SignatureState
import androidx.compose.ui.graphics.Color as ComposeColor

// ─────────────────────────────────────────────
// SignatureFlowState — state for draw-only signature flow
// ─────────────────────────────────────────────

/** Steps in the signature creation flow. */
enum class SignatureFlowStep {
    SOURCE,
    DRAW,
    CONFIRM,
}

/**
 * Configuration passed when opening the signature flow.
 *
 * When editing an existing signature, [initialState] carries the full
 * persistent state (strokes, color, width, scales, position) so the editor
 * can reconstruct the signature exactly as it was saved.
 */
data class SignatureFlowConfig(
    /** The page this signature belongs to. */
    val pageId: String,
    /** Existing signature state to edit, or null for a fresh signature. */
    val initialState: SignatureState? = null,
    /** Source of the existing signature (if editing). */
    val initialSource: SignatureSource = SignatureSource.DRAWN,
)

/**
 * Mutable state holder for the signature flow.
 *
 * On creation it hydrates itself from [config] so that editing
 * a signature loads its original strokes, color, width and render scale.
 */
class SignatureFlowState(
    val config: SignatureFlowConfig,
    val onConfirm: (state: SignatureState, bitmap: Bitmap) -> Unit,
    val onDismiss: () -> Unit,
) {
    // ── Current step ──
    private val _step = mutableStateOf(
        if (config.initialState != null) SignatureFlowStep.DRAW
        else SignatureFlowStep.SOURCE
    )

    // ── Draw state ──
    val strokes = mutableStateListOf<List<Offset>>()
    val undoneStrokes = mutableStateListOf<List<Offset>>()
    var strokeWidth = mutableFloatStateOf(3f)
    var strokeColor = mutableStateOf(ComposeColor(0xFF1A1A1A))
    var renderScale = mutableFloatStateOf(1.0f)

    // ── Canvas size for draw step ──
    var canvasSize = mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)

    var confirmedSource = mutableStateOf(SignatureSource.DRAWN)

    init {
        // Hydrate from existing state when editing.
        config.initialState?.let { state ->
            strokes.clear()
            state.strokes.forEach { serialized ->
                strokes.add(serialized.map { Offset(it.x, it.y) })
            }
            strokeWidth.floatValue = state.strokeWidth
            strokeColor.value = ComposeColor(state.strokeColorArgb.toInt())
            renderScale.floatValue = state.renderScale
            confirmedSource.value = state.source
        }
    }

    // ── Navigation helpers ──

    fun back(): Boolean {
        when (_step.value) {
            SignatureFlowStep.SOURCE -> {
                onDismiss()
                return false
            }

            SignatureFlowStep.DRAW -> _step.value = SignatureFlowStep.SOURCE
            SignatureFlowStep.CONFIRM -> _step.value = SignatureFlowStep.DRAW
        }
        return true
    }

    // ── Draw operations ──

    fun addStroke(points: List<Offset>) {
        undoneStrokes.clear()
        strokes.add(points)
    }

    fun undrawnStrokesClear() {
        undoneStrokes.clear()
    }

    fun undoDraw() {
        if (strokes.isNotEmpty()) {
            undoneStrokes.add(0, strokes.removeAt(strokes.lastIndex))
        }
    }

    fun redoDraw() {
        if (undoneStrokes.isNotEmpty()) {
            strokes.add(undoneStrokes.removeAt(0))
        }
    }

    fun clearDraw() {
        strokes.clear()
        undoneStrokes.clear()
    }

    fun hasDrawContent(): Boolean = strokes.isNotEmpty() || config.initialState != null

    // ── Confirm ──

    /**
     * Builds the complete [SignatureState] from the current editor state.
     * This is the single source of truth persisted and used to reconstruct
     * the signature later.
     */
    fun buildSignatureState(
        overlayScale: Float,
        positionFractionX: Float,
        positionFractionY: Float
    ): SignatureState {
        return SignatureState(
            strokes = strokes.map { stroke -> stroke.map { SerializableOffset(it.x, it.y) } },
            strokeWidth = strokeWidth.floatValue,
            strokeColorArgb = strokeColor.value.toArgb().toLong(),
            renderScale = renderScale.floatValue,
            overlayScale = overlayScale,
            positionFractionX = positionFractionX,
            positionFractionY = positionFractionY,
            source = confirmedSource.value,
        )
    }
}
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

import kotlinx.serialization.Serializable

/**
 * Serializable representation of a single point in a signature stroke.
 * Wraps Float coordinates so the whole stroke list can be serialized.
 */
@Serializable
data class SerializableOffset(
    val x: Float,
    val y: Float,
)

/**
 * Complete, persistent state of a hand-drawn signature.
 *
 * This is the single source of truth for a signature entity. It stores
 * everything needed to fully reconstruct the signature in the editor and
 * to re-render its bitmap at any time:
 *  - the strokes (drawing)
 *  - stroke width (thickness)
 *  - stroke color
 *  - editor render scale (size in the editor)
 *  - overlay scale (size of the overlay on the document)
 *  - position (fractional position on the page)
 *  - source (drawn or imported)
 *
 * Drawn signatures are reconstructed from [strokes] + [strokeWidth] +
 * [strokeColorArgb] + [renderScale]. Imported signatures instead store their
 * final PNG in [importedImageBytes], because their edits cannot be recreated
 * from strokes.
 */
@Serializable
data class SignatureState(
    val strokes: List<List<SerializableOffset>> = emptyList(),
    val strokeWidth: Float = 3f,
    /** Stroke color encoded as ARGB Long (Color.toArgb().toLong()). */
    val strokeColorArgb: Long = 0xFF1A1A1A,
    /** Scale used inside the signature editor (0.5 .. 2.0). */
    val renderScale: Float = 1.0f,
    /** Scale of the signature overlay on the document page (0.3 .. 3.0). */
    val overlayScale: Float = 1.0f,
    /** Fractional X position (0..1) of the overlay top-left corner. */
    val positionFractionX: Float = 0.05f,
    /** Fractional Y position (0..1) of the overlay top-left corner. */
    val positionFractionY: Float = 0.05f,
    val source: SignatureSource = SignatureSource.DRAWN,
    /** PNG-compressed image bytes for imported signatures. null = drawn. */
    val importedImageBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignatureState) return false
        return strokes == other.strokes && strokeWidth == other.strokeWidth &&
                strokeColorArgb == other.strokeColorArgb && renderScale == other.renderScale &&
                overlayScale == other.overlayScale && positionFractionX == other.positionFractionX &&
                positionFractionY == other.positionFractionY && source == other.source &&
                importedImageBytes.contentEquals(other.importedImageBytes)
    }

    override fun hashCode(): Int {
        var result = strokes.hashCode()
        result = 31 * result + strokeWidth.hashCode()
        result = 31 * result + strokeColorArgb.hashCode()
        result = 31 * result + renderScale.hashCode()
        result = 31 * result + overlayScale.hashCode()
        result = 31 * result + positionFractionX.hashCode()
        result = 31 * result + positionFractionY.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + (importedImageBytes?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * Minimum/maximum overlay scale. Unified across the whole app so that
         * buttons, pinch, corner handles and persistence all agree.
         */
        const val MIN_OVERLAY_SCALE = 0.3f
        const val MAX_OVERLAY_SCALE = 3.0f

    }
}
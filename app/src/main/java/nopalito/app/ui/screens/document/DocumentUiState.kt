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
import androidx.compose.ui.geometry.Offset
import nopalito.app.domain.PageToExport
import nopalito.app.domain.PageViewKey
import nopalito.app.ui.state.DocumentUiModel
import nopalito.imageprocessing.ColorMode

/** Background style for date overlay text. */
enum class DateBackgroundStyle { NONE, CAPSULE, RECTANGLE, SOFT_RECTANGLE }

/** Typed overlay identifier for selection state. */
enum class OverlayType { SIGNATURE, DATE }

/** Whether a signature overlay was hand-drawn or imported from photo/gallery. */
enum class SignatureSource { DRAWN, IMPORTED }

/** Style configuration for the date text overlay. */
data class DateOverlayStyle(
    val textColor: Long = 0xFFFFFFFF,
    val fontSize: Float = 14f,
    val backgroundStyle: DateBackgroundStyle = DateBackgroundStyle.CAPSULE,
    val backgroundColor: Long = 0x80000000,
)

/**
 * Overlays for a single page. Positions are fractional (0..1) relative
 * to the image preview container — use [] to convert
 * when rendering.
 *
 * Signature overlays are persisted via [signatureState], which is the
 * single source of truth for the signature entity. [signatureBitmap] is
 * a derived, in-memory-only cache regenerated from [signatureState].
 */
data class PageOverlays(
    /**
     * Complete persistent state of the signature (strokes, color, width,
     * scales, position, source). When non-null, a signature exists on
     * this page.
     */
    val signatureState: SignatureState? = null,
    /**
     * In-memory rendered bitmap of the signature, derived from
     * [signatureState]. Not persisted; regenerated on load.
     */
    val signatureBitmap: Bitmap? = null,
    /** Whether this signature was drawn or imported. */
    val signatureSource: SignatureSource = SignatureSource.DRAWN,
    /** Fractional Offset (0..1) of top‑left corner within the image area. */
    val signaturePositionFraction: Offset? = null,
    /** Scale factor for the signature overlay (0.3 .. 3.0). 1.0 = default size. */
    val signatureScale: Float = 1.0f,
    /** Continuous clockwise rotation in degrees (0..360) of the signature overlay. */
    val signatureRotationDegrees: Float = 0f,
    val dateText: String? = null,
    /** Fractional Offset (0..1) of top‑left corner within the image area. */
    val datePositionFraction: Offset? = null,
    /** Scale factor for the date overlay (0.5 .. 2.5). 1.0 = default size. */
    val dateScale: Float = 1.0f,
    /** Continuous clockwise rotation in degrees (0..360) of the date overlay. */
    val dateRotationDegrees: Float = 0f,
    /** Style configuration for the date overlay. */
    val dateStyle: DateOverlayStyle = DateOverlayStyle(),
)

data class DocumentUiState(
    val currentPageIndex: Int,
    val currentPage: CurrentPageUiState?,
    val document: DocumentUiModel,
    val pageOverlays: Map<String, PageOverlays> = emptyMap(),
)

data class CurrentPageUiState(
    val key: PageViewKey,
    val bitmap: Bitmap?,
    val colorMode: ColorMode?,
    val canBeCropped: Boolean = false,
    val isLoading: Boolean = false,
    val overlays: PageOverlays = PageOverlays(),
)

/**
 * Converts editor overlays into the export-pipeline form. Returns null when
 * there is nothing to bake (no signature and no date), so callers can skip
 * compositing entirely.
 */
fun PageOverlays.toPageExportOverlays(): PageToExport.PageExportOverlays? {
    val hasSignature = signatureBitmap != null && signaturePositionFraction != null
    val hasDate = !dateText.isNullOrBlank() && datePositionFraction != null
    if (!hasSignature && !hasDate) return null
    val ds = dateStyle
    return PageToExport.PageExportOverlays(
        signatureBitmap = signatureBitmap,
        signaturePositionFractionX = signaturePositionFraction?.x,
        signaturePositionFractionY = signaturePositionFraction?.y,
        signatureScale = signatureScale,
        signatureRotationDegrees = signatureRotationDegrees,
        dateText = dateText?.ifBlank { null },
        datePositionFractionX = datePositionFraction?.x,
        datePositionFractionY = datePositionFraction?.y,
        dateScale = dateScale,
        dateRotationDegrees = dateRotationDegrees,
        dateStyleTextColor = ds.textColor,
        dateStyleFontSize = ds.fontSize,
        dateStyleBackgroundStyle = ds.backgroundStyle.name,
        dateStyleBackgroundColor = ds.backgroundColor,
    )
}
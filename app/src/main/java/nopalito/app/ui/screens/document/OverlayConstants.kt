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

import android.graphics.Paint

/**
 * Shared overlay sizing constants used by both the editor (OverlayLayer)
 * and the export renderer (composeOverlaysOnBitmap).
 *
 * All size fractions are relative to the document/page image dimensions,
 * NOT to screen pixels or container size. This guarantees visual consistency
 * between editor preview and exported output.
 */
object OverlayConstants {

    /** Default signature width as a fraction of page image width (at scale=1). */
    const val SIGNATURE_WIDTH_FRACTION = 0.25f

    /** Default signature max height as a fraction of page image height (at scale=1). */
    const val SIGNATURE_HEIGHT_FRACTION = 0.20f

    /** Date font size as a fraction of page image width (at scale=1). */
    const val DATE_FONT_FRACTION = 0.03f

    /** Default value used by the date style editor. */
    const val DATE_DEFAULT_FONT_SIZE = 14f

    fun computeDateFontSizePx(
        imageWidth: Float,
        styleFontSize: Float,
        scale: Float = 1f,
    ): Float {
        return imageWidth * DATE_FONT_FRACTION *
                (styleFontSize / DATE_DEFAULT_FONT_SIZE).coerceAtLeast(0.1f) * scale
    }

    data class DateMetrics(
        val textWidthPx: Float,
        val ascentPx: Float,
        val descentPx: Float,
        val horizontalPaddingPx: Float,
        val verticalPaddingPx: Float,
        val cornerRadiusPx: Float,
    ) {
        val widthPx: Float
            get() = textWidthPx + horizontalPaddingPx * 2f

        val heightPx: Float
            get() = ascentPx + descentPx + verticalPaddingPx * 2f
    }

    /** Shared text and box metrics for the Compose and Canvas renderers. */
    fun dateMetrics(
        text: String,
        fontSizePx: Float,
        backgroundStyle: DateBackgroundStyle,
    ): DateMetrics {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            isFakeBoldText = true
        }
        val padding = when (backgroundStyle) {
            DateBackgroundStyle.NONE -> 0f to 0f
            DateBackgroundStyle.CAPSULE,
            DateBackgroundStyle.RECTANGLE,
            DateBackgroundStyle.SOFT_RECTANGLE ->
                fontSizePx * 0.25f to fontSizePx * 0.15f
        }
        val cornerRadius = when (backgroundStyle) {
            DateBackgroundStyle.NONE -> 0f
            DateBackgroundStyle.CAPSULE -> fontSizePx
            DateBackgroundStyle.RECTANGLE -> fontSizePx * 0.15f
            DateBackgroundStyle.SOFT_RECTANGLE -> fontSizePx * 0.4f
        }
        return DateMetrics(
            textWidthPx = paint.measureText(text),
            ascentPx = -paint.ascent(),
            descentPx = paint.descent(),
            horizontalPaddingPx = padding.first,
            verticalPaddingPx = padding.second,
            cornerRadiusPx = cornerRadius,
        )
    }

    /**
     * Computes the base display size for a signature bitmap, constrained
     * to fit within [maxW] × [maxH] while preserving aspect ratio.
     * Both editor and export must call this with the same fractional limits.
     */
    fun computeSignatureBaseSize(
        sigBmpW: Int,
        sigBmpH: Int,
        maxW: Float,
        maxH: Float,
    ): Pair<Float, Float> {
        val aspect = sigBmpW.toFloat() / sigBmpH.toFloat().coerceAtLeast(1f)
        val baseW: Float
        val baseH: Float
        if (maxW / maxH > aspect) {
            baseH = maxH
            baseW = baseH * aspect
        } else {
            baseW = maxW
            baseH = baseW / aspect
        }
        return baseW to baseH
    }

    /**
     * Axis-aligned visual size of a [w]×[h] rectangle rotated by [degrees].
     * Used by the editor (to place selection handles / clamp position) and by
     * the export renderer, so both always agree on where a rotated overlay
     * actually sits.
     */
    fun rotatedVisualSize(w: Float, h: Float, degrees: Float): Pair<Float, Float> {
        val rad = Math.toRadians(((degrees % 360f) + 360f) % 360f.toDouble())
        val c = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
        val s = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
        return (w * c + h * s) to (w * s + h * c)
    }
}
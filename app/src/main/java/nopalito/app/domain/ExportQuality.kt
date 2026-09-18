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

package nopalito.app.domain

import nopalito.app.R

/** Finite ceiling used before OpenCV allocates a full source Mat. */
const val MAX_FULL_RES_EXPORT_PIXELS = 12_500_000L

enum class ExportQuality(
    val jpegQuality: Int,
    val maxPixels: Long,
    val labelResource: Int,
) {
    /** Maximum processed resolution. Perspective, rotation and color are applied. */
    ORIGINAL(
        jpegQuality = 100,
        maxPixels = MAX_FULL_RES_EXPORT_PIXELS,
        R.string.export_quality_original,
    ),

    /**
     * High-resolution reprocessing from SourceOriginal (fallback Safe,
     * then stored processed for legacy docs). "High" means high-res
     * reprocessing, not lossless: Phase 1 still caps at 6 MP.
     */
    HIGH(
        jpegQuality = 85,
        maxPixels = 6_000_000,
        R.string.export_quality_high,
    ),

    /**
     * Balanced stored result (~2 MP with the current pipeline).
     * Capture may use up to 6 MP but processed output stays ~2 MP.
     */
    BALANCED(
        jpegQuality = 75,
        maxPixels = 2_000_000,
        R.string.export_quality_balanced,
    ),
    COMPRESSED(
        jpegQuality = 60,
        maxPixels = 1_000_000,
        R.string.export_quality_compressed,
    ),

    /**
     * Small preview/thumbnail for UI, not for archival export.
     */
    PREVIEW(
        jpegQuality = 60,
        maxPixels = 500_000,
        R.string.export_quality_compressed,
    ),

    /**
     * Legacy name kept for stored preferences/history. Alias of HIGH:
     * must not be used to generate a 0.5 MP image.
     */
    @Deprecated("Legacy alias of HIGH. Use HIGH or PREVIEW explicitly.")
    MAX_COMPRESSION(
        jpegQuality = 85,
        maxPixels = 6_000_000,
        R.string.export_quality_max_compression,
    ),
}
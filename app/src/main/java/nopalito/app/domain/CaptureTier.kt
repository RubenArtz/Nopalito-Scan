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

import nopalito.app.domain.CaptureTier.Companion.HIGH_SAFE_MAX_PIXELS


/**
 * Capture resolution tiers for Phase 1.
 *
 * Each tier separates three concerns that must not be confused:
 * - capture resolution (what CameraX is asked for),
 * - working resolution (downsampled input used only for segmentation/detection),
 * - processed resolution (output of the current pipeline, unchanged in Phase 1).
 *
 * LOW keeps the legacy behaviour (~2 MP). BALANCED asks the camera for more
 * pixels (up to 6 MP) but the processed result stays at ~2 MP so the visual
 * output does not change in Phase 1. HIGH reprocesses from the preserved
 * original up to the safe device limit (currently 6 MP, see [HIGH_SAFE_MAX_PIXELS]).
 * ORIGINAL preserves the CameraX file without app reprocessing and processes
 * a 2 MP copy for preview/editing with the current algorithm.
 */
enum class CaptureTier(
    val captureMaxPixels: Long,
    val workingLongSidePx: Int,
    val processedMaxPixels: Long,
) {
    LOW(
        captureMaxPixels = 2_000_000L,
        workingLongSidePx = 1280,
        processedMaxPixels = 2_000_000L,
    ),
    BALANCED(
        captureMaxPixels = 6_000_000L,
        workingLongSidePx = 1280,
        processedMaxPixels = 2_000_000L,
    ),
    HIGH(
        captureMaxPixels = Long.MAX_VALUE,
        workingLongSidePx = 1280,
        processedMaxPixels = 6_000_000L,
    ),
    ORIGINAL(
        captureMaxPixels = Long.MAX_VALUE,
        workingLongSidePx = 1280,
        processedMaxPixels = 2_000_000L,
    ),
    ;

    companion object {
        /**
         * Safe reprocessing ceiling for HIGH in Phase 1. HIGH means
         * "high-resolution reprocessing", not lossless: the current
         * pipeline still caps output at this value.
         */
        const val HIGH_SAFE_MAX_PIXELS = 6_000_000L

        /** Safety margin applied on top of the byte estimate before capture. */
        const val STORAGE_SAFETY_MULTIPLIER = 1.5

        /** Extra headroom kept free so the app never fills the disk. */
        const val STORAGE_MIN_FREE_BYTES = 100L * 1024L * 1024L

        fun fromName(name: String?): CaptureTier =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

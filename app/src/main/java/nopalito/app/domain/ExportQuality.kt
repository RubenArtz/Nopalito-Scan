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

enum class ExportQuality(
    val jpegQuality: Int,
    val maxPixels: Long,
    val labelResource: Int,
) {
    ORIGINAL(
        jpegQuality = 100,
        maxPixels = Long.MAX_VALUE,
        R.string.export_quality_original,
    ),
    HIGH(
        jpegQuality = 85,
        maxPixels = 6_000_000,
        R.string.export_quality_high,
    ),
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
    MAX_COMPRESSION(
        jpegQuality = 40,
        maxPixels = 500_000,
        R.string.export_quality_max_compression,
    ),
}
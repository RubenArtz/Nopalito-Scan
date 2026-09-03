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

package nopalito.imageprocessing

/** A back-facing camera as seen by the lens picker. */
data class LensSpec(
    val id: String,
    /** Shortest available focal length in millimetres. */
    val minFocalLengthMm: Double,
)

/**
 * Picks the ultra-wide (0.6x-ish) back camera among the available ones.
 *
 * The ultra-wide lens is the one with the smallest focal length, but only
 * when there is more than one back camera and the widest lens is clearly
 * wider than the longest one (at most 75% of it). This avoids classifying a
 * single standard lens as ultra-wide, which would silently break the
 * expectation of a real 0.6x field of view.
 *
 * @return the ultra-wide lens spec, or `null` when the device does not
 *   expose one that meets the criterion.
 */
fun pickUltraWideLens(lenses: List<LensSpec>): LensSpec? {
    if (lenses.size < 2) return null
    val widest = lenses.minByOrNull { it.minFocalLengthMm } ?: return null
    if (widest.minFocalLengthMm <= 0.0) return null
    val longestFocal = lenses.maxOf { it.minFocalLengthMm }
    if (longestFocal <= 0.0) return null
    val ratio = widest.minFocalLengthMm / longestFocal
    return if (ratio < 0.75) widest else null
}

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

/**
 * Single documented place where camera rotation and EXIF orientation meet.
 *
 * CameraX file captures expose orientation via EXIF while in-memory captures
 * expose [rotationDegrees] from [androidx.camera.core.ImageProxy]. OEMs are
 * inconsistent: some rotate pixels and write NORMAL, others keep pixels raw
 * and write EXIF 90/180/270. Both values are persisted separately and merged
 * exactly once here.
 *
 * @param rotationDegrees one of 0/90/180/270 from ImageProxy (0 for file captures).
 * @param exifOrientation EXIF orientation tag (1/3/6/8, 0/1 means undefined/NORMAL).
 */
fun resolveEffectiveOrientation(
    rotationDegrees: Int,
    exifOrientation: Int,
    pixelWidth: Int,
    pixelHeight: Int,
): Rotation {
    val exifDegrees = when (exifOrientation) {
        3 -> 180
        6 -> 90
        8 -> 270
        else -> 0
    }
    val total = ((rotationDegrees % 360 + 360) % 360 + exifDegrees) % 360
    // pixelWidth/pixelHeight are kept in the signature so callers handle the
    // already-rotated-pixels case explicitly and tests document OEM quirks.
    @Suppress("UNUSED_PARAMETER")
    val unused = pixelWidth + pixelHeight
    return Rotation.fromDegrees(total)
}

/** EXIF tag value matching [resolveEffectiveOrientation] output. */
fun rotationToExifOrientation(rotation: Rotation): Int =
    when (rotation) {
        Rotation.R0 -> 1
        Rotation.R90 -> 6
        Rotation.R180 -> 3
        Rotation.R270 -> 8
    }

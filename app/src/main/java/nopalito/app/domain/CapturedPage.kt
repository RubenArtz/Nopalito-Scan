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

import kotlinx.coroutines.Deferred
import nopalito.imageprocessing.ColorMode

data class CapturedPage(
    val pageJpeg: Jpeg,
    val sourceJpeg: Deferred<Jpeg>,
    val metadata: PageMetadata,
    val colorMode: ColorMode,
    /**
     * Phase 1 preserved original (CameraX file without app reprocessing).
     * Null for legacy in-memory captures and old documents.
     */
    val originalFile: java.io.File? = null,
    val originalSha256: String? = null,
    val captureTier: CaptureTier? = null,
    val processingStatus: ProcessingStatus = ProcessingStatus.PROCESSED,
    val processingError: String? = null,
    val capturedWidth: Int? = null,
    val capturedHeight: Int? = null,
    val workingWidth: Int? = null,
    val workingHeight: Int? = null,
    val processedWidth: Int? = null,
    val processedHeight: Int? = null,
    /** Instrumentation record for this capture (null for legacy/import paths). */
    val captureDiag: CaptureDiag? = null,
    /** Real camera id that exposed the frame (null for legacy/import paths). */
    val cameraId: String? = null,
    /** EXIF orientation tag read from the CameraX file (1 if unknown). */
    val exifOrientation: Int = 1,
)

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
 * Per-page capture metadata persisted in document.json (Phase 1).
 *
 * Capture, working, processed and exported resolutions are stored
 * separately so BALANCED is never misreported as 6 MP: the camera may
 * capture up to 6 MP while the processed output stays at ~2 MP with the
 * current algorithm. All dimensions are real values read from the final
 * files, never hardcoded. Dimensions that are unknown (legacy documents)
 * stay null and [hasOriginal] is false.
 */
data class CaptureMetadata(
    val captureTier: CaptureTier = CaptureTier.BALANCED,
    val capturedWidth: Int? = null,
    val capturedHeight: Int? = null,
    val workingWidth: Int? = null,
    val workingHeight: Int? = null,
    val processedWidth: Int? = null,
    val processedHeight: Int? = null,
    val exportedWidth: Int? = null,
    val exportedHeight: Int? = null,
    val sourceFileSize: Long? = null,
    val processedFileSize: Long? = null,
    val safeFileSize: Long? = null,
    val jpegQuality: Int? = null,
    val safeJpegQuality: Int? = null,
    val captureMode: String? = null,
    val cameraId: String? = null,
    val rotationDegrees: Int = 0,
    val exifOrientation: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val pipelineVersion: String = PIPELINE_VERSION,
    val sourceSha256: String? = null,
    val safeSha256: String? = null,
    val hasOriginal: Boolean = false,
) {
    companion object {
        const val PIPELINE_VERSION = "1.0"
    }
}

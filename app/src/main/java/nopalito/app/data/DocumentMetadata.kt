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

package nopalito.app.data

import kotlinx.serialization.Serializable
import nopalito.imageprocessing.ColorMode

@Serializable
data class DocumentMetadataV1(
    val version: Int = 1,
    val pages: List<PageV1>
)

@Serializable
data class PageV1(
    val file: String
)

@Serializable
data class DocumentMetadataV2(
    val version: Int = 2,
    val pages: List<PageV2> = emptyList(),
    /** Whether the current document is an INE (credential front/back) session. */
    val isIne: Boolean = false,
    /**
     * Schema version of this document file, documented separately from
     * [version] for forward compatibility. Phase 1 writes "2.1".
     */
    val schemaVersion: String = "2.1",
    /** Pipeline version that produced the pages (Phase 1 writes "1.0"). */
    val pipelineVersion: String = "1.0",
)

@Serializable
data class PageV2(
    val id: String,
    val baseRotationDegrees: Int = 0,
    val manualRotationDegrees: Int = 0,
    val quad: NormalizedQuad? = null,
    val quadVersion: Int = 0,
    val userQuad: NormalizedQuad? = null,
    val isColored: Boolean? = null,
    val colorMode: ColorMode? = null,
    val focalLength: Float? = null,
    val sensorWidth: Float? = null,
    val subjectDistance: Float? = null,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    // Phase 1 traceability: capture vs working vs processed vs exported are
    // stored separately with real file values, never hardcoded.
    val captureTier: String? = null,
    val originalCaptureTier: String? = null,
    val capturedWidth: Int? = null,
    val capturedHeight: Int? = null,
    val workingWidth: Int? = null,
    val workingHeight: Int? = null,
    val processedWidth: Int? = null,
    val processedHeight: Int? = null,
    val exportedWidth: Int? = null,
    val exportedHeight: Int? = null,
    val sourceFile: String? = null,
    val safeFile: String? = null,
    val processedFile: String? = null,
    val sourceFileSize: Long? = null,
    val safeFileSize: Long? = null,
    val processedFileSize: Long? = null,
    val sourceSha256: String? = null,
    val safeSha256: String? = null,
    val safeJpegQuality: Int? = null,
    val jpegQuality: Int? = null,
    val captureMode: String? = null,
    val cameraId: String? = null,
    val rotationDegrees: Int? = null,
    val exifOrientation: Int? = null,
    val timestamp: Long? = null,
    val pipelineVersion: String? = null,
    val hasOriginal: Boolean = false,
    val processingStatus: String = "PROCESSED",
    val processingError: String? = null,
    val activeVariantId: String? = null,
    val baselineVariantId: String? = null,
    val candidateVariantId: String? = null,
    val variantsVersion: Int? = null,
    val processingRecipeHash: String? = null,
    val algorithmId: String? = null,
    val regionId: String? = null,
    /**
     * Reserved for finger-removal reconstruction (Phase 4). Always null in
     * Phase 1; kept nullable so old readers ignore it.
     */
    val reconstructedRegions: List<RectD>? = null,
)

@Serializable
data class NormalizedQuad(
    val topLeft: PointD,
    val topRight: PointD,
    val bottomRight: PointD,
    val bottomLeft: PointD
)

@Serializable
data class PointD(
    val x: Double,
    val y: Double
)

@Serializable
data class RectD(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

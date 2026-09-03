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

package nopalito.app.ui.screens.export

import android.net.Uri
import nopalito.app.R
import nopalito.app.domain.ExportQuality

enum class ExportArtifactType { FILE, FOLDER }

/**
 * How large the composed INE credential should appear on the exported sheet.
 * The INE capture is front-on-top / back-below on one page; this controls the
 * white margin around it so it fills most of the sheet (200%) or sits smaller.
 */
enum class IneExportScale(val labelResource: Int, val fillFraction: Float) {
    /** INE at ~200%: nearly fills the sheet with a thin margin. */
    DOUBLE_200(R.string.ine_export_scale_200, fillFraction = 0.92f),

    /** INE at a reasonable, smaller size. */
    NORMAL(R.string.ine_export_scale_normal, fillFraction = 0.55f),
}

/**
 * Final model of a completed export: a single file or the container folder
 * of a multiple image export.
 */
data class ExportArtifact(
    val type: ExportArtifactType,
    val format: ExportFormat,
    val displayName: String,
    val itemCount: Int,
    val sizeInBytes: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val path: String? = null,
    val uri: Uri? = null,
    val folderUri: Uri? = null,
    val children: List<ExportArtifact> = emptyList(),
)

data class ExportUiState(
    val format: ExportFormat = ExportFormat.PDF,
    val quality: ExportQuality = ExportQuality.BALANCED,
    val filename: String = "",
    /** Optional password protection applied to PDF / Word exports. */
    val protectWithPassword: Boolean = false,
    val password: String = "",
    val isGenerating: Boolean = false,
    val progress: ExportProgress? = null,
    val ocrActivation: Boolean? = null,
    val isSaving: Boolean = false,
    val result: ExportResult? = null,
    val savedBundle: SavedBundle? = null,
    val hasShared: Boolean = false,
    val error: ExportError? = null,
    val isUploadingToCloud: Boolean = false,
    val cloudUploadSuccess: Boolean? = null,
    val cloudUploadError: String? = null,
    val isCloudAuthAvailable: Boolean = false,
    /** Biometric mode with a locked session (NeedsUnlock): a prompt must
     *  run before any cloud API call succeeds. */
    val needsCloudUnlock: Boolean = false,
    /** Opt-in toggle (same policy as the tools): upload only happens on save
     *  when the user explicitly enabled it, or taps "Upload now" afterwards. */
    val cloudUploadEnabled: Boolean = false,
    /** Cloud grouping of the export (a single id per export). */
    val cloudExportId: String? = null,
    /** When true, the document is an INE session and the composed sheet is exported. */
    val isIneDocument: Boolean = false,
    /** Size of the composed INE on the sheet (200% vs normal), only relevant in INE mode. */
    val ineExportScale: IneExportScale = IneExportScale.DOUBLE_200,
) {
    val hasSavedOrShared get() = savedBundle != null || hasShared
}

data class ExportProgress(
    val completedPages: Int,
    val totalPages: Int,
) {
    val progress = completedPages.toFloat() / totalPages
}

data class SavedItem(
    val uri: Uri,
    val fileName: String,
    val format: ExportFormat,
)

data class SavedBundle(
    val items: List<SavedItem>,
    val saveDir: SaveDir? = null,
    /** Name of the container folder (only for multiple JPEG export). */
    val folderName: String? = null,
    /** URI of the DocumentFile of the folder created via SAF (null if MediaStore). */
    val folderUri: Uri? = null,
) {
    val isFolderExport get() = folderName != null
}

data class SaveDir(
    val uri: Uri,
    val name: String?,
)

sealed class ExportError {

    data class OnPrepareOrShare(
        val message: String,
        val throwable: Throwable,
    ) : ExportError()

    data class OnSave(
        val messageRes: Int,
        val saveDir: SaveDir?,
        val throwable: Throwable? = null,
    ) : ExportError()
}
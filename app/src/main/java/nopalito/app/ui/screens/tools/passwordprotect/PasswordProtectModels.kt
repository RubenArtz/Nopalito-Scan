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

package nopalito.app.ui.screens.tools.passwordprotect

import android.net.Uri
import nopalito.app.R
import nopalito.app.ui.screens.tools.BatchMode
import nopalito.app.ui.screens.tools.CompressTool
import nopalito.app.ui.screens.tools.OriginalFileAction
import nopalito.app.ui.screens.tools.PickedFile
import nopalito.app.ui.screens.tools.shared.PreviewFileState

/**
 * File types supported by the "Protect with password" tool.
 * Each type knows its MIME types, valid extensions and the equivalent
 * compressor tool (for the "Send to compression" flow).
 */
enum class ProtectedFileType(
    val titleRes: Int,
    val compressTool: CompressTool,
    val mimeTypes: Array<String>,
    val extensions: Set<String>,
) {
    PDF(
        titleRes = R.string.pp_file_pdf,
        compressTool = CompressTool.PDF,
        mimeTypes = arrayOf("application/pdf"),
        extensions = setOf("pdf"),
    ),
    WORD(
        titleRes = R.string.pp_file_word,
        compressTool = CompressTool.WORD,
        mimeTypes = arrayOf(
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ),
        extensions = setOf("doc", "docx"),
    ),
}

/** Result of protecting a file (or a batch). */
data class PasswordProtectResult(
    val fileName: String,
    /** Content uri used to open/share the protected file. */
    val outputUri: Uri?,
    val sizeBytes: Long,
    /** Uri of the folder that contains the batch (only when several files). */
    val batchFolderUri: Uri? = null,
    val cloudUploadSuccess: Boolean? = null,
    val cloudUploadError: String? = null,
)

data class PasswordProtectUiState(
    val fileType: ProtectedFileType = ProtectedFileType.PDF,
    val batchMode: BatchMode = BatchMode.INDIVIDUAL,
    val files: List<PickedFile> = emptyList(),
    val password: String = "",
    /** Suggested password waiting for the user to confirm. */
    val generatedPassword: String? = null,
    val generateDialogVisible: Boolean = false,
    /** What to do with the original file after protecting. */
    val originalAction: OriginalFileAction = OriginalFileAction.KEEP,
    /** Output destination: mirrors the destination configured in Settings. */
    val saveLocationName: String? = null,
    val saveLocationUri: String? = null,
    val isAuthenticated: Boolean = false,
    val cloudUploadEnabled: Boolean = false,
    val premiumDialogVisible: Boolean = false,
    val isProcessing: Boolean = false,
    val results: List<PasswordProtectResult> = emptyList(),
    val errorMessage: String? = null,
    /** Page preview (read-only thumbnails): single-file page count; 0 = none. */
    val previewPageCount: Int = 0,
    /** True while a preview is being generated (Word via the backend). */
    val isPreviewLoading: Boolean = false,
    /** True when the preview could not be generated (the tool still works). */
    val previewFailed: Boolean = false,
    /** True when the picked file is password-protected and cannot be previewed. */
    val previewProtected: Boolean = false,
    /** Per-file previews shown in batch mode (one entry per picked file). */
    val previewBatch: List<PreviewFileState> = emptyList(),
)

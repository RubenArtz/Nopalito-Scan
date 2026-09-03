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

package nopalito.app.ui.screens.tools.convert

import android.net.Uri
import nopalito.app.ui.screens.tools.BatchMode
import nopalito.app.ui.screens.tools.OriginalFileAction
import nopalito.app.ui.screens.tools.PickedFile
import nopalito.app.ui.screens.tools.shared.PreviewFileState
import java.io.File

/**
 * Input MIME types accepted by the "Convert to PDF" tool. The server-side
 * backend (LibreOffice) processes all of these formats and produces a PDF,
 * so there is no need to choose a conversion direction.
 */
val CONVERT_MIME_TYPES: Array<String> = arrayOf(
    "text/plain",
    "text/rtf",
    "application/rtf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.oasis.opendocument.spreadsheet",
    "text/csv",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.oasis.opendocument.presentation",
    "application/octet-stream",
)

/** File extensions accepted by the "Convert to PDF" tool. */
val CONVERT_EXTENSIONS: Set<String> = setOf(
    "txt", "rtf", "doc", "docx", "odt", "fodt",
    "xls", "xlsx", "ods", "csv",
    "ppt", "pptx", "odp",
)

/** Execution phase of a server-side (LibreOffice) DOC → PDF conversion. */
enum class ConvertPhase {
    IDLE,
    SELECTING,
    UPLOADING,
    PROCESSING,
    DOWNLOADING,
    COMPLETED,
    PARTIAL,
    FAILED,
}

/** Result of converting one file (or one item of a batch). */
data class ConvertResult(
    val fileName: String,
    /** Content uri used to open/share the converted file. */
    val outputUri: Uri?,
    val sizeBytes: Long,
    /** Uri of the folder that contains the batch (only when several files). */
    val batchFolderUri: Uri? = null,
    val cloudUploadSuccess: Boolean? = null,
    val cloudUploadError: String? = null,
    /** Per-file failure message (partial batch success keeps processing the rest). */
    val error: String? = null,
)

/**
 * A converted PDF kept in the app cache until the user saves it. The file is
 * written to the selected destination only when the user presses Save or
 * Share, and it can be saved again as many times as wanted.
 */
data class CachedConvert(
    val fileName: String,
    val file: File,
    val sizeBytes: Long,
    /** Original picked file, deleted when the action is REPLACE. */
    val sourceUri: Uri?,
    /**
     * Password-protected copy of [file], cached so that saving/sharing/opening
     * again does not re-encrypt the PDF. It is (re)generated only when the
     * entered password changes; null = no protection applied yet.
     */
    val protectedFile: File? = null,
    /** Password that [protectedFile] was encrypted with ("" = not protected). */
    val protectedWithPassword: String = "",
)

/** One-shot action executed after the cached files are saved. */
enum class ResultAction {
    SHARE,
    OPEN,
}

data class ConvertUiState(
    val batchMode: BatchMode = BatchMode.INDIVIDUAL,
    val files: List<PickedFile> = emptyList(),
    /** What to do with the original file after converting. */
    val originalAction: OriginalFileAction = OriginalFileAction.KEEP,
    /** Output destination: mirrors the destination configured in Settings. */
    val saveLocationName: String? = null,
    val saveLocationUri: String? = null,
    val isConverting: Boolean = false,
    /** Overall progress (0..1) reported by the converters, or null when idle. */
    val progress: Float? = null,
    /** Number of files already processed so the UI can show "2 of 5". */
    val convertedCount: Int = 0,
    /** Execution phase of a server-side DOC → PDF conversion. */
    val phase: ConvertPhase = ConvertPhase.IDLE,
    /** Converted PDFs kept in cache, not yet written to the destination. */
    val cached: List<CachedConvert> = emptyList(),
    /** Results written to the destination (set once the user saves). */
    val results: List<ConvertResult> = emptyList(),
    val errorMessage: String? = null,
    /** Optional password protection for the converted PDF (applied on save, like other tools). */
    val passwordEnabled: Boolean = false,
    /** Password applied when [passwordEnabled] is true (blank = no protection). */
    val password: String = "",
    val generatedPassword: String? = null,
    val generateDialogVisible: Boolean = false,
    val isSaving: Boolean = false,
    /** Action (share/open) to run once the current save finishes. */
    val pendingAction: ResultAction? = null,
    val cloudUploadEnabled: Boolean = false,
    val isAuthenticated: Boolean = false,
    val premiumDialogVisible: Boolean = false,
    /** Whether the current conversion round has been recorded in history. */
    val historyRecorded: Boolean = false,
    /** Page preview (read-only thumbnails): single-file page count; 0 = none. */
    val previewPageCount: Int = 0,
    /** True while a preview is being generated (backend conversion). */
    val isPreviewLoading: Boolean = false,
    /** True when the preview could not be generated (the tool still works). */
    val previewFailed: Boolean = false,
    /** True when the picked file is password-protected and cannot be previewed. */
    val previewProtected: Boolean = false,
    /** Per-file previews shown in batch mode (one entry per picked file). */
    val previewBatch: List<PreviewFileState> = emptyList(),
)
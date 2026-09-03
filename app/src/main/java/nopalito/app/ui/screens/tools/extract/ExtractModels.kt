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

package nopalito.app.ui.screens.tools.extract

import android.net.Uri
import nopalito.app.ui.screens.tools.PickedFile

/** Export format of the "Extract pages" tool. */
enum class ExtractExportMode { PDF, IMAGES }

/** One exported file (or, for an image export, one image of the batch). */
data class ExtractResult(
    val fileName: String,
    /** Content uri used to open/share the exported file. */
    val outputUri: Uri?,
    val sizeBytes: Long,
    /** Folder that contains the batch (image exports always set it). */
    val batchFolderUri: Uri? = null,
    /** How many pages/files this result represents. */
    val itemCount: Int = 1,
    val cloudUploadSuccess: Boolean? = null,
    val cloudUploadError: String? = null,
    val error: String? = null,
)

data class ExtractUiState(
    /** The single picked PDF (this tool works on one document). */
    val files: List<PickedFile> = emptyList(),
    /** True once a valid PDF is open and ready to preview/export. */
    val isLoaded: Boolean = false,
    /** True while the picked PDF is being opened and counted. */
    val isLoading: Boolean = false,
    val fileName: String = "",
    /** Total pages of the loaded document. */
    val pageCount: Int = 0,
    /** Page-range expression typed by the user. */
    val rangeInput: String = "",
    /** Parsed + validated range, recomputed on every keystroke. */
    val parse: PageRangeResult = PageRangeResult.Empty,
    /** Export format chosen by the user (the password option only applies to PDF). */
    val exportMode: ExtractExportMode = ExtractExportMode.PDF,
    /** Protection switch state (only relevant for the PDF export). */
    val passwordEnabled: Boolean = false,
    val password: String = "",
    /** Suggested password waiting for the user to confirm. */
    val generatedPassword: String? = null,
    val generateDialogVisible: Boolean = false,
    /** Editable output file name used for the PDF export. */
    val outputFileName: String = "",
    /** Output destination: mirrors the destination configured in Settings. */
    val saveLocationName: String? = null,
    val saveLocationUri: String? = null,
    val isAuthenticated: Boolean = false,
    val cloudUploadEnabled: Boolean = false,
    val premiumDialogVisible: Boolean = false,
    val isProcessing: Boolean = false,
    /** Overall export progress (0..1) while exporting images; null for PDF. */
    val progress: Float? = null,
    /** Sub-label shown next to the progress bar. */
    val progressLabel: String? = null,
    val results: List<ExtractResult> = emptyList(),
    val errorMessage: String? = null,
    /** Non-blocking notices (e.g. explicit `a/b` total mismatch). */
    val infoMessages: List<String> = emptyList(),
)

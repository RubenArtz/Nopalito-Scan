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

package nopalito.app.ui.screens.tools.reorder

import android.net.Uri

/** One exported file of the "Reorder PDF pages" tool. */
data class ReorderResult(
    val fileName: String,
    /** Content uri used to open/share the exported file. */
    val outputUri: Uri?,
    val sizeBytes: Long,
    /** How many pages the reordered PDF contains. */
    val pageCount: Int,
    /** True when the exported PDF was encrypted with a password. */
    val protected: Boolean = false,
    /** Cloud upload outcome (null when upload was not requested). */
    val cloudUploadSuccess: Boolean? = null,
    val cloudUploadError: String? = null,
    val error: String? = null,
)

data class ReorderUiState(
    /** Name of the picked PDF ("" until one is chosen). */
    val fileName: String = "",
    val fileUri: Uri? = null,
    val sizeBytes: Long = 0,
    /** True while the picked PDF is being opened and counted. */
    val isLoading: Boolean = false,
    /** True once a valid PDF is open and ready to preview/export. */
    val isLoaded: Boolean = false,
    /** Total pages of the loaded document. */
    val pageCount: Int = 0,
    /**
     * Current page order. Every entry is the **original** 1-based page number,
     * e.g. [3, 1, 2] means the original page 3 comes first in the final PDF.
     * It is the single observable source of truth: thumbnails and the preview
     * are derived from it, and only the export builds the physical PDF.
     */
    val pageOrder: List<Int> = emptyList(),
    /** Index (position in [pageOrder]) of the page shown in the main preview. */
    val selectedPreviewIndex: Int = 0,
    /** Editable output file name used for the export. */
    val outputFileName: String = "",
    /** Protection switch state (encrypts the exported PDF). */
    val passwordEnabled: Boolean = false,
    val password: String = "",
    /** Suggested password waiting for the user to confirm. */
    val generatedPassword: String? = null,
    val generateDialogVisible: Boolean = false,
    /** Cloud upload toggle, gated on having an active cloud session. */
    val isAuthenticated: Boolean = false,
    val cloudUploadEnabled: Boolean = false,
    val premiumDialogVisible: Boolean = false,
    /** Output destination: mirrors the destination configured in Settings. */
    val saveLocationName: String? = null,
    val saveLocationUri: String? = null,
    val isProcessing: Boolean = false,
    /** Export progress (0..1) while generating the reordered PDF. */
    val progress: Float? = null,
    /** Sub-label shown next to the progress bar. */
    val progressLabel: String? = null,
    val results: List<ReorderResult> = emptyList(),
    val errorMessage: String? = null,
)
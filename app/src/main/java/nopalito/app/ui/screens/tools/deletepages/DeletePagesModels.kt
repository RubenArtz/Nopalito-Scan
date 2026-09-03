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

package nopalito.app.ui.screens.tools.deletepages

import android.net.Uri

/**
 * Classification of a page during the blank-page analysis, from the combined
 * strategy (PDF structure signals + low-resolution render pixel analysis).
 */
enum class BlankPageStatus {
    /** No text, no images/graphics and almost no non-white pixels. */
    BLANK,

    /** No strong content (text/image) but some faint marks: needs user review. */
    LIKELY_BLANK,

    /** The page carries text, images, graphics or enough painted pixels. */
    HAS_CONTENT,
}

/** One page reported by the blank-page analysis. */
data class BlankPageInfo(
    /** The **original** 1-based page number (as used by [DeletePagesUiState.pageOrder]). */
    val originalPageNumber: Int,
    val status: BlankPageStatus,
)

/**
 * Snapshot pushed onto the undo stack before a deletion is applied. Restoring
 * it puts back the page order and the preview selection, which makes the
 * thumbnails and the preview recompose to exactly the previous state (both are
 * derived from `pageOrder`).
 */
data class DeletePagesUndoEntry(
    val pageOrder: List<Int>,
    val selectedPreviewIndex: Int,
    /** How many pages that deletion removed (shown next to the undo button). */
    val deletedCount: Int,
)

/** One exported file of the "Delete PDF pages" tool. */
data class DeletePagesResult(
    val fileName: String,
    /** Content uri used to open/share the exported file. */
    val outputUri: Uri?,
    val sizeBytes: Long,
    /** How many pages the exported PDF contains. */
    val pageCount: Int,
    /** True when the exported PDF was encrypted with a password. */
    val protected: Boolean = false,
    /** Cloud upload outcome (null when upload was not requested). */
    val cloudUploadSuccess: Boolean? = null,
    val cloudUploadError: String? = null,
    val error: String? = null,
)

data class DeletePagesUiState(
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
     * Pages removed by a deletion simply disappear from this list (undo keeps
     * a snapshot of the previous list). It is the single observable source of
     * truth: thumbnails and the preview are derived from it, and only the
     * export builds the physical PDF.
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
    /** Export progress (0..1) while generating the PDF. */
    val progress: Float? = null,
    /** Sub-label shown next to the progress bar. */
    val progressLabel: String? = null,
    val results: List<DeletePagesResult> = emptyList(),
    val errorMessage: String? = null,
    /**
     * True while the "delete pages" selection mode is active: thumbnails can
     * be marked/unmarked instead of being dragged, and the trash button
     * applies the deletion with a confirmation dialog.
     */
    val deleteMode: Boolean = false,
    /**
     * Original 1-based page numbers marked for deletion while [deleteMode]
     * is active (the actual removal only happens on confirmation).
     */
    val markedForDeletion: Set<Int> = emptySet(),
    /** True while the delete confirmation dialog is showing. */
    val deleteDialogVisible: Boolean = false,
    /** Snapshots of the state before each applied deletion (undo history). */
    val undoStack: List<DeletePagesUndoEntry> = emptyList(),
    /** True while the blank-page analysis is running (cancellable). */
    val isAnalyzingBlanks: Boolean = false,
    /** Analysis progress (0..1) while scanning for blank pages. */
    val blankProgress: Float? = null,
    /** "Analizando página X de Y" while the analysis runs. */
    val blankProgressLabel: String? = null,
    /** Detected blank pages waiting for the user to review and confirm. */
    val blankResult: List<BlankPageInfo>? = null,
)
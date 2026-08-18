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

package nopalito.app.ui.screens.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "export_history")
data class ExportHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentName: String,
    val dateTime: Long,
    val pageCount: Int,
    val format: String, // "PDF", "JPEG" or "DOCX"
    val quality: String,
    val fileSizeBytes: Long,
    val exportCount: Int = 1,
    val thumbnailPath: String? = null,
    val originalDocumentPath: String? = null,
    val exportedFilePath: String? = null,
    val status: String = STATUS_AVAILABLE, // "AVAILABLE" or "DELETED"
    // App-private backup of the exported file/folder: the history keeps its
    // own copy so previews/opening work even after the file is deleted from
    // Downloads. Single file -> backupPath, multi-image folder -> backupDirPath.
    val backupPath: String? = null,
    val backupDirPath: String? = null,
    // ── Multiple export (final result ExportArtifact) ──
    val resultType: String = RESULT_TYPE_FILE, // "FILE" or "FOLDER"
    val exportedFolderUri: String? = null,     // uri of the DocumentFile of the folder (SAF)
    val exportedItemCount: Int = 1,            // number of files saved
    val childrenUris: String? = null,          // uris of the children, separated by "\n"
    val exportId: String? = null,              // cloud grouping (one per export)
) {
    companion object {
        const val STATUS_AVAILABLE = "AVAILABLE"
        const val RESULT_TYPE_FILE = "FILE"
        const val RESULT_TYPE_FOLDER = "FOLDER"
    }
}
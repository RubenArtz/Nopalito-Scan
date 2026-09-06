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
import androidx.core.net.toUri
import nopalito.app.ui.screens.history.ExportHistoryEntity
import java.io.File

/**
 * Converts the intermediate export models to the final model
 * [ExportArtifact]. Single conversion point used by the export screen,
 * the history, and result opening.
 */
object ExportArtifactMapper {

    /**
     * Maps a save ([SavedBundle]) — and optionally its generation result
     * ([ExportResult] for exact sizes) — to the final model.
     * Single PDF/Word/JPEG -> FILE. Multiple JPEG -> FOLDER.
     */
    fun fromBundle(bundle: SavedBundle, result: ExportResult? = null): ExportArtifact {
        val first = bundle.items.firstOrNull()
            ?: return ExportArtifact(
                type = ExportArtifactType.FILE,
                format = ExportFormat.PDF,
                displayName = "",
                itemCount = 0,
                sizeInBytes = 0,
            )
        return if (bundle.folderName != null) {
            ExportArtifact(
                type = ExportArtifactType.FOLDER,
                format = first.format,
                displayName = bundle.folderName,
                itemCount = bundle.items.size,
                sizeInBytes = result?.sizeInBytes ?: 0,
                uri = first.uri,
                folderUri = bundle.folderUri,
                children = bundle.items.map { item ->
                    ExportArtifact(
                        type = ExportArtifactType.FILE,
                        format = item.format,
                        displayName = item.fileName,
                        itemCount = 1,
                        sizeInBytes = 0,
                        uri = item.uri,
                    )
                },
            )
        } else {
            ExportArtifact(
                type = ExportArtifactType.FILE,
                format = first.format,
                displayName = first.fileName,
                itemCount = 1,
                sizeInBytes = result?.sizeInBytes ?: 0,
                uri = first.uri,
            )
        }
    }

    /**
     * Maps a history entry to the final model, or null if there is no
     * saved destination (e.g. exports that were only shared).
     */
    fun fromHistoryEntity(entity: ExportHistoryEntity): ExportArtifact? {
        val format = when (entity.format) {
            "PDF" -> ExportFormat.PDF
            "DOCX" -> ExportFormat.WORD
            else -> ExportFormat.JPEG
        }
        if (entity.resultType == "FOLDER") {
            val children = entity.childrenUris
                ?.split("\n")
                ?.map { uri ->
                    ExportArtifact(
                        type = ExportArtifactType.FILE,
                        format = format,
                        displayName = uri.substringAfterLast('/'),
                        itemCount = 1,
                        sizeInBytes = 0,
                        uri = uri.toUri(),
                    )
                }
                ?: emptyList()
            return ExportArtifact(
                type = ExportArtifactType.FOLDER,
                format = format,
                displayName = entity.documentName,
                itemCount = entity.exportedItemCount,
                sizeInBytes = entity.fileSizeBytes,
                createdAt = entity.dateTime,
                uri = entity.exportedFilePath?.toUri()
                    ?: entity.backupDirPath?.let { dir ->
                        File(dir).listFiles()?.firstOrNull()?.let { Uri.fromFile(it) }
                    },
                folderUri = entity.exportedFolderUri?.toUri(),
                children = children,
            )
        }
        // Prefer the private backup copy: it survives deletion from Downloads and
        // is readable via FileProvider (openUri converts file:// to a provider uri).
        val uri = entity.backupPath?.let { Uri.fromFile(File(it)) }
            ?: entity.exportedFilePath?.takeIf { it.isNotBlank() }?.toUri()
            ?: return null
        return ExportArtifact(
            type = ExportArtifactType.FILE,
            format = format,
            displayName = entity.documentName,
            itemCount = 1,
            sizeInBytes = entity.fileSizeBytes,
            createdAt = entity.dateTime,
            path = entity.backupPath,
            uri = uri,
        )
    }
}

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

package nopalito.app.ui.screens.camera

import nopalito.app.ui.screens.cloud.model.CloudFile
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportCandidatesTest {

    private fun file(
        id: String,
        name: String,
        mime: String? = null,
        itemType: String? = "file",
    ) = CloudFile(
        id = id,
        originalName = name,
        mimeType = mime,
        itemType = itemType,
    )

    @Test
    fun `folders pass through untouched even with non-importable names`() {
        val folders = listOf(
            file("f1", "hola", itemType = "folder"),
            // A folder named like an incompatible file must still be shown.
            file("f2", "backup.zip", itemType = "folder"),
            file("f3", "noextension", itemType = "folder"),
        )
        val (keptFolders, keptFiles) = partitionForImport(folders, emptyList())
        assertEquals(folders, keptFolders)
        assertEquals(emptyList<CloudFile>(), keptFiles)
    }

    @Test
    fun `only importable files are kept in backend order`() {
        val files = listOf(
            file("a", "scan.jpg", "image/jpeg"),
            file("b", "movie.mp4", "video/mp4"),
            file("c", "doc.pdf", "application/pdf"),
            file("d", "notes", null),
            file("e", "letter.docx", null),
        )
        val (_, keptFiles) = partitionForImport(emptyList(), files)
        assertEquals(listOf("a", "c", "e"), keptFiles.map { it.id })
    }

    @Test
    fun `empty input stays empty`() {
        val (folders, files) = partitionForImport(emptyList(), emptyList())
        assertEquals(emptyList<CloudFile>(), folders)
        assertEquals(emptyList<CloudFile>(), files)
    }
}
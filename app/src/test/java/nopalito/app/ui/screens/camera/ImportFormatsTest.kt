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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportFormatsTest {

    @Test
    fun `images are importable by mime or extension`() {
        assertTrue(isImportableFile("photo.jpg", "image/jpeg"))
        assertTrue(isImportableFile("scan", "image/png"))
        assertTrue(isImportableFile("photo.webp", null))
        assertTrue(isImportableFile("shot.HEIC", "application/octet-stream"))
    }

    @Test
    fun `pdf is importable by mime or extension`() {
        assertTrue(isImportableFile("doc.pdf", "application/pdf"))
        assertTrue(isImportableFile("doc.pdf", null))
    }

    @Test
    fun `word compatible documents are importable`() {
        assertTrue(isImportableFile("letter.docx", null))
        assertTrue(isImportableFile("notes.odt", "application/vnd.oasis.opendocument.text"))
        assertTrue(isImportableFile("old.doc", "application/msword"))
        assertTrue(isImportableFile("read.rtf", null))
        assertTrue(isImportableFile("plain.txt", "text/plain"))
    }

    @Test
    fun `unknown and extensionless files are rejected`() {
        assertFalse(isImportableFile("archive.zip", "application/zip"))
        assertFalse(isImportableFile("movie.mp4", "video/mp4"))
        assertFalse(isImportableFile("noextension", null))
        assertFalse(isImportableFile("noextension", "application/octet-stream"))
        assertFalse(isImportableFile("", null))
    }
}
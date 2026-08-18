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

package nopalito.app.ui.screens.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CompressionEngineTest {

    @Test
    fun rezipDocx_preservesEntriesAndContent() {
        val tempDir = File.createTempFile("tools_test", "").parentFile!!
        val input = File(tempDir, "input.docx")
        val output = File(tempDir, "output.docx")
        try {
            ZipOutputStream(input.outputStream()).use { zout ->
                zout.putNextEntry(ZipEntry("word/document.xml"))
                zout.write("<w:document>contenido</w:document>".toByteArray())
                zout.closeEntry()
                zout.putNextEntry(ZipEntry("word/media/image1.png"))
                zout.write(ByteArray(512) { it.toByte() })
                zout.closeEntry()
            }

            rezipDocx(input, output)

            assertTrue(output.exists())
            assertEquals(
                setOf("word/document.xml", "word/media/image1.png"),
                readEntryNames(output).toSet(),
            )
            val document = readEntryBytes(output, "word/document.xml")
                .toString(Charsets.UTF_8)
            assertEquals("<w:document>contenido</w:document>", document)
        } finally {
            input.delete()
            output.delete()
        }
    }

    private fun readEntryNames(file: File): List<String> =
        ZipInputStream(file.inputStream().buffered()).use { zin ->
            val names = mutableListOf<String>()
            var entry = zin.nextEntry
            while (entry != null) {
                names.add(entry.name)
                zin.closeEntry()
                entry = zin.nextEntry
            }
            names
        }

    private fun readEntryBytes(file: File, name: String): ByteArray =
        ZipInputStream(file.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name == name) return@use zin.readBytes()
                zin.closeEntry()
                entry = zin.nextEntry
            }
            ByteArray(0)
        }
}

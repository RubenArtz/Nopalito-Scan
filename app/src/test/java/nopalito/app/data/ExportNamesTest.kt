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

package nopalito.app.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.*

class ExportNamesTest {

    @Test
    fun `sanitizeFileName replaces invalid characters with underscore`() {
        assertEquals(
            "Scan_1_2_3_4_5_6_7",
            ExportNames.sanitizeFileName("Scan/1:2*3\"4<5>6|7", "fallback")
        )
    }

    @Test
    fun `sanitizeFileName trims whitespace and collapses runs`() {
        assertEquals("name", ExportNames.sanitizeFileName("  name  ", "fallback"))
        assertEquals("two words", ExportNames.sanitizeFileName("two   words", "fallback"))
    }

    @Test
    fun `sanitizeFileName falls back when blank`() {
        assertEquals("fallback", ExportNames.sanitizeFileName("   ", "fallback"))
        assertEquals("fallback", ExportNames.sanitizeFileName("", "fallback"))
        assertEquals("fallback", ExportNames.sanitizeFileName("...", "fallback"))
    }

    @Test
    fun `sanitizeFileName caps length`() {
        val long = "a".repeat(300)
        assertEquals(120, ExportNames.sanitizeFileName(long, "fb").length)
    }

    @Test
    fun `folderName matches filesystem-safe pattern`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val name = ExportNames.folderName(date = Date(0))
            assertEquals("Nopalito_Scan_Export_1970-01-01_00-00-00", name)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `availableFile avoids collisions with numeric suffix`() {
        val dir = Files.createTempDirectory("export_names").toFile()
        try {
            val first = ExportNames.availableFile(dir, "doc.pdf")
            assertTrue(first.createNewFile())
            val second = ExportNames.availableFile(dir, "doc.pdf")
            assertEquals("doc (1).pdf", second.name)
            assertTrue(second.createNewFile())
            val third = ExportNames.availableFile(dir, "doc.pdf")
            assertEquals("doc (2).pdf", third.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `availableFile returns name untouched when free`() {
        val dir = Files.createTempDirectory("export_names").toFile()
        try {
            assertEquals("doc.pdf", ExportNames.availableFile(dir, "doc.pdf").name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `uniqueSubfolder generates unique names on collision`() {
        val parent = Files.createTempDirectory("export_names").toFile()
        try {
            val first = ExportNames.uniqueSubfolder(parent)
            assertTrue(first.mkdirs())
            val second = ExportNames.uniqueSubfolder(parent)
            assertFalse(first.name == second.name)
        } finally {
            parent.deleteRecursively()
        }
    }
}

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

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * JVM tests for [ReorderEngine]. The Android PDFBox fork needs
 * PDFBoxResourceLoader for the standard-14 fonts, so text pages are built with
 * a hand-written content stream and text preservation is proven by comparing
 * the content-stream bytes before and after the page copy (importPage copies
 * streams untouched, which is what keeps text selectable and layout intact).
 */
class ReorderEngineTest {

    /** Builds a PDF whose page i (0-based) has a unique text token in its stream. */
    private fun createPdf(pages: Int): File {
        val file = File.createTempFile("reorder_in", ".pdf")
        PDDocument().use { doc ->
            repeat(pages) { index ->
                val page = PDPage(PDRectangle.A4)
                val stream = COSStream()
                stream.createOutputStream().use {
                    it.write("q\nBT /F1 12 Tf 72 720 Td (pagina$index) Tj\nET\nQ\n".toByteArray())
                }
                page.cosObject.setItem(COSName.CONTENTS, stream)
                doc.addPage(page)
            }
            doc.save(file)
        }
        return file
    }

    private fun contentBytes(doc: PDDocument, pageIndex: Int): ByteArray {
        val contents = doc.getPage(pageIndex).cosObject.getItem(COSName.CONTENTS)
        val stream = contents as? COSStream ?: return ByteArray(0)
        return stream.createInputStream().use { it.readBytes() }
    }

    // ── moveElement (pure reorder logic) ──

    @Test
    fun `move element to the left`() {
        val result = ReorderEngine.moveElement(listOf(1, 2, 3, 4), from = 3, to = 1)
        assertEquals(listOf(1, 4, 2, 3), result)
    }

    @Test
    fun `move element to the right`() {
        val result = ReorderEngine.moveElement(listOf(1, 2, 3, 4), from = 1, to = 3)
        assertEquals(listOf(1, 3, 4, 2), result)
    }

    @Test
    fun `move first element to the end`() {
        val result = ReorderEngine.moveElement(listOf(1, 2, 3, 4, 5), from = 0, to = 4)
        assertEquals(listOf(2, 3, 4, 5, 1), result)
    }

    @Test
    fun `move last element to the start`() {
        val result = ReorderEngine.moveElement(listOf(1, 2, 3, 4, 5), from = 4, to = 0)
        assertEquals(listOf(5, 1, 2, 3, 4), result)
    }

    @Test
    fun `several consecutive moves keep the whole set`() {
        val order = listOf(1, 2, 3, 4, 5, 6)
        val first = ReorderEngine.moveElement(order, from = 5, to = 0)
        val second = ReorderEngine.moveElement(first, from = 1, to = 4)
        val third = ReorderEngine.moveElement(second, from = 0, to = 5)
        assertEquals(order.sorted(), third.sorted())
        assertEquals(6, third.size)
    }

    @Test
    fun `out of range indices are clamped, not crashing`() {
        // from -5 → 0, to 99 → 2: the first element moves to the last position.
        assertEquals(listOf(2, 3, 1), ReorderEngine.moveElement(listOf(1, 2, 3), from = -5, to = 99))
        assertEquals(listOf(1, 2, 3), ReorderEngine.moveElement(listOf(1, 2, 3), from = 1, to = 1))
    }

    @Test
    fun `empty list is a no-op`() {
        assertEquals(emptyList<Int>(), ReorderEngine.moveElement(emptyList(), from = 0, to = 0))
    }

    // ── reorderPages (PDF generation) ──

    @Test
    fun `page count reports the real number of pages`() {
        val input = createPdf(4)
        try {
            assertEquals(4, ReorderEngine.pageCount(input))
        } finally {
            input.delete()
        }
    }

    @Test
    fun `reordering a single page keeps it`() {
        val input = createPdf(1)
        val output = File.createTempFile("reorder_out", ".pdf")
        try {
            ReorderEngine.reorderPages(input, listOf(0), output)
            PDDocument.load(input).use { src ->
                PDDocument.load(output).use { doc ->
                    assertEquals(1, doc.numberOfPages)
                    assertArrayEquals(contentBytes(src, 0), contentBytes(doc, 0))
                }
            }
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun `reordered pdf respects the new order`() {
        val input = createPdf(5)
        val output = File.createTempFile("reorder_out", ".pdf")
        try {
            // 0-based: 4,2,0,3,1 → the final pages must carry the tokens of
            // the source pages 4,2,0,3,1 in that order.
            ReorderEngine.reorderPages(input, listOf(4, 2, 0, 3, 1), output)
            PDDocument.load(input).use { src ->
                PDDocument.load(output).use { doc ->
                    assertEquals(5, doc.numberOfPages)
                    listOf(4, 2, 0, 3, 1).forEachIndexed { index, sourceIndex ->
                        assertArrayEquals(
                            "page $index must come from source page $sourceIndex",
                            contentBytes(src, sourceIndex),
                            contentBytes(doc, index),
                        )
                    }
                }
            }
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun `exporting twice produces independent documents`() {
        val input = createPdf(4)
        val output1 = File.createTempFile("reorder_1", ".pdf")
        val output2 = File.createTempFile("reorder_2", ".pdf")
        try {
            ReorderEngine.reorderPages(input, listOf(3, 2, 1, 0), output1)
            ReorderEngine.reorderPages(input, listOf(3, 2, 1, 0), output2)
            assertEquals(4, ReorderEngine.pageCount(output1))
            assertEquals(4, ReorderEngine.pageCount(output2))
        } finally {
            input.delete()
            output1.delete()
            output2.delete()
        }
    }

    @Test
    fun `password protected export requires the password to open`() {
        val input = createPdf(3)
        val output = File.createTempFile("reorder_protected", ".pdf")
        try {
            ReorderEngine.reorderPages(input, listOf(2, 0, 1), output, password = "clave123")
            assertThrows(InvalidPasswordException::class.java) {
                PDDocument.load(output)
            }
            PDDocument.load(output, "clave123").use { doc ->
                assertEquals(3, doc.numberOfPages)
                PDDocument.load(input).use { src ->
                    listOf(2, 0, 1).forEachIndexed { index, sourceIndex ->
                        assertArrayEquals(
                            "page $index must come from source page $sourceIndex",
                            contentBytes(src, sourceIndex),
                            contentBytes(doc, index),
                        )
                    }
                }
            }
            assertThrows(InvalidPasswordException::class.java) {
                PDDocument.load(output, "incorrecta")
            }
        } finally {
            input.delete()
            output.delete()
        }
    }
}
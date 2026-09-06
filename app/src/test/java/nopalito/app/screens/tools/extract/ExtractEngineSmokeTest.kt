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

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM smoke test for [ExtractEngine]. The Android PDFBox fork needs
 * PDFBoxResourceLoader for the standard-14 fonts, so text pages are built with
 * a hand-written content stream and text preservation is proven by comparing
 * the content-stream bytes before and after the page copy (importPage copies
 * streams untouched, which is what keeps text selectable and layout intact).
 */
class ExtractEngineSmokeTest {

    private fun createPdf(pages: Int, content: String = ""): File {
        val file = File.createTempFile("extract_in", ".pdf")
        PDDocument().use { doc ->
            repeat(pages) {
                val page = PDPage(PDRectangle.A4)
                if (content.isNotEmpty()) {
                    val stream = COSStream()
                    stream.createOutputStream().use {
                        it.write("q\nBT /F1 12 Tf 72 720 Td ($content) Tj\nET\nQ\n".toByteArray())
                    }
                    page.cosObject.setItem(COSName.CONTENTS, stream)
                }
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

    @Test
    fun `page count reports the real number of pages`() {
        val input = createPdf(4)
        try {
            assertEquals(4, ExtractEngine.pageCount(input))
        } finally {
            input.delete()
        }
    }

    @Test
    fun `extract single pages keeps order and count`() {
        val input = createPdf(5, content = "texto seleccionable")
        val output = File.createTempFile("extract_out", ".pdf")
        try {
            ExtractEngine.extractPdf(input, listOf(0, 2, 4), output, password = null)
            PDDocument.load(input).use { src ->
                PDDocument.load(output).use { doc ->
                    assertEquals(3, doc.numberOfPages)
                    // The content stream is copied untouched: the text-showing
                    // operators survive, i.e. the text remains in the page.
                    assertArrayEquals(
                        contentBytes(src, 0),
                        contentBytes(doc, 0),
                    )
                }
            }
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun `extract a range preserves the page order`() {
        val input = createPdf(10)
        val output = File.createTempFile("extract_out", ".pdf")
        try {
            ExtractEngine.extractPdf(input, (1..5).toList(), output, password = null)
            PDDocument.load(output).use { doc ->
                assertEquals(5, doc.numberOfPages)
            }
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun `extracting twice works without restrictions`() {
        val input = createPdf(4)
        val output1 = File.createTempFile("extract_1", ".pdf")
        val output2 = File.createTempFile("extract_2", ".pdf")
        try {
            ExtractEngine.extractPdf(input, listOf(0), output1, password = null)
            ExtractEngine.extractPdf(input, listOf(0), output2, password = null)
            assertEquals(1, ExtractEngine.pageCount(output1))
            assertEquals(1, ExtractEngine.pageCount(output2))
        } finally {
            input.delete()
            output1.delete()
            output2.delete()
        }
    }

    @Test
    fun `password-protected export can be reopened with the password`() {
        val input = createPdf(3)
        val output = File.createTempFile("extract_protected", ".pdf")
        try {
            ExtractEngine.extractPdf(input, listOf(0, 1), output, password = "pass123")
            PDDocument.load(output, "pass123").use { doc ->
                assertEquals(2, doc.numberOfPages)
                assertTrue(doc.isEncrypted)
            }
        } finally {
            input.delete()
            output.delete()
        }
    }
    // ponytail: image export (ExtractEngine.extractImages) needs the Android
    // runtime (PDFRenderer produces android.graphics.Bitmap, which unit tests
    // return as null). Covered by an instrumented test on a device/emulator.
}

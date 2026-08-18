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

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * JVM tests for [DeletePagesEngine]. Like `ReorderEngineTest`, pages are built
 * by hand (content streams + COS objects), so no font resources need to be
 * loaded. The non-white pixel ratio comes from an Android render, so it is
 * passed into [DeletePagesEngine.classifyBlank] directly here and the
 * structural analysis ([DeletePagesEngine.pageContentSignals]) is the part
 * under test.
 */
class DeletePagesEngineTest {

    /** Builds a PDF whose page i (0-based) carries a unique text token. */
    private fun createTextPdf(pages: Int): File {
        val file = File.createTempFile("delpages_text", ".pdf")
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

    private fun buildPdf(buildPage: (PDDocument, Int, PDPage) -> Unit, pages: Int): File {
        val file = File.createTempFile("delpages", ".pdf")
        PDDocument().use { doc ->
            repeat(pages) { index ->
                val page = PDPage(PDRectangle.A4)
                buildPage(doc, index, page)
                doc.addPage(page)
            }
            doc.save(file)
        }
        return file
    }

    private fun setContentStream(page: PDPage, content: String) {
        val stream = COSStream()
        stream.createOutputStream().use { it.write(content.toByteArray()) }
        page.cosObject.setItem(COSName.CONTENTS, stream)
    }

    // ── withoutPages (pure deletion logic) ──

    @Test
    fun `deleting the first page removes it and keeps the rest`() {
        assertEquals(listOf(2, 3, 4), DeletePagesEngine.withoutPages(listOf(1, 2, 3, 4), setOf(1)))
    }

    @Test
    fun `deleting the last page removes it and keeps the rest`() {
        assertEquals(listOf(1, 2, 3), DeletePagesEngine.withoutPages(listOf(1, 2, 3, 4), setOf(4)))
    }

    @Test
    fun `deleting several pages keeps the relative order of the rest`() {
        assertEquals(
            listOf(2, 5),
            DeletePagesEngine.withoutPages(listOf(1, 2, 3, 4, 5), setOf(1, 3, 4)),
        )
    }

    @Test
    fun `deleting all but one leaves the single page`() {
        assertEquals(
            listOf(3),
            DeletePagesEngine.withoutPages(listOf(1, 2, 3, 4, 5), setOf(1, 2, 4, 5)),
        )
    }

    @Test
    fun `deleting every page leaves an empty order`() {
        assertTrue(
            DeletePagesEngine.withoutPages(listOf(1, 2, 3), setOf(1, 2, 3)).isEmpty(),
        )
    }

    @Test
    fun `deleting nothing is a no-op`() {
        assertEquals(listOf(3, 1, 2), DeletePagesEngine.withoutPages(listOf(3, 1, 2), emptySet()))
    }

    @Test
    fun `deleting unknown pages is a no-op`() {
        assertEquals(listOf(1, 2), DeletePagesEngine.withoutPages(listOf(1, 2), setOf(99)))
    }

    @Test
    fun `deleting pages of an already-reordered list keeps the order`() {
        val reordered = DeletePagesEngine.moveElement(listOf(1, 2, 3, 4, 5), from = 4, to = 0)
        assertEquals(listOf(5, 1, 2, 3, 4), reordered)
        assertEquals(listOf(5, 2, 3), DeletePagesEngine.withoutPages(reordered, setOf(1, 4)))
    }

    // ── classifyBlank (pure classification) ──

    @Test
    fun `empty page with no pixels painted is blank`() {
        assertEquals(
            BlankPageStatus.BLANK,
            DeletePagesEngine.classifyBlank(PageContentSignals(), nonWhiteRatio = 0f),
        )
    }

    @Test
    fun `empty page below the strict ratio is blank`() {
        assertEquals(
            BlankPageStatus.BLANK,
            DeletePagesEngine.classifyBlank(PageContentSignals(), nonWhiteRatio = 0.002f),
        )
    }

    @Test
    fun `empty page between the thresholds is possibly blank`() {
        assertEquals(
            BlankPageStatus.LIKELY_BLANK,
            DeletePagesEngine.classifyBlank(PageContentSignals(), nonWhiteRatio = 0.01f),
        )
    }

    @Test
    fun `empty page above the possible threshold has content`() {
        assertEquals(
            BlankPageStatus.HAS_CONTENT,
            DeletePagesEngine.classifyBlank(PageContentSignals(), nonWhiteRatio = 0.05f),
        )
    }

    @Test
    fun `text page is content whatever the pixel ratio says`() {
        val signals = PageContentSignals(hasText = true)
        assertEquals(BlankPageStatus.HAS_CONTENT, DeletePagesEngine.classifyBlank(signals, 0f))
        assertEquals(BlankPageStatus.HAS_CONTENT, DeletePagesEngine.classifyBlank(signals, 0.5f))
    }

    @Test
    fun `image page is content whatever the pixel ratio says`() {
        val signals = PageContentSignals(hasImages = true)
        assertEquals(BlankPageStatus.HAS_CONTENT, DeletePagesEngine.classifyBlank(signals, 0f))
    }

    @Test
    fun `vector graphics with almost no paint is possibly blank`() {
        assertEquals(
            BlankPageStatus.LIKELY_BLANK,
            DeletePagesEngine.classifyBlank(PageContentSignals(hasVectorGraphics = true), 0.001f),
        )
    }

    @Test
    fun `vector graphics that painted something have content`() {
        assertEquals(
            BlankPageStatus.HAS_CONTENT,
            DeletePagesEngine.classifyBlank(PageContentSignals(hasVectorGraphics = true), 0.05f),
        )
    }

    @Test
    fun `annotations with no paint are possibly blank`() {
        assertEquals(
            BlankPageStatus.LIKELY_BLANK,
            DeletePagesEngine.classifyBlank(PageContentSignals(hasAnnotations = true), 0.0f),
        )
    }

    @Test
    fun `custom thresholds change the classification`() {
        val params = BlankDetectionParams(
            minimumBlankRatio = 0.1f,
            minimumPossibleRatio = 0.2f,
        )
        assertEquals(
            BlankPageStatus.BLANK,
            DeletePagesEngine.classifyBlank(PageContentSignals(), nonWhiteRatio = 0.05f, params),
        )
        assertEquals(
            BlankPageStatus.LIKELY_BLANK,
            DeletePagesEngine.classifyBlank(PageContentSignals(), nonWhiteRatio = 0.15f, params),
        )
        assertEquals(
            BlankPageStatus.HAS_CONTENT,
            DeletePagesEngine.classifyBlank(PageContentSignals(), nonWhiteRatio = 0.25f, params),
        )
    }

    // ── pageContentSignals (structural analysis) ──

    @Test
    fun `text operators are detected as text`() {
        val pdf = buildPdf({ _, _, page ->
            setContentStream(page, "BT /F1 12 Tf 72 720 Td (Hola mundo) Tj ET")
        }, pages = 1)
        try {
            PDDocument.load(pdf).use { doc ->
                val signals = DeletePagesEngine.pageContentSignals(doc, 0)
                assertTrue("text page must have hasText", signals.hasText)
                assertFalse("text page must not have images", signals.hasImages)
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun `TJ operator is also detected as text`() {
        val pdf = buildPdf({ _, _, page ->
            setContentStream(page, "BT /F1 12 Tf 72 720 Td [(Hola) (mundo)] TJ ET")
        }, pages = 1)
        try {
            PDDocument.load(pdf).use { doc ->
                assertTrue(DeletePagesEngine.pageContentSignals(doc, 0).hasText)
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun `vector path operators are detected as graphics`() {
        val pdf = buildPdf({ _, _, page ->
            setContentStream(page, "0 0 100 100 re f")
        }, pages = 1)
        try {
            PDDocument.load(pdf).use { doc ->
                val signals = DeletePagesEngine.pageContentSignals(doc, 0)
                assertTrue("vector page must have hasVectorGraphics", signals.hasVectorGraphics)
                assertFalse("vector page must not have text", signals.hasText)
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun `image xobject is detected as image`() {
        val pdf = buildPdf({ _, _, page ->
            val resources = PDResources()
            val imageStream = COSStream()
            imageStream.setItem(COSName.SUBTYPE, COSName.IMAGE)
            imageStream.setItem(COSName.WIDTH, COSInteger.get(1))
            imageStream.setItem(COSName.HEIGHT, COSInteger.get(1))
            imageStream.setItem(COSName.BITS_PER_COMPONENT, COSInteger.get(8))
            imageStream.setItem(COSName.COLORSPACE, COSName.DEVICERGB)
            val xobjectDict = resources.cosObject
                .getDictionaryObject(COSName.XOBJECT) as? COSDictionary
                ?: COSDictionary().also {
                    resources.cosObject.setItem(COSName.XOBJECT, it)
                }
            xobjectDict.setItem(COSName.getPDFName("Im0"), imageStream)
            page.resources = resources
        }, pages = 1)
        try {
            PDDocument.load(pdf).use { doc ->
                val signals = DeletePagesEngine.pageContentSignals(doc, 0)
                assertTrue("image page must have hasImages", signals.hasImages)
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun `annotation is detected as annotation`() {
        val pdf = buildPdf({ _, _, page ->
            page.annotations = listOf(PDAnnotationLink())
        }, pages = 1)
        try {
            PDDocument.load(pdf).use { doc ->
                assertTrue(DeletePagesEngine.pageContentSignals(doc, 0).hasAnnotations)
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun `nested form xobject text is detected`() {
        val pdf = buildPdf({ doc, _, page ->
            val resources = PDResources()
            val form = PDFormXObject(doc)
            form.contentStream.createOutputStream().use {
                it.write("BT /F1 12 Tf 72 720 Td (dentro del form) Tj ET".toByteArray())
            }
            resources.put(COSName.getPDFName("Fm0"), form)
            page.resources = resources
        }, pages = 1)
        try {
            PDDocument.load(pdf).use { doc ->
                assertTrue(
                    "text nested in a form must be detected",
                    DeletePagesEngine.pageContentSignals(doc, 0).hasText,
                )
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun `empty page has no signals at all`() {
        val pdf = buildPdf({ _, _, _ -> }, pages = 1)
        try {
            PDDocument.load(pdf).use { doc ->
                assertEquals(PageContentSignals(), DeletePagesEngine.pageContentSignals(doc, 0))
            }
        } finally {
            pdf.delete()
        }
    }

    // ── end-to-end: blank detection across a mixed document ──

    @Test
    fun `mixed document classifies blank, text and vector pages`() {
        val pdf = buildPdf({ _, index, page ->
            when (index) {
                0 -> setContentStream(page, "BT /F1 12 Tf 72 720 Td (contenido) Tj ET")
                1 -> { /* empty page */
                }

                2 -> setContentStream(page, "0 0 50 50 re f")
            }
            if (index == 3) {
                val resources = PDResources()
                val imageStream = COSStream()
                imageStream.setItem(COSName.SUBTYPE, COSName.IMAGE)
                imageStream.setItem(COSName.WIDTH, COSInteger.get(1))
                imageStream.setItem(COSName.HEIGHT, COSInteger.get(1))
                imageStream.setItem(COSName.BITS_PER_COMPONENT, COSInteger.get(8))
                imageStream.setItem(COSName.COLORSPACE, COSName.DEVICERGB)
                val xobjectDict = resources.cosObject
                    .getDictionaryObject(COSName.XOBJECT) as? COSDictionary
                    ?: COSDictionary().also {
                        resources.cosObject.setItem(COSName.XOBJECT, it)
                    }
                xobjectDict.setItem(COSName.getPDFName("Im0"), imageStream)
                page.resources = resources
            }
        }, pages = 4)
        try {
            PDDocument.load(pdf).use { doc ->
                val params = BlankDetectionParams()
                val classifications = (0 until 4).map { index ->
                    val signals = DeletePagesEngine.pageContentSignals(doc, index)
                    // The pixel ratio comes from a render; here we simulate: the
                    // empty page renders blank (ratio 0), everything else paints.
                    val ratio = if (index == 1) 0f else 0.1f
                    DeletePagesEngine.classifyBlank(signals, ratio, params)
                }
                assertEquals(BlankPageStatus.HAS_CONTENT, classifications[0])
                assertEquals(BlankPageStatus.BLANK, classifications[1])
                assertEquals(BlankPageStatus.HAS_CONTENT, classifications[2])
                assertEquals(BlankPageStatus.HAS_CONTENT, classifications[3])
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun `faint smear without structure is possibly blank`() {
        val pdf = buildPdf({ _, _, page ->
            setContentStream(page, "10 10 3 3 re f")
        }, pages = 1)
        try {
            PDDocument.load(pdf).use { doc ->
                val signals = DeletePagesEngine.pageContentSignals(doc, 0)
                assertEquals(
                    BlankPageStatus.LIKELY_BLANK,
                    DeletePagesEngine.classifyBlank(signals, nonWhiteRatio = 0.001f),
                )
            }
        } finally {
            pdf.delete()
        }
    }

    // ── export excludes the deleted pages ──

    @Test
    fun `export after deletion keeps the remaining pages in order`() {
        val input = createTextPdf(4)
        val output = File.createTempFile("delpages_out", ".pdf")
        try {
            // Delete original pages 2 and 4 (1-based), keep 1,3 (1-based).
            val remaining = DeletePagesEngine.withoutPages(listOf(1, 2, 3, 4), setOf(2, 4))
            assertEquals(listOf(1, 3), remaining)
            val orderIndices = remaining.map { it - 1 }
            DeletePagesEngine.reorderPages(input, orderIndices, output)
            PDDocument.load(input).use { src ->
                PDDocument.load(output).use { doc ->
                    assertEquals(2, doc.numberOfPages)
                    assertArrayEquals(contentBytes(src, 0), contentBytes(doc, 0))
                    assertArrayEquals(contentBytes(src, 2), contentBytes(doc, 1))
                }
            }
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun `exporting with an empty order produces an empty document`() {
        val input = createTextPdf(3)
        val output = File.createTempFile("delpages_empty", ".pdf")
        try {
            DeletePagesEngine.reorderPages(input, emptyList(), output)
            PDDocument.load(output).use { doc ->
                assertEquals(0, doc.numberOfPages)
            }
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun `export after delete and reorder respects the final order`() {
        val input = createTextPdf(5)
        val output = File.createTempFile("delpages_mix", ".pdf")
        try {
            // Delete 2,4 (1-based), then move the first remaining to the end.
            val afterDelete = DeletePagesEngine.withoutPages(listOf(1, 2, 3, 4, 5), setOf(2, 4))
            val finalOrder = DeletePagesEngine.moveElement(afterDelete, from = 0, to = afterDelete.lastIndex)
            assertEquals(listOf(3, 5, 1), finalOrder)
            DeletePagesEngine.reorderPages(input, finalOrder.map { it - 1 }, output)
            PDDocument.load(input).use { src ->
                PDDocument.load(output).use { doc ->
                    listOf(3, 5, 1).forEachIndexed { index, sourcePage ->
                        assertArrayEquals(contentBytes(src, sourcePage - 1), contentBytes(doc, index))
                    }
                }
            }
        } finally {
            input.delete()
            output.delete()
        }
    }

    // ── tokenizer edge cases ──

    @Test
    fun `operators inside string literals are not misread`() {
        // "re f Tj" appear only inside a parenthesised string: no vector/text ops.
        val tokens = DeletePagesEngine.tokenize("(re f Tj with (nested) parens)".toByteArray())
        assertFalse("text op inside a string must not count", tokens.contains("Tj"))
        assertFalse("vector ops inside a string must not count", tokens.contains("re"))
        assertFalse(tokens.contains("f"))
    }

    @Test
    fun `comments are skipped`() {
        val tokens = DeletePagesEngine.tokenize("re % Tj\nf".toByteArray())
        assertFalse(tokens.contains("Tj"))
        assertTrue(tokens.contains("re"))
        assertTrue(tokens.contains("f"))
    }

    @Test
    fun `hex strings and names are skipped`() {
        val tokens = DeletePagesEngine.tokenize("/F1 12 Tf <4E75> Tj".toByteArray())
        assertFalse(tokens.contains("4E75"))
        assertFalse(tokens.contains("F1"))
        assertTrue(tokens.contains("Tj"))
    }
}
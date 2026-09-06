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

package nopalito.app.domain.ocr

import nopalito.imageprocessing.ImageRect
import nopalito.imageprocessing.OcrTextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextExtractorTest {

    private val extractor = OcrTextExtractor()

    private fun box(
        text: String,
        left: Int,
        lineBottom: Int,
        lineHeight: Int = 20,
    ) = OcrTextBox(
        text = text,
        box = ImageRect(left, lineBottom - lineHeight, left + 50, lineBottom),
        lineHeight = lineHeight,
        lineBottom = lineBottom,
    )

    @Test
    fun `empty input produces empty document`() {
        val result = extractor.extract(emptyList())
        assertTrue(result.lines.isEmpty())
        assertTrue(result.fullText.isBlank())
        assertEquals(0, result.lineCount)
        assertEquals(0, result.wordCount)
    }

    @Test
    fun `words on one line are joined left to right`() {
        val result = extractor.extract(
            listOf(
                box("world", left = 100, lineBottom = 50),
                box("Hello", left = 10, lineBottom = 52),
            )
        )
        assertEquals(listOf("Hello world"), result.lines.map { it.text })
        assertEquals("Hello world", result.fullText)
        assertEquals(1, result.lineCount)
        assertEquals(2, result.wordCount)
    }

    @Test
    fun `words on separate lines stay separate`() {
        val result = extractor.extract(
            listOf(
                box("second", left = 10, lineBottom = 120),
                box("first", left = 10, lineBottom = 50),
            )
        )
        assertEquals(listOf("first", "second"), result.lines.map { it.text })
        assertEquals("first\nsecond", result.fullText)
    }

    @Test
    fun `blank words are dropped`() {
        val result = extractor.extract(
            listOf(
                box("  ", left = 10, lineBottom = 50),
                box("kept", left = 70, lineBottom = 50),
            )
        )
        assertEquals(listOf("kept"), result.lines.map { it.text })
    }

    @Test
    fun `punctuation-only tokens are dropped`() {
        val result = extractor.extract(
            listOf(
                box("hello", left = 10, lineBottom = 50),
                box("-", left = 70, lineBottom = 50),
                box("=", left = 90, lineBottom = 50),
                box("world", left = 110, lineBottom = 50),
            )
        )
        assertEquals(listOf("hello world"), result.lines.map { it.text })
    }

    @Test
    fun `lone single-character lines are dropped as logo noise`() {
        val result = extractor.extract(
            listOf(
                box("a", left = 10, lineBottom = 30),
                box("Contrato", left = 10, lineBottom = 80),
                box("colectivo", left = 100, lineBottom = 80),
            )
        )
        assertEquals(listOf("Contrato colectivo"), result.lines.map { it.text })
    }

    @Test
    fun `tiny words below height fraction are dropped`() {
        val tall = OcrTextBox(
            text = "kept",
            box = ImageRect(10, 100, 80, 130),
            lineHeight = 30,
            lineBottom = 130,
        )
        val tiny = OcrTextBox(
            text = "speck",
            box = ImageRect(10, 10, 30, 14),
            lineHeight = 4,
            lineBottom = 14,
        )
        // Image 2000px tall -> 10px minimum; the 4px speck is dropped.
        val result = extractor.extract(listOf(tall, tiny), imageHeightPx = 2000)
        assertEquals(listOf("kept"), result.lines.map { it.text })
    }

    @Test
    fun `height filter is disabled when image height is unknown`() {
        val tiny = OcrTextBox(
            text = "speck",
            box = ImageRect(10, 10, 30, 14),
            lineHeight = 4,
            lineBottom = 14,
        )
        val result = extractor.extract(listOf(tiny), imageHeightPx = 0)
        assertEquals(listOf("speck"), result.lines.map { it.text })
    }
}
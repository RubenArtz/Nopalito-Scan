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

package nopalito.app.platform

import nopalito.imageprocessing.ImageRect
import nopalito.imageprocessing.OcrTextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxFileBuilderTest {

    // 1x1 transparent PNG
    private val png1x1: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    )

    @Test
    fun `write produces a valid docx package with all parts`() {
        val media = listOf(
            DocxPageMedia(
                bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
                widthPx = 200,
                heightPx = 400,
                isPng = false,
                ocrLines = listOf("Hello <world> & \"quotes\"", "Second line"),
            ),
            DocxPageMedia(
                bytes = png1x1,
                widthPx = 100,
                heightPx = 100,
                isPng = true,
                ocrLines = emptyList(),
            ),
        )

        val bytes = ByteArrayOutputStream().let { out ->
            DocxFileBuilder.write(media, out)
            out.toByteArray()
        }

        // 1. The output must be a readable zip with every expected part
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "docProps/core.xml",
                "docProps/app.xml",
                "word/document.xml",
                "word/_rels/document.xml.rels",
                "word/media/page1.jpg",
                "word/media/page2.png",
            ),
            entries.keys
        )

        // 2. Every XML part must be well-formed
        entries.forEach { (name, content) ->
            if (name.endsWith(".xml") || name.endsWith(".rels")) {
                assertXmlWellFormed(String(content, Charsets.UTF_8))
            }
        }

        // 3. document.xml must reference the images and escape OCR text
        val documentXml = String(entries.getValue("word/document.xml"), Charsets.UTF_8)
        assertTrue(documentXml.contains("r:embed=\"rIdImage1\""))
        assertTrue(documentXml.contains("r:embed=\"rIdImage2\""))
        assertTrue(documentXml.contains("<w:pgSz w:w=\"12240\" w:h=\"15840\"/>"))
        assertTrue(documentXml.contains("<w:br w:type=\"page\"/>"))
        assertTrue(documentXml.contains("Hello &lt;world&gt; &amp; &quot;quotes&quot;"))
        assertTrue(documentXml.contains("Second line"))

        // 4. Image extent is scaled to fit the content area (200px -> <= 6.5in)
        assertTrue(documentXml.contains("<wp:extent cx=\"1905000\" cy=\"3810000\"/>"))

        // 5. Relationships point to the right media parts
        val rels = String(entries.getValue("word/_rels/document.xml.rels"), Charsets.UTF_8)
        assertTrue(rels.contains("Target=\"media/page1.jpg\""))
        assertTrue(rels.contains("Target=\"media/page2.png\""))

        // 6. Content types declare both image extensions used
        val contentTypes = String(entries.getValue("[Content_Types].xml"), Charsets.UTF_8)
        assertTrue(contentTypes.contains("Extension=\"jpg\" ContentType=\"image/jpeg\""))
        assertTrue(contentTypes.contains("Extension=\"png\" ContentType=\"image/png\""))
        assertTrue(
            contentTypes.contains(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
            )
        )
    }

    @Test
    fun `group words into lines ordered top to bottom and left to right`() {
        val words = listOf(
            OcrTextBox("word3", ImageRect(60, 5, 90, 25), 20, 25),
            OcrTextBox("word1", ImageRect(10, 5, 35, 25), 20, 25),
            OcrTextBox("word2", ImageRect(40, 5, 55, 25), 20, 25),
            OcrTextBox("nextLine", ImageRect(10, 30, 70, 50), 20, 50),
        )
        val lines = groupWordsIntoLines(words)
        assertEquals(listOf("word1 word2 word3", "nextLine"), lines)
    }

    @Test
    fun `xml escape handles special characters`() {
        assertEquals(
            "a&amp;b&lt;c&gt;d&quot;e&apos;f",
            DocxFileBuilder.xmlEscape("a&b<c>d\"e'f")
        )
    }

    private fun assertXmlWellFormed(xml: String) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.newDocumentBuilder().parse(xml.byteInputStream())
    }
}

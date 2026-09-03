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

import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import nopalito.app.ui.screens.tools.reorder.ReorderEngine
import java.io.File

/**
 * Structural signals of one PDF page, gathered without rasterizing anything
 * (content-stream operators + resource dictionaries + annotations). Combined
 * with the pixel ratio of a low-resolution render, they drive the blank-page
 * classification in [classifyBlank].
 */
data class PageContentSignals(
    var hasText: Boolean = false,
    var hasImages: Boolean = false,
    var hasVectorGraphics: Boolean = false,
    var hasAnnotations: Boolean = false,
)

/**
 * Thresholds of the blank-page analysis. All values are configurable; the
 * defaults are tuned for scanned documents (a strict "definitely blank" band,
 * a wider "possibly blank" band that needs human review, and a margin that is
 * ignored so page borders/crop marks do not count as content).
 */
data class BlankDetectionParams(
    /** Below this non-white pixel ratio a page with no structure is "blank". */
    val minimumBlankRatio: Float = 0.005f,
    /** Below this non-white pixel ratio a page with faint marks is "possibly blank". */
    val minimumPossibleRatio: Float = 0.02f,
    /** Fraction of each edge ignored when counting non-white pixels (margins). */
    val marginFraction: Float = 0.05f,
    /** Max per-channel deviation from white (255) still counted as white. */
    val whiteTolerance: Int = 10,
)

/**
 * PDF page operations for the "Delete PDF pages" tool.
 *
 * Reordering/deleting uses PDFBox (the same library as the reorder /
 * compressor / extract tools): pages are copied via [ReorderEngine.reorderPages]
 * (`PDDocument.importPage`), which clones the page object **and its resources**;
 * the content streams are carried over untouched, so text stays selectable and
 * fonts/images/vectors/layout/orientation/size are preserved. Nothing is
 * rasterized.
 *
 * The tool never builds the PDF during preview; it only reads the page list
 * and renders previews with the native renderer. The physical PDF is produced
 * only on export, from the current page order.
 *
 * This layer is independent of Android (it receives local [File]s and
 * [PDDocument]s), so it can be covered by pure JVM unit tests, like
 * `ReorderEngine` / `ExtractEngine`. Blank-page detection works on the
 * content-stream operators and resources (no fonts need to be loaded, so the
 * tests can build pages by hand).
 */
object DeletePagesEngine {

    /** Returns [list] with the element at [from] moved to position [to] (0-based). */
    fun moveElement(list: List<Int>, from: Int, to: Int): List<Int> =
        ReorderEngine.moveElement(list, from, to)

    /** Opens [input] and returns its page count, closing the document. */
    fun pageCount(input: File): Int = ReorderEngine.pageCount(input)

    /**
     * Creates a brand-new PDF at [output] with the pages of [input] in the
     * order given by [orderIndices] (0-based original indices). The original
     * document is never modified, so the tool can be exported any number of
     * times. Deleting pages simply means passing an order that does not
     * contain them.
     */
    fun reorderPages(
        input: File,
        orderIndices: List<Int>,
        output: File,
        password: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = ReorderEngine.reorderPages(input, orderIndices, output, password, onProgress)

    /**
     * Returns [order] without the original 1-based page numbers in [toRemove],
     * keeping the relative order of the remaining pages. Pure function so the
     * deletion behavior can be unit tested (first page, last page, several
     * pages, all but one, â€¦).
     */
    fun withoutPages(order: List<Int>, toRemove: Set<Int>): List<Int> =
        order.filterNot { it in toRemove }

    /**
     * Classifies a page from its structure signals and the non-white pixel
     * ratio of a low-resolution render (0..1, margins already ignored).
     *
     * - Text or images â‡’ content, whatever the pixel ratio says.
     * - Only vector graphics / annotations: content if the page actually
     *   painted something noticeable, otherwise "possibly blank".
     * - No structure at all: the pixel ratio decides between "blank",
     *   "possibly blank" and content.
     */
    fun classifyBlank(
        signals: PageContentSignals,
        nonWhiteRatio: Float,
        params: BlankDetectionParams = BlankDetectionParams(),
    ): BlankPageStatus {
        return when {
            signals.hasText || signals.hasImages -> BlankPageStatus.HAS_CONTENT
            signals.hasVectorGraphics || signals.hasAnnotations ->
                if (nonWhiteRatio < params.minimumBlankRatio) BlankPageStatus.LIKELY_BLANK
                else BlankPageStatus.HAS_CONTENT

            nonWhiteRatio < params.minimumBlankRatio -> BlankPageStatus.BLANK
            nonWhiteRatio < params.minimumPossibleRatio -> BlankPageStatus.LIKELY_BLANK
            else -> BlankPageStatus.HAS_CONTENT
        }
    }

    /**
     * Gathers the structural signals of the page at [pageIndex] (0-based):
     * text-showing operators in the content streams (including nested form
     * XObjects), image XObjects, vector painting operators and annotations.
     * Content streams are scanned token-by-token (strings, hex strings,
     * comments and inline images are skipped), so no font resources are
     * required - the same trick the tests use to build pages by hand.
     */
    fun pageContentSignals(doc: PDDocument, pageIndex: Int): PageContentSignals {
        val page = doc.getPage(pageIndex)
        val visited = HashSet<COSBase>()
        val signals = PageContentSignals()
        page.getContentStreams().asSequence().forEach { scanContentStream(it, visited, signals) }
        scanResources(page.resources, visited, signals)
        return signals.copy(
            hasAnnotations = runCatching { page.annotations.isNotEmpty() }.getOrDefault(false),
        )
    }

    private fun scanContentStream(
        stream: PDStream,
        visited: MutableSet<COSBase>,
        signals: PageContentSignals
    ) {
        if (!visited.add(stream.getCOSObject())) return
        val tokens = runCatching {
            stream.createInputStream().use { input ->
                val bytes = input.readBytes()
                tokenize(bytes)
            }
        }.getOrDefault(emptyList())
        if (tokens.any { it == "Tj" || it == "TJ" }) {
            signals.hasText = true
        }
        if (tokens.any { it in VECTOR_OPS }) {
            signals.hasVectorGraphics = true
        }
    }

    private fun scanResources(
        resources: PDResources?,
        visited: MutableSet<COSBase>,
        signals: PageContentSignals,
    ) {
        if (resources == null) return
        runCatching { resources.xObjectNames }.getOrDefault(emptyList()).forEach { name ->
            val xObject = runCatching { resources.getXObject(name) }.getOrNull() ?: return@forEach
            when (xObject) {
                is PDImageXObject -> signals.hasImages = true
                is PDFormXObject -> {
                    scanContentStream(xObject.contentStream, visited, signals)
                    scanResources(xObject.resources, visited, signals)
                }
            }
        }
    }

    /** Operators that paint paths / vector graphics (not text). */
    private val VECTOR_OPS = setOf(
        "re", "m", "l", "c", "v", "y", "h", "f", "F", "S", "s", "B", "b",
        "w", "W", "d", "g", "G", "rg", "RG", "k", "K", "sh",
    )

    /**
     * Splits a raw PDF content stream into operator tokens, skipping string
     * literals (parenthesised, with balanced nesting), hex strings, comments,
     * name objects and inline image data, so operators inside text are not
     * misread as vector graphics.
     */
    internal fun tokenize(content: ByteArray): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val n = content.size
        while (i < n) {
            val startPos = i
            val c = content[i]
            when {
                c == '%'.code.toByte() -> {
                    // Comment: skip to end of line.
                    while (i < n && content[i] != '\n'.code.toByte()) i++
                }

                c == '('.code.toByte() -> {
                    // Parenthesised string with nesting and backslash escapes.
                    var depth = 1
                    i++
                    while (i < n && depth > 0) {
                        val ch = content[i]
                        if (ch == '\\'.code.toByte()) {
                            i += 2
                            continue
                        }
                        if (ch == '('.code.toByte()) depth++
                        if (ch == ')'.code.toByte()) depth--
                        i++
                    }
                }

                c == '<'.code.toByte() -> {
                    if (i + 1 < n && content[i + 1] == '<'.code.toByte()) {
                        // Dictionary: skip balanced << >> (rare in streams).
                        var depth = 1
                        i += 2
                        while (i < n && depth > 0) {
                            if (i + 1 < n && content[i] == '<'.code.toByte() && content[i + 1] == '<'.code.toByte()) {
                                depth++
                                i += 2
                            } else if (i + 1 < n && content[i] == '>'.code.toByte() && content[i + 1] == '>'.code.toByte()) {
                                depth--
                                i += 2
                            } else {
                                i++
                            }
                        }
                    } else {
                        // Hex string.
                        while (i < n && content[i] != '>'.code.toByte()) i++
                        i++
                    }
                }

                c == '/'.code.toByte() -> {
                    // Name object (delimiters are handled in the else branch).
                    i++
                    while (i < n && !content[i].isDelimiter() && !content[i].isWhitespaceByte()) i++
                }

                c.isDelimiter() -> i++
                c.isWhitespaceByte() -> i++
                else -> {
                    val start = i
                    while (i < n && !content[i].isDelimiter() && !content[i].isWhitespaceByte()) i++
                    val token = content.copyOfRange(start, i).toString(Charsets.ISO_8859_1)
                    if (token.isNotEmpty()) tokens.add(token)
                }
            }
            // Never allow a step to stall: guaranteed forward progress.
            if (i <= startPos) i = startPos + 1
        }
        return tokens
    }

    private fun Byte.isDelimiter(): Boolean =
        this in setOf(
            '('.code.toByte(),
            ')'.code.toByte(),
            '<'.code.toByte(),
            '>'.code.toByte(),
            '['.code.toByte(),
            ']'.code.toByte(),
            '{'.code.toByte(),
            '}'.code.toByte(),
            '/'.code.toByte(),
            '%'.code.toByte(),
        )

    private fun Byte.isWhitespaceByte(): Boolean =
        this == ' '.code.toByte() || this == '\n'.code.toByte() ||
                this == '\r'.code.toByte() || this == '\t'.code.toByte() ||
                this == '\u000c'.code.toByte()
}
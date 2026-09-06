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

import nopalito.app.domain.ocr.OcrTextExtractor.Companion.MIN_WORD_HEIGHT_FRACTION
import nopalito.app.domain.ocr.OcrTextExtractor.Companion.PARAGRAPH_GAP_FACTOR
import nopalito.app.domain.ocr.OcrTextExtractor.Companion.PARAGRAPH_INDENT_FACTOR
import nopalito.app.domain.ocr.OcrTextExtractor.Companion.SPLIT_COLUMNS
import nopalito.imageprocessing.OcrTextBox
import kotlin.math.abs

/** A single line of extracted text, in reading order. */
data class ExtractedTextLine(
    val text: String,
    val wordCount: Int,
)

/** A single line with its paragraph boundary flag. */
data class StructuredLine(
    val text: String,
    val wordCount: Int,
    /** True when this line starts a new paragraph. Always true for line 0. */
    val startsNewParagraph: Boolean,
)

/** Full extraction result for one page: lines plus the joined plain text. */
data class ExtractedDocument(
    val lines: List<ExtractedTextLine>,
    val fullText: String,
) {
    val lineCount: Int get() = lines.size
    val wordCount: Int get() = lines.sumOf { it.wordCount }
}

/**
 * Text extraction: turns detected word boxes into ordered lines and plain
 * text. Pure Kotlin with no Android dependencies, so it is unit-testable on
 * the JVM. This is the single home of the word-to-line grouping algorithm
 * (also reused by the DOCX exporter).
 *
 * Detection noise from logos and graphics is filtered out:
 * - tokens without a single letter or digit (stray "-", "=", "|"),
 * - words shorter than [MIN_WORD_HEIGHT_FRACTION] of the page height
 *   (specks inside graphic elements),
 * - lines holding a single character (a lone "a" on its own line is
 *   virtually always a logo speck, never body text).
 */
class OcrTextExtractor {

    companion object {
        /** Minimum word height, relative to the page height. */
        const val MIN_WORD_HEIGHT_FRACTION = 0.005f

        /** Line gap (x median line height) that opens a new paragraph. */
        const val PARAGRAPH_GAP_FACTOR = 1.6f

        /** First-line indent (x median line height) that opens a new paragraph. */
        const val PARAGRAPH_INDENT_FACTOR = 1.0f

        /** Experimental two-column reading order. Off by default: zero behavior change. */
        const val SPLIT_COLUMNS = false
    }

    /**
     * Groups word boxes into lines ordered from top to bottom, then from
     * left to right. Words whose baselines differ by less than a third of
     * the line height belong to the same line. Each word is compared to the
     * last word on the line (not the first), so a slight page skew does not
     * fracture real lines.
     */
    fun groupIntoLines(boxes: List<OcrTextBox>): List<List<OcrTextBox>> {
        if (boxes.isEmpty()) return emptyList()
        val ordered = if (SPLIT_COLUMNS) splitColumnsIfPresent(boxes) else boxes
        val sorted = ordered.sortedWith(
            compareBy<OcrTextBox> { it.lineBottom }
                .thenBy { it.box.left },
        )
        val lines = mutableListOf<MutableList<OcrTextBox>>()
        for (word in sorted) {
            val current = lines.lastOrNull()
            val tolerance = (word.lineHeight / 3f).coerceAtLeast(2f)
            if (
                current != null &&
                abs(current.last().lineBottom - word.lineBottom) <= tolerance
            ) {
                current += word
            } else {
                lines += mutableListOf(word)
            }
        }
        return lines.map { line -> line.sortedBy { it.box.left } }
    }

    /**
     * Builds the [ExtractedDocument] for the given detected word boxes.
     *
     * @param imageHeightPx height of the source bitmap in pixels. When
     * positive, words shorter than [MIN_WORD_HEIGHT_FRACTION] of it are
     * treated as graphic noise and dropped. Pass 0 to disable that filter
     * (e.g. when the image size is unknown).
     */
    fun extract(boxes: List<OcrTextBox>, imageHeightPx: Int = 0): ExtractedDocument {
        // Delegates to extractWithStructure: same denoise, same grouping, same
        // single-character rule, lines joined with "\n". Observable behavior
        // is unchanged; only paragraph flags are computed additionally.
        val lines = extractWithStructure(boxes, imageHeightPx)
        val docLines = lines.map { ExtractedTextLine(it.text, it.wordCount) }
        return ExtractedDocument(docLines, docLines.joinToString(separator = "\n") { it.text })
    }

    /**
     * Like [extract] but keeps paragraph boundaries: a line starts a new
     * paragraph when the gap above it exceeds [PARAGRAPH_GAP_FACTOR] x the
     * median line height, or it is indented beyond [PARAGRAPH_INDENT_FACTOR]
     * x the median line height. Medians (not means) keep one giant heading
     * from shifting the thresholds. Pure Kotlin, JVM-testable.
     */
    fun extractWithStructure(
        boxes: List<OcrTextBox>,
        imageHeightPx: Int = 0,
    ): List<StructuredLine> {
        val minWordHeightPx =
            if (imageHeightPx > 0) imageHeightPx * MIN_WORD_HEIGHT_FRACTION else 0f
        val denoised = boxes.filter { box ->
            box.text.any { it.isLetterOrDigit() } &&
                    (minWordHeightPx <= 0f || box.box.height >= minWordHeightPx)
        }
        val lineBoxes = groupIntoLines(denoised)
        if (lineBoxes.isEmpty()) return emptyList()
        val heights = lineBoxes.map { line -> line.maxOf { it.lineHeight } }.sorted()
        val medianH = heights[heights.size / 2].toFloat().coerceAtLeast(1f)
        val lefts = lineBoxes.map { line -> line.minOf { it.box.left } }.sorted()
        val medianLeft = lefts[lefts.size / 2].toFloat()
        return lineBoxes.mapIndexed { index, line ->
            val text = line.joinToString(separator = " ") { it.text.trim() }.trim()
            val startsNew = if (index == 0) {
                true
            } else {
                val prev = lineBoxes[index - 1]
                val gap = line.minOf { it.box.top } - prev.maxOf { it.box.bottom }
                val indent = line.minOf { it.box.left } - medianLeft
                gap > medianH * PARAGRAPH_GAP_FACTOR ||
                        indent > medianH * PARAGRAPH_INDENT_FACTOR
            }
            Triple(text, line.size, startsNew)
        }.filter { it.first.length > 1 }
            .mapIndexed { kept, (text, count, flag) ->
                StructuredLine(text, count, startsNewParagraph = kept == 0 || flag)
            }
    }

    /**
     * Experimental two-column support: splits boxes at a full-height
     * whitespace gutter so grouped lines read column-by-column. Returns the
     * input untouched when no gutter qualifies. Only called from
     * [groupIntoLines] when [SPLIT_COLUMNS] is true.
     */
    private fun splitColumnsIfPresent(boxes: List<OcrTextBox>): List<OcrTextBox> {
        if (boxes.size < 8) return boxes
        val widths = boxes.map { it.box.width }.sorted()
        val medianW = widths[widths.size / 2].coerceAtLeast(1)
        val top = boxes.minOf { it.box.top }
        val bottom = boxes.maxOf { it.box.bottom }
        val rows = (0..9).map { top + (bottom - top) * it / 9 }
        fun clearAt(x: Int, y: Int) =
            boxes.none { it.box.top <= y && y <= it.box.bottom && it.box.left < x && x < it.box.right }

        val midY = rows[5]
        // Widest band clear at mid-height...
        var bestStart = -1
        var bestEnd = -1
        var x = boxes.minOf { it.box.left }
        val maxX = boxes.maxOf { it.box.right }
        while (x < maxX) {
            if (clearAt(x, midY)) {
                var end = x
                while (end < maxX && clearAt(end, midY)) end += 4
                if (end - x > bestEnd - bestStart) {
                    bestStart = x
                    bestEnd = end
                }
                x = end
            } else x += 4
        }
        // ...kept only when wide enough and clear on most sampled rows, so a
        // short paragraph's ragged edge never counts as a column gutter.
        if (bestEnd - bestStart < medianW * 2) return boxes
        val midX = (bestStart + bestEnd) / 2
        if (rows.count { clearAt(midX, it) } < 6) return boxes
        val left = boxes.filter { (it.box.left + it.box.right) / 2 < midX }
        val right = boxes.filter { (it.box.left + it.box.right) / 2 >= midX }
        if (left.size < 3 || right.size < 3) return boxes
        return left + right
    }
}
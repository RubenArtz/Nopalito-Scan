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

import nopalito.imageprocessing.OcrTextBox

/**
 * Lightweight export filter (PDF/DOCX): mirrors the denoising of
 * [OcrTextExtractor.extract] but returns boxes (with coordinates) instead of
 * text lines. ALWAYS use it before [OcrDocument.addPage] or before building
 * DOCX paragraphs: raw Tesseract boxes carry specks.
 *
 * Criteria (cheap, no extra OCR):
 * - must contain a letter/digit (drops "-", "=", "|")
 * - visible length >= 2 (drops lone "a", "·")
 * - height >= 0.5% of the page (drops specks inside logos/graphics)
 * - alphanumeric ratio >= 50% (drops "-_-_-")
 */
fun filterBoxesForExport(
    boxes: List<OcrTextBox>,
    imageHeightPx: Int,
): List<OcrTextBox> {
    if (boxes.isEmpty()) return emptyList()
    val minH =
        if (imageHeightPx > 0) imageHeightPx * OcrTextExtractor.MIN_WORD_HEIGHT_FRACTION else 0f
    return boxes.filter { b ->
        val t = b.text.trim()
        if (t.length < 2) return@filter false
        if (!t.any { it.isLetterOrDigit() }) return@filter false
        if (minH > 0f && b.box.height < minH) return@filter false
        val alnum = t.count { it.isLetterOrDigit() }
        if (alnum.toFloat() / t.length < 0.5f) return@filter false
        if (t.length >= 4 && t.toSet().size <= 1) return@filter false
        true
    }
}
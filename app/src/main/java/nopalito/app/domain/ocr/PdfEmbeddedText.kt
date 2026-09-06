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

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * OCR bypass: when the imported PDF already carries a text layer (digital
 * invoice, Word export), reusing it is faster and more faithful than re-running
 * OCR over a render. Import flow only; camera scans always need OCR because
 * they start from JPEGs without text.
 *
 * @return clean page text, or null when not reusable
 * (scanned image, empty page, or garbage only).
 */
fun embeddedTextOrNull(doc: PDDocument, pageIndex: Int): String? {
    return try {
        if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return null
        val stripper = PDFTextStripper().apply {
            startPage = pageIndex + 1 // PDFBox is 1-based
            endPage = pageIndex + 1
            // Physical order, not visual: avoids merged columns in invoices.
            sortByPosition = true
        }
        val raw = stripper.getText(doc)?.trim() ?: return null
        // 1) Too short: empty cover or a 2-3 char artifact.
        if (raw.length < 20) return null
        // 2) Low alphanumeric ratio: scanned page without real text
        // (dashes, table dots, vector garbage only).
        val alnum = raw.count { it.isLetterOrDigit() }
        if (alnum.toFloat() / raw.length < 0.3f) return null
        // 3) No line with content: only breaks/spaces.
        if (raw.lines().none { it.trim().length > 3 }) return null
        raw
    } catch (_: Exception) {
        null // When in doubt, the caller falls back to OCR
    }
}
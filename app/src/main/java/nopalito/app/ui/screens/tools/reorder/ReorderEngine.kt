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

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import nopalito.app.ui.screens.tools.reorder.ReorderEngine.reorderPages
import java.io.File

/**
 * PDF page operations for the "Reorder PDF pages" tool.
 *
 * - Reordering uses PDFBox (the same library as the compressor / extract
 *   tool): pages are copied via [PDDocument.importPage], which clones the page
 *   object **and its resources**; the content streams are carried over
 *   untouched, so text stays selectable and fonts/images/vectors/layout/
 *   orientation/size are preserved. Nothing is rasterized.
 * - The tool never builds the PDF during preview; it only reads the page list
 *   and renders previews with the native renderer. [reorderPages] is the only
 *   place where a new file is produced (on export).
 *
 * This layer is independent of Android (it receives local [File]s), so it can
 * be covered by pure JVM unit tests, like `ExtractEngine`.
 */
object ReorderEngine {

    /**
     * Returns [list] with the element at [from] moved to position [to]
     * (0-based). Pure function so the drag-and-drop behavior can be unit
     * tested; out-of-range values are clamped.
     */
    fun moveElement(list: List<Int>, from: Int, to: Int): List<Int> {
        if (list.isEmpty()) return list
        val safeFrom = from.coerceIn(0, list.lastIndex)
        val safeTo = to.coerceIn(0, list.lastIndex)
        if (safeFrom == safeTo) return list
        val mutable = list.toMutableList()
        val element = mutable.removeAt(safeFrom)
        mutable.add(safeTo, element)
        return mutable
    }

    /**
     * Opens [input] and returns its page count, closing the document.
     * @throws com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
     *   when the PDF is password-protected.
     */
    fun pageCount(input: File): Int =
        PDDocument.load(input).use { it.numberOfPages }

    /**
     * Creates a brand-new PDF at [output] with the pages of [input] in the
     * order given by [orderIndices] (0-based original indices). The original
     * document is never modified, so the tool can be exported any number of
     * times. [password], when not blank, encrypts the result with a 128-bit
     * standard policy (same as the compressor / extract tool). [onProgress]
     * reports (done, total) copied pages.
     */
    fun reorderPages(
        input: File,
        orderIndices: List<Int>,
        output: File,
        password: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        PDDocument.load(input).use { source ->
            PDDocument().use { target ->
                orderIndices.forEachIndexed { index, pageIndex ->
                    target.importPage(source.getPage(pageIndex))
                    onProgress(index + 1, orderIndices.size)
                }
                if (!password.isNullOrBlank()) {
                    val policy = StandardProtectionPolicy(password, password, AccessPermission())
                    policy.encryptionKeyLength = 128
                    target.protect(policy)
                }
                target.save(output)
            }
        }
    }
}
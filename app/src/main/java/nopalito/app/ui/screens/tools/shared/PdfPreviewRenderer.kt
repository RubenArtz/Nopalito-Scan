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

package nopalito.app.ui.screens.tools.shared

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Native PDF page rendering shared by every tool preview (Compress / Protect /
 * Convert via [FilePreviewController], Reorder, Delete pages, Organize and
 * Extract). One implementation, no duplicated code.
 *
 * The renderer is never kept open by this object: the callers own their
 * [PdfRenderer] instances and just ask for one page bitmap at a time.
 */
object PdfPreviewRenderer {

    /**
     * Renders one page at the given pixel width with a white background
     * (Pdfium does not paint it, so text-only pages would otherwise render
     * over a transparent bitmap). Returns null when the page cannot be
     * rendered (e.g. a password-protected PDF).
     */
    fun renderPage(renderer: PdfRenderer, pageIndex: Int, targetWidthPx: Int): Bitmap? =
        try {
            val page = renderer.openPage(pageIndex)
            val scale = targetWidthPx.toFloat() / page.width.coerceAtLeast(1)
            val bitmap = androidx.core.graphics.createBitmap(
                maxOf(1, (page.width * scale).toInt()),
                maxOf(1, (page.height * scale).toInt()),
            )
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        } catch (_: Exception) {
            null
        }

    /**
     * Renders one page, retrying at half width when very large pages exhaust
     * the heap.
     */
    fun renderWithOomRetry(renderer: PdfRenderer, pageIndex: Int, targetWidthPx: Int): Bitmap? =
        try {
            renderPage(renderer, pageIndex, targetWidthPx)
        } catch (_: OutOfMemoryError) {
            renderPage(renderer, pageIndex, targetWidthPx / 2)
        }

    /** Opens [file] transiently and returns its page count (null on failure). */
    fun countPdfPages(file: File): Int? =
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer -> renderer.pageCount }
            }
        } catch (_: Exception) {
            null
        }
}

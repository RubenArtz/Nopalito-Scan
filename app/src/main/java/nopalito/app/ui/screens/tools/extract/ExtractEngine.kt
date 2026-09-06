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

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * PDF page operations for the "Extract pages" tool.
 *
 * - Page copy / export to PDF uses PDFBox (the same library as the compressor)
 *   and is pure JVM so it can be covered by unit tests.
 * - Rendering (preview + image export) uses Android's native
 *   [PdfRenderer], exactly like the History and Cloud previews
 *   (`ui/DocumentPreview.kt`): faster, lighter and without PDFBox's font
 *   fallback noise.
 */
object ExtractEngine {

    /**
     * Opens [input] and returns its page count, closing the document.
     * @throws com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
     *   when the PDF is password-protected.
     */
    fun pageCount(input: File): Int =
        PDDocument.load(input).use { it.numberOfPages }

    /**
     * Copies the pages at [pageIndices] (0-based, ascending) from [input] into
     * a brand-new PDF saved at [output]. [password], when not blank, encrypts
     * the result with a 128-bit standard policy (same as the compressor).
     *
     * Pages are copied via [PDDocument.importPage], which clones the page object
     * **and its resources**: content streams are carried over untouched, so
     * text stays selectable and fonts/images/vectors/layout/orientation/size are
     * preserved. Nothing is rasterized.
     */
    fun extractPdf(input: File, pageIndices: List<Int>, output: File, password: String?) {
        PDDocument.load(input).use { source ->
            PDDocument().use { target ->
                for (index in pageIndices) {
                    target.importPage(source.getPage(index))
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

    /**
     * Renders page [pageIndex] of [renderer] at [dpi] (points → pixels), used
     * for the PNG image export. Returns null when the page cannot be rendered.
     */
    fun renderPageAtDpi(renderer: PdfRenderer, pageIndex: Int, dpi: Int): Bitmap? =
        try {
            val page = renderer.openPage(pageIndex)
            val scale = dpi / 72f
            val bitmap = createBitmap(
                maxOf(1, (page.width * scale).toInt()),
                maxOf(1, (page.height * scale).toInt()),
            )
            // Pdfium does not paint the page background: fill it white so exported
            // PNGs of text-only pages are not transparent.
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        } catch (_: Exception) {
            null
        }

    /**
     * Renders every page in [pageIndices] (0-based, ascending) to a PNG
     * `pagina_001.png`, `pagina_002.png`, … inside [outputDir], reporting
     * progress through [onProgress] (done, total). Returns the written files.
     *
     * Pages are rendered one at a time and each bitmap is recycled as soon as
     * it is written, so memory stays flat regardless of page count.
     */
    fun extractImages(
        input: File,
        pageIndices: List<Int>,
        outputDir: File,
        dpi: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<File> {
        outputDir.mkdirs()
        val pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { renderer ->
                val written = mutableListOf<File>()
                pageIndices.forEachIndexed { index, pageIndex ->
                    val bitmap = renderPageAtDpi(renderer, pageIndex, dpi)
                        ?: throw IOException("Cannot render page ${pageIndex + 1}")
                    val file = File(outputDir, "pagina_%03d.png".format(index + 1))
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    bitmap.recycle()
                    written += file
                    onProgress(index + 1, pageIndices.size)
                }
                return written
            }
        } finally {
            pfd.close()
        }
    }
}

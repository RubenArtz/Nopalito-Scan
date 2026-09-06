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

package nopalito.app.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import nopalito.app.R
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/** One page of a document preview: its image and the OCR text of that page. */
data class DocPagePreview(
    val image: Bitmap?,
    val text: String?,
) {
    val hasContent get() = image != null || !text.isNullOrBlank()
}

const val MAX_PDF_PREVIEW_PAGES = 30
const val MAX_PDF_PREVIEW_DIM = 1400f

/**
 * Decodes the pages of a .docx: each page image from word/media plus the OCR text
 * paragraphs of that page from word/document.xml.
 */
fun decodeDocxPages(file: File, maxDim: Int): List<DocPagePreview> {
    val images = decodeDocxMedia(file, maxDim)
    val texts = extractDocxPageTexts(file)
    val pages = mutableListOf<DocPagePreview>()
    for (i in 0 until maxOf(images.size, texts.size)) {
        val page = DocPagePreview(
            image = images.getOrNull(i),
            text = texts.getOrNull(i)?.takeIf { it.isNotBlank() },
        )
        if (!page.hasContent) break
        pages += page
    }
    return pages
}

/**
 * True when the PDF file carries an /Encrypt dictionary (password protected).
 * The whole file is scanned with a sliding window: encrypted linearized
 * documents can place the trailer (and the /Encrypt key) beyond the first MB.
 * PdfRenderer cannot open such files, so the UI shows a clear message instead
 * of the generic "cannot render" error.
 */
fun isPdfEncrypted(file: File): Boolean = try {
    FileInputStream(file).use { input ->
        val buf = ByteArray(64 * 1024)
        val tail = ByteArray(PDF_ENCRYPT_MARKER.size - 1)
        var n = input.read(buf)
        while (n >= 0) {
            val combined = ByteArray(tail.size + n)
            System.arraycopy(tail, 0, combined, 0, tail.size)
            System.arraycopy(buf, 0, combined, tail.size, n)
            if (containsMarker(combined, PDF_ENCRYPT_MARKER)) return true
            val keep = minOf(PDF_ENCRYPT_MARKER.size - 1, n)
            System.arraycopy(buf, n - keep, tail, 0, keep)
            n = input.read(buf)
        }
        false
    }
} catch (_: Throwable) {
    false
}

/** ASCII marker written by the PDF writer when a document is encrypted. */
private val PDF_ENCRYPT_MARKER = "/Encrypt".toByteArray(Charsets.ISO_8859_1)

private fun containsMarker(haystack: ByteArray, needle: ByteArray): Boolean {
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) continue@outer
        }
        return true
    }
    return false
}

/**
 * True when the document is an OLE2/CFB container (magic D0CF11E0A1B11AE1)
 * carrying the agile-encryption streams — the signature of a password
 * protected OOXML file (docx/xlsx/pptx). A plain .doc is also OLE2 but has no
 * EncryptedPackage/EncryptionInfo stream, so only those count.
 */
fun isEncryptedOle2(file: File): Boolean = try {
    FileInputStream(file).use { input ->
        val magic = ByteArray(8)
        if (input.read(magic) != 8) return false
        if (magic[0] != 0xd0.toByte() || magic[1] != 0xcf.toByte() ||
            magic[2] != 0x11.toByte() || magic[3] != 0xe0.toByte() ||
            magic[4] != 0xa1.toByte() || magic[5] != 0xb1.toByte() ||
            magic[6] != 0x1a.toByte() || magic[7] != 0xe1.toByte()
        ) return false
        val head = ByteArray(4 * 1024 * 1024)
        val read = input.read(head)
        val rest = ByteArray(read.coerceAtLeast(0)) { head[it] }
        val latin = String(magic + rest, Charsets.ISO_8859_1)
        val utf16 = String(magic + rest, Charsets.UTF_16LE)
        latin.contains("EncryptedPackage") || latin.contains("EncryptionInfo") ||
                utf16.contains("EncryptedPackage") || utf16.contains("EncryptionInfo")
    }
} catch (_: Throwable) {
    false
}

/** Renders the first pages of a PDF scaled down, as pure page images (no
 * extracted text layer).
 *
 * Only the first [MAX_PDF_PREVIEW_PAGES] pages are rendered, so huge documents
 * (e.g. 2000+ pages) only ever process what is actually shown on screen — the
 * same "don't touch every page" strategy the Extract tool uses for its lazy
 * preview.
 */
fun renderPdfPages(file: File, maxDim: Int = MAX_PDF_PREVIEW_DIM.toInt()): List<DocPagePreview> =
    try {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val toRender = minOf(renderer.pageCount, MAX_PDF_PREVIEW_PAGES)
                (0 until toRender).map { index ->
                    val page = renderer.openPage(index)
                    try {
                        val scale = minOf(1f, maxDim.toFloat() / maxOf(page.width, page.height))
                        val bmp = createBitmap(
                            maxOf(1, (page.width * scale).toInt()),
                            maxOf(1, (page.height * scale).toInt()),
                        )
                        // Pdfium does not paint the page background: a text-only page
                        // would otherwise render over a transparent bitmap.
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        DocPagePreview(bmp, null)
                    } finally {
                        page.close()
                    }
                }
            }
        }
    } catch (_: Throwable) {
        // Includes OutOfMemoryError: a preview must degrade to the "cannot
        // render" message instead of crashing the app.
        emptyList()
    }

/** Scrollable preview: each page shows its image and, below it, its OCR text. Tap a page to zoom it. */
@Composable
fun DocumentPagesPreview(pages: List<DocPagePreview>, modifier: Modifier = Modifier) {
    var zoomPage by remember { mutableStateOf<Bitmap?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(pages) { _, page ->
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (page.image != null) {
                    Image(
                        bitmap = page.image.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { zoomPage = page.image },
                        contentScale = ContentScale.Fit,
                    )
                }
                if (!page.text.isNullOrBlank()) {
                    Text(
                        text = page.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    zoomPage?.let { bitmap ->
        ZoomableBitmapDialog(bitmap = bitmap, onDismiss = { zoomPage = null })
    }
}

/**
 * Full-screen pinch-zoom dialog over a page image. Uses the same gesture pattern
 * as the Cloud image preview (transform gestures + graphics layer).
 */
@Composable
fun ZoomableBitmapDialog(bitmap: Bitmap, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offsetX by remember { mutableFloatStateOf(0f) }
            var offsetY by remember { mutableFloatStateOf(0f) }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    },
                contentScale = ContentScale.Fit,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                )
            }
        }
    }
}

private fun extractDocxPageTexts(file: File): List<String> {
    val xml = readDocumentXml(file) ?: return emptyList()
    val pages = mutableListOf<StringBuilder>()
    var current = -1
    val paragraph = Regex("<w:p[^>]*>[\\s\\S]*?</w:p>")
    val textTag = Regex("<w:t[^>]*>([\\s\\S]*?)</w:t>")
    paragraph.findAll(xml).forEach { m ->
        val block = m.value
        if (block.contains("<w:drawing")) {
            current++
            pages += StringBuilder()
        } else if (current >= 0) {
            val sb = pages[current]
            textTag.findAll(block).forEach { t ->
                val value = t.groupValues[1]
                if (sb.isNotEmpty() && !sb.endsWith(' ')) sb.append(' ')
                sb.append(value)
            }
            if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
        }
    }
    return pages.map { it.toString().trim() }
}

private fun readDocumentXml(file: File): String? {
    ZipInputStream(FileInputStream(file)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: return null
            if (entry.name == "word/document.xml") {
                return zip.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }
    }
}
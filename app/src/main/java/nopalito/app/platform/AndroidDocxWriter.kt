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

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nopalito.app.data.DocxWriter
import nopalito.app.domain.Jpeg
import nopalito.app.domain.OcrService
import nopalito.app.domain.PageToExport
import nopalito.app.platform.crypto.DocxEncryptor
import nopalito.imageprocessing.OcrTextBox
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * Writes scanned pages to a valid OOXML .docx package.
 *
 * The generated file contains:
 * - Office Open XML content types
 * - Package and document relationships
 * - Core and application properties
 * - Embedded JPEG or PNG images
 * - OCR text paragraphs when OCR is enabled
 *
 * It is intended to open in Microsoft Word, LibreOffice, Google Docs,
 * and Android office applications.
 */
class AndroidDocxWriter(
    private val ocrService: OcrService,
) : DocxWriter {

    override suspend fun writeDocxFromJpegs(
        pages: List<PageToExport>,
        outputStream: OutputStream,
        disableOcr: Boolean,
        password: String?,
        onProgress: (Int) -> Unit,
    ) {
        val pagesMedia = mutableListOf<DocxPageMedia>()

        for ((index, page) in pages.withIndex()) {
            val jpeg = page.jpeg.get()
            val baseBitmap = jpeg.toBitmap()

            try {
                val widthPx = baseBitmap.width
                val heightPx = baseBitmap.height

                val composedBitmap = composeOverlaysOnBitmap(
                    baseBitmap = baseBitmap,
                    overlays = page.overlays,
                )

                val imageBytes: ByteArray
                val isPng: Boolean

                if (composedBitmap != null) {
                    try {
                        // JPEG is ~10x faster than PNG deflate on photographic
                        // content (same fix as PdfWriter). White backdrop already
                        // baked in, so JPEG quality 85 is visually lossless.
                        imageBytes = bitmapToJpegBytes(composedBitmap, 85)
                        isPng = false
                    } finally {
                        if (!composedBitmap.isRecycled) {
                            composedBitmap.recycle()
                        }
                    }
                } else {
                    imageBytes = jpeg.bytes
                    isPng = false
                }

                val ocrLines = if (disableOcr) {
                    emptyList()
                } else {
                    // Reuse already-decoded baseBitmap (OcrService downscales internally)
                    runOcrLines(baseBitmap, index)
                }

                pagesMedia += DocxPageMedia(
                    bytes = imageBytes,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    isPng = isPng,
                    ocrLines = ocrLines,
                )

                onProgress(index + 1)
            } finally {
                if (!baseBitmap.isRecycled) {
                    baseBitmap.recycle()
                }
            }
        }

        val plain = ByteArrayOutputStream()
        DocxFileBuilder.write(pagesMedia, plain)
        val bytes = plain.toByteArray()
        if (password.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                outputStream.write(bytes)
            }
        } else {
            withContext(Dispatchers.IO) {
                outputStream.write(DocxEncryptor.encryptBytes(bytes, password))
            }
        }
    }

    private suspend fun runOcrLines(
        jpeg: Jpeg,
        pageIndex: Int,
    ): List<String> {
        val bitmap = jpeg.toBitmap()

        return try {
            val textBoxes = ocrService.runOcr(bitmap)
            groupWordsIntoLines(textBoxes)
        } catch (error: Exception) {
            Log.e(
                "AndroidDocxWriter",
                "OCR failed for page $pageIndex",
                error,
            )
            emptyList()
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private suspend fun runOcrLines(
        bitmap: Bitmap,
        pageIndex: Int,
    ): List<String> {
        return try {
            val textBoxes = ocrService.runOcr(bitmap)
            groupWordsIntoLines(textBoxes)
        } catch (error: Exception) {
            Log.e(
                "AndroidDocxWriter",
                "OCR failed for page $pageIndex",
                error,
            )
            emptyList()
        }
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int): ByteArray {
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), output)
            output.toByteArray()
        }
    }

    @Suppress("unused")
    private fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
    }
}

/**
 * Groups OCR words into lines ordered from top to bottom,
 * then from left to right.
 */
internal fun groupWordsIntoLines(
    textBoxes: List<OcrTextBox>,
): List<String> {
    if (textBoxes.isEmpty()) {
        return emptyList()
    }

    val sortedWords = textBoxes.sortedWith(
        compareBy<OcrTextBox> { it.lineBottom }
            .thenBy { it.box.left },
    )

    val lines = mutableListOf<MutableList<OcrTextBox>>()

    for (word in sortedWords) {
        val currentLine = lines.lastOrNull()
        val tolerance = (word.lineHeight / 3f).coerceAtLeast(2f)

        if (
            currentLine != null &&
            abs(currentLine.first().lineBottom - word.lineBottom) <= tolerance
        ) {
            currentLine += word
        } else {
            lines += mutableListOf(word)
        }
    }

    return lines.mapNotNull { line ->
        line.sortedBy { it.box.left }
            .joinToString(separator = " ") { it.text.trim() }
            .trim()
            .takeIf { it.isNotEmpty() }
    }
}

/**
 * A media item stored under word/media in the DOCX package.
 */
internal data class DocxPageMedia(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val isPng: Boolean,
    val ocrLines: List<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DocxPageMedia) return false

        return widthPx == other.widthPx &&
                heightPx == other.heightPx &&
                isPng == other.isPng &&
                bytes.contentEquals(other.bytes) &&
                ocrLines == other.ocrLines
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        result = 31 * result + isPng.hashCode()
        result = 31 * result + ocrLines.hashCode()
        return result
    }
}

/**
 * Creates the OOXML ZIP package without external DOCX libraries.
 */
internal object DocxFileBuilder {

    private const val EMU_PER_PIXEL = 9_525L
    private const val MAX_IMAGE_WIDTH_EMU = 5_943_600L
    private const val MAX_IMAGE_HEIGHT_EMU = 8_229_600L

    private const val OFFICE_DOCUMENT_RELATIONSHIP =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"

    private const val CORE_PROPERTIES_RELATIONSHIP =
        "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"

    private const val APP_PROPERTIES_RELATIONSHIP =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties"

    private const val IMAGE_RELATIONSHIP =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"

    fun write(
        media: List<DocxPageMedia>,
        outputStream: OutputStream,
    ) {
        ZipOutputStream(outputStream).use { zip ->
            addTextEntry(
                zip = zip,
                path = "[Content_Types].xml",
                value = buildContentTypesXml(media),
            )

            addTextEntry(
                zip = zip,
                path = "_rels/.rels",
                value = buildRootRelationshipsXml(),
            )

            addTextEntry(
                zip = zip,
                path = "docProps/core.xml",
                value = buildCorePropertiesXml(),
            )

            addTextEntry(
                zip = zip,
                path = "docProps/app.xml",
                value = buildApplicationPropertiesXml(media.size),
            )

            addTextEntry(
                zip = zip,
                path = "word/document.xml",
                value = buildDocumentXml(media),
            )

            addTextEntry(
                zip = zip,
                path = "word/_rels/document.xml.rels",
                value = buildDocumentRelationshipsXml(media),
            )

            media.forEachIndexed { index, page ->
                val extension = if (page.isPng) "png" else "jpg"

                addBinaryEntry(
                    zip = zip,
                    path = "word/media/page${index + 1}.$extension",
                    bytes = page.bytes,
                )
            }
        }
    }

    private fun addTextEntry(
        zip: ZipOutputStream,
        path: String,
        value: String,
    ) {
        addBinaryEntry(
            zip = zip,
            path = path,
            bytes = value.toByteArray(Charsets.UTF_8),
        )
    }

    private fun addBinaryEntry(
        zip: ZipOutputStream,
        path: String,
        bytes: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun buildContentTypesXml(
        media: List<DocxPageMedia>,
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append('\n')
        append(
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""",
        )
        append('\n')

        append(
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""",
        )
        append('\n')

        append(
            """<Default Extension="xml" ContentType="application/xml"/>""",
        )
        append('\n')

        if (media.any { !it.isPng }) {
            append(
                """<Default Extension="jpg" ContentType="image/jpeg"/>""",
            )
            append('\n')
        }

        if (media.any { it.isPng }) {
            append(
                """<Default Extension="png" ContentType="image/png"/>""",
            )
            append('\n')
        }

        append(
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""",
        )
        append('\n')

        append(
            """<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""",
        )
        append('\n')

        append(
            """<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""",
        )
        append('\n')

        append("</Types>")
    }

    private fun buildRootRelationshipsXml(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append('\n')
        append(
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""",
        )
        append('\n')

        append(
            """<Relationship Id="rId1" Type="$OFFICE_DOCUMENT_RELATIONSHIP" Target="word/document.xml"/>""",
        )
        append('\n')

        append(
            """<Relationship Id="rId2" Type="$CORE_PROPERTIES_RELATIONSHIP" Target="docProps/core.xml"/>""",
        )
        append('\n')

        append(
            """<Relationship Id="rId3" Type="$APP_PROPERTIES_RELATIONSHIP" Target="docProps/app.xml"/>""",
        )
        append('\n')

        append("</Relationships>")
    }

    private fun buildDocumentRelationshipsXml(
        media: List<DocxPageMedia>,
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append('\n')
        append(
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""",
        )
        append('\n')

        media.forEachIndexed { index, page ->
            val extension = if (page.isPng) "png" else "jpg"

            append(
                """<Relationship Id="rIdImage${index + 1}" Type="$IMAGE_RELATIONSHIP" Target="media/page${index + 1}.$extension"/>""",
            )
            append('\n')
        }

        append("</Relationships>")
    }

    private fun buildDocumentXml(
        media: List<DocxPageMedia>,
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append('\n')

        append(
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">""",
        )
        append('\n')
        append("<w:body>")
        append('\n')

        if (media.isEmpty()) {
            append("""<w:p><w:r><w:t>Empty document</w:t></w:r></w:p>""")
            append('\n')
        }

        media.forEachIndexed { index, page ->
            append(buildImageParagraph(index, page))
            append('\n')

            page.ocrLines.forEach { line ->
                append(buildTextParagraph(line))
                append('\n')
            }

            if (index < media.lastIndex) {
                append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
                append('\n')
            }
        }

        append(
            """<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="720" w:right="720" w:bottom="720" w:left="720" w:header="360" w:footer="360" w:gutter="0"/></w:sectPr>""",
        )
        append('\n')

        append("</w:body>")
        append('\n')
        append("</w:document>")
    }

    private fun buildImageParagraph(
        index: Int,
        page: DocxPageMedia,
    ): String {
        val size = scaleToDocumentSize(
            widthPx = page.widthPx,
            heightPx = page.heightPx,
        )

        val imageId = index + 1
        val relationshipId = "rIdImage$imageId"
        val fileName = if (page.isPng) {
            "page$imageId.png"
        } else {
            "page$imageId.jpg"
        }

        return buildString {
            append("<w:p>")
            append("<w:pPr><w:jc w:val=\"center\"/></w:pPr>")
            append("<w:r><w:drawing>")
            append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
            append("<wp:extent cx=\"${size.first}\" cy=\"${size.second}\"/>")
            append("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>")
            append("<wp:docPr id=\"$imageId\" name=\"Image $imageId\" descr=\"$fileName\"/>")
            append("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
            append("<a:graphic>")
            append("<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
            append("<pic:pic>")
            append("<pic:nvPicPr>")
            append("<pic:cNvPr id=\"$imageId\" name=\"$fileName\"/>")
            append("<pic:cNvPicPr/>")
            append("</pic:nvPicPr>")
            append("<pic:blipFill>")
            append("<a:blip r:embed=\"$relationshipId\"/>")
            append("<a:stretch><a:fillRect/></a:stretch>")
            append("</pic:blipFill>")
            append("<pic:spPr>")
            append("<a:xfrm>")
            append("<a:off x=\"0\" y=\"0\"/>")
            append("<a:ext cx=\"${size.first}\" cy=\"${size.second}\"/>")
            append("</a:xfrm>")
            append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>")
            append("</pic:spPr>")
            append("</pic:pic>")
            append("</a:graphicData>")
            append("</a:graphic>")
            append("</wp:inline>")
            append("</w:drawing></w:r>")
            append("</w:p>")
        }
    }

    private fun buildTextParagraph(
        text: String,
    ): String {
        val safeText = xmlEscape(cleanXmlText(text))

        return buildString {
            append("<w:p>")
            append("<w:pPr><w:spacing w:after=\"60\"/></w:pPr>")
            append("<w:r>")
            append("<w:t xml:space=\"preserve\">")
            append(safeText)
            append("</w:t>")
            append("</w:r>")
            append("</w:p>")
        }
    }

    private fun scaleToDocumentSize(
        widthPx: Int,
        heightPx: Int,
    ): Pair<Long, Long> {
        val safeWidth = widthPx.coerceAtLeast(1)
        val safeHeight = heightPx.coerceAtLeast(1)

        val originalWidthEmu = safeWidth.toLong() * EMU_PER_PIXEL
        val originalHeightEmu = safeHeight.toLong() * EMU_PER_PIXEL

        val scale = minOf(
            1.0,
            MAX_IMAGE_WIDTH_EMU.toDouble() / originalWidthEmu.toDouble(),
            MAX_IMAGE_HEIGHT_EMU.toDouble() / originalHeightEmu.toDouble(),
        )

        return Pair(
            (originalWidthEmu * scale).toLong().coerceAtLeast(1L),
            (originalHeightEmu * scale).toLong().coerceAtLeast(1L),
        )
    }

    private fun buildCorePropertiesXml(): String {
        val timestamp = currentIsoUtcTimestamp()

        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <dc:title>NopalitoScan export</dc:title>
              <dc:creator>NopalitoScan</dc:creator>
              <cp:lastModifiedBy>NopalitoScan</cp:lastModifiedBy>
              <dcterms:created xsi:type="dcterms:W3CDTF">$timestamp</dcterms:created>
              <dcterms:modified xsi:type="dcterms:W3CDTF">$timestamp</dcterms:modified>
            </cp:coreProperties>
        """.trimIndent()
    }

    private fun buildApplicationPropertiesXml(
        pageCount: Int,
    ): String {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
              <Application>NopalitoScan</Application>
              <DocSecurity>0</DocSecurity>
              <ScaleCrop>false</ScaleCrop>
              <Pages>$pageCount</Pages>
              <Company>NopalitoScan</Company>
              <LinksUpToDate>false</LinksUpToDate>
              <SharedDoc>false</SharedDoc>
              <HyperlinksChanged>false</HyperlinksChanged>
              <AppVersion>1.0</AppVersion>
            </Properties>
        """.trimIndent()
    }

    private fun currentIsoUtcTimestamp(): String {
        return SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.US,
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    private fun cleanXmlText(
        text: String,
    ): String {
        return buildString(text.length) {
            text.forEach { character ->
                if (
                    character == '\t' ||
                    character == '\n' ||
                    character == '\r' ||
                    character.code >= 0x20
                ) {
                    append(character)
                }
            }
        }
    }

    internal fun xmlEscape(
        text: String,
    ): String {
        return buildString(text.length + 16) {
            text.forEach { character ->
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(character)
                }
            }
        }
    }
}
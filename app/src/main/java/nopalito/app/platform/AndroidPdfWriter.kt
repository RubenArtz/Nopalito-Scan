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

import android.content.res.AssetManager
import android.graphics.*
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFontDescriptor
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import nopalito.app.BuildConfig
import nopalito.app.data.PdfWriter
import nopalito.app.domain.OcrService
import nopalito.app.domain.PageToExport
import nopalito.app.ui.screens.document.DateBackgroundStyle
import nopalito.app.ui.screens.document.OverlayConstants
import nopalito.imageprocessing.EstimatedDimensions
import nopalito.imageprocessing.OcrTextBox
import nopalito.imageprocessing.PaperFormats
import java.io.OutputStream
import java.util.*

/**
 * Composes signature bitmap and date text overlays onto the base bitmap.
 * Returns a new bitmap with overlays drawn, or null if no overlays are present.
 */
internal fun composeOverlaysOnBitmap(
    baseBitmap: Bitmap,
    overlays: PageToExport.PageExportOverlays?
): Bitmap? {
    if (overlays == null) return null
    val hasSignature = overlays.signatureBitmap != null
            && overlays.signaturePositionFractionX != null
            && overlays.signaturePositionFractionY != null
    val hasDate = overlays.dateText != null
            && overlays.datePositionFractionX != null
            && overlays.datePositionFractionY != null
    if (!hasSignature && !hasDate) return null

    val result = createBitmap(baseBitmap.width, baseBitmap.height)
    val canvas = Canvas(result)
    // Opaque white backdrop: transparent pixels in the base bitmap (rotated
    // page corners, signature after background removal) export as WHITE
    // instead of BLACK in JPEG (no alpha channel) and LosslessFactory PDFs.
    // The base bitmap is then drawn ON TOP, so the page photo is preserved.
    canvas.drawColor(Color.WHITE, PorterDuff.Mode.SRC)
    canvas.drawBitmap(baseBitmap, 0f, 0f, null)
    val srcW = baseBitmap.width.toFloat()
    val srcH = baseBitmap.height.toFloat()

    // Draw signature — uses same constants as editor (OverlayConstants)
    if (hasSignature) {
        val sigBitmap = overlays.signatureBitmap
        val sigX = overlays.signaturePositionFractionX * srcW
        val sigY = overlays.signaturePositionFractionY * srcH
        val maxW = srcW * OverlayConstants.SIGNATURE_WIDTH_FRACTION
        val maxH = srcH * OverlayConstants.SIGNATURE_HEIGHT_FRACTION
        val (baseW, baseH) = OverlayConstants.computeSignatureBaseSize(
            sigBitmap.width, sigBitmap.height, maxW, maxH
        )
        val sigW = baseW * overlays.signatureScale
        val sigH = baseH * overlays.signatureScale
        // Axis-aligned visual size of the rotated overlay (matches editor).
        val degrees = ((overlays.signatureRotationDegrees % 360f) + 360f) % 360f
        val (visW, visH) = OverlayConstants.rotatedVisualSize(sigW, sigH, degrees)
        val clampedSigX = sigX.coerceIn(0f, srcW - visW)
        val clampedSigY = sigY.coerceIn(0f, srcH - visH)
        if (degrees == 0f) {
            val destRect = RectF(clampedSigX, clampedSigY, clampedSigX + sigW, clampedSigY + sigH)
            canvas.drawBitmap(sigBitmap, null, destRect, null)
        } else {
            val centerX = clampedSigX + visW / 2f
            val centerY = clampedSigY + visH / 2f
            canvas.withRotation(degrees, centerX, centerY) {
                val destRect = RectF(
                    centerX - sigW / 2f,
                    centerY - sigH / 2f,
                    centerX + sigW / 2f,
                    centerY + sigH / 2f,
                )
                drawBitmap(sigBitmap, null, destRect, null)
            }
        }
    }

    // Draw date text using style from editor
    if (hasDate) {
        val textColor = overlays.dateStyleTextColor.toInt()
        val bgStyle = overlays.dateStyleBackgroundStyle
        val bgColor = overlays.dateStyleBackgroundColor.toInt()
        val backgroundStyle = runCatching { DateBackgroundStyle.valueOf(bgStyle) }
            .getOrDefault(DateBackgroundStyle.CAPSULE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = OverlayConstants.computeDateFontSizePx(
                imageWidth = srcW,
                styleFontSize = overlays.dateStyleFontSize,
                scale = overlays.dateScale,
            )
            isFakeBoldText = true // matches FontWeight.SemiBold in editor
        }
        val metrics = OverlayConstants.dateMetrics(
            text = overlays.dateText,
            fontSizePx = paint.textSize,
            backgroundStyle = backgroundStyle,
        )
        // Fractions refer to the complete overlay box in both renderers.
        val dateLeft = overlays.datePositionFractionX * srcW
        val dateTop = overlays.datePositionFractionY * srcH
        val dateDegrees = ((overlays.dateRotationDegrees % 360f) + 360f) % 360f
        // For 90°/270° rotation the visual width/height swap (same as editor).
        val (dateVisW, dateVisH) = OverlayConstants.rotatedVisualSize(
            metrics.widthPx, metrics.heightPx, dateDegrees
        )
        val maxDateLeft = (srcW - dateVisW).coerceAtLeast(0f)
        val maxDateTop = (srcH - dateVisH).coerceAtLeast(0f)
        val clampedDateLeft = dateLeft.coerceIn(0f, maxDateLeft)
        val clampedDateTop = dateTop.coerceIn(0f, maxDateTop)
        val bgPaint = Paint().apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        if (dateDegrees == 0f) {
            val textX = clampedDateLeft + metrics.horizontalPaddingPx
            val baseline = clampedDateTop + metrics.verticalPaddingPx + metrics.ascentPx
            val bgRect = RectF(
                clampedDateLeft,
                clampedDateTop,
                clampedDateLeft + metrics.widthPx,
                clampedDateTop + metrics.heightPx,
            )
            if (backgroundStyle != DateBackgroundStyle.NONE) {
                canvas.drawRoundRect(
                    bgRect,
                    metrics.cornerRadiusPx,
                    metrics.cornerRadiusPx,
                    bgPaint,
                )
            }
            canvas.drawText(overlays.dateText, textX, baseline, paint)
        } else {
            val centerX = clampedDateLeft + dateVisW / 2f
            val centerY = clampedDateTop + dateVisH / 2f
            canvas.withRotation(dateDegrees, centerX, centerY) {
                val boxLeft = centerX - metrics.widthPx / 2f
                val boxTop = centerY - metrics.heightPx / 2f
                if (backgroundStyle != DateBackgroundStyle.NONE) {
                    drawRoundRect(
                        RectF(boxLeft, boxTop, boxLeft + metrics.widthPx, boxTop + metrics.heightPx),
                        metrics.cornerRadiusPx,
                        metrics.cornerRadiusPx,
                        bgPaint,
                    )
                }
                drawText(
                    overlays.dateText,
                    boxLeft + metrics.horizontalPaddingPx,
                    boxTop + metrics.verticalPaddingPx + metrics.ascentPx,
                    paint,
                )
            }
        }
    }

    return result
}

class AndroidPdfWriter(val ocrService: OcrService, val assets: AssetManager) : PdfWriter {

    override suspend fun writePdfFromJpegs(
        pages: List<PageToExport>,
        outputStream: OutputStream,
        disableOcr: Boolean,
        password: String?,
        onProgress: (Int) -> Unit,
    ) {
        val doc = PDDocument()
        doc.documentInformation.creationDate = Calendar.getInstance()
        doc.documentInformation.creator = "NopalitoScan ${BuildConfig.VERSION_NAME}"
        doc.use { document ->
            val ocrDocument = OcrDocument(document, assets)
            for ((index, page) in pages.withIndex()) {
                val jpeg = page.jpeg.get()
                val image = JPEGFactory.createFromByteArray(document, jpeg.bytes)

                // PDF has 72 points (units) per inch, 1 inch = 25.4 mm
                val pointsPerMm = 72f / 25.4f

                val widthPx = image.width.toFloat()
                val heightPx = image.height.toFloat()

                val dimensions = page.estimatedDimensions()
                val (widthMm, heightMm) = when (dimensions) {
                    is EstimatedDimensions.Physical ->
                        constrainToMaxFormat(dimensions.widthMm, dimensions.heightMm)

                    else -> {
                        // No physical dimensions available
                        val maxDimMm = PaperFormats.A4.heightMm
                        val scalePxToMm = maxDimMm / maxOf(widthPx, heightPx)
                        constrainToMaxFormat(widthPx * scalePxToMm, heightPx * scalePxToMm)
                    }
                }
                val widthPoints = widthMm.toFloat() * pointsPerMm
                val heightPoints = heightMm.toFloat() * pointsPerMm

                val pdPage = PDPage(PDRectangle(widthPoints, heightPoints))
                document.addPage(pdPage)

                val contentStream = PDPageContentStream(document, pdPage, AppendMode.OVERWRITE, false)

                // Compose overlays onto the bitmap if present
                val baseBitmap = jpeg.toBitmap()
                val composedBitmap = composeOverlaysOnBitmap(baseBitmap, page.overlays)
                if (composedBitmap != null) {
                    // Use LosslessFactory for the composed bitmap (PNG-like)
                    val pdImage = LosslessFactory.createFromImage(document, composedBitmap)
                    contentStream.drawImage(pdImage, 0f, 0f, widthPoints, heightPoints)
                    composedBitmap.recycle()
                } else {
                    contentStream.drawImage(image, 0f, 0f, widthPoints, heightPoints)
                }
                baseBitmap.recycle()

                if (!disableOcr) {
                    try {
                        val bitmap = jpeg.toBitmap()
                        val ocrTextBoxes = ocrService.runOcr(bitmap)
                        val pdfPageDimensions = PageDimensions(
                            bitmap.width,
                            bitmap.height,
                            widthPoints,
                            heightPoints
                        )
                        ocrDocument.addPage(pdPage, ocrTextBoxes, pdfPageDimensions)
                    } catch (e: Exception) {
                        Log.e("AndroidPdfWriter", "Failed to run OCR on page $index", e)
                    }
                }
                contentStream.close()

                onProgress(index + 1)
            }
            // TODO So the whole document is in memory before this line...
            if (!password.isNullOrEmpty()) {
                val policy = StandardProtectionPolicy(password, password, AccessPermission())
                policy.encryptionKeyLength = 128
                document.protect(policy)
            }
            document.save(outputStream)
        }
    }
}

fun constrainToMaxFormat(widthMm: Double, heightMm: Double): Pair<Double, Double> {
    val maxDim = 297.0   // A4 height
    val minDim = 215.9   // Letter width

    val scale = minOf(
        maxDim / maxOf(widthMm, heightMm),
        minDim / minOf(widthMm, heightMm),
        1.0
    )
    return widthMm * scale to heightMm * scale
}

data class PageDimensions(
    val imageWidth: Int,
    val imageHeight: Int,
    val pageWidth: Float,
    val pageHeight: Float,
)

// Adapted from https://github.com/tesseract-ocr/tesseract/blob/main/src/api/pdfrenderer.cpp
class OcrDocument(
    private val document: PDDocument,
    private val assets: AssetManager,
) {
    private val fontBytes: ByteArray by lazy {
        assets.open("fonts/TesseractGlyphLessFont.ttf").readBytes()
    }

    private val cidToGidMap: ByteArray by lazy {
        ByteArray(65536 * 2) { i -> if (i % 2 == 0) 0x00.toByte() else 0x01.toByte() }
    }

    private var fontDict: COSDictionary? = null

    fun ensureResourcesAreCreated() {
        if (fontDict != null)
            return

        // -- Object 1 : embedded TTF --
        val fontFileStream = PDStream(document)
        fontFileStream.createOutputStream().use { it.write(fontBytes) }
        fontFileStream.cosObject.setInt(COSName.LENGTH1, fontBytes.size)

        // -- Object 2 : CIDToGIDMap stream --
        val cidToGidStream = PDStream(document)
        cidToGidStream.createOutputStream(COSName.FLATE_DECODE).use {
            it.write(cidToGidMap)
        }

        // -- Object 3 : FontDescriptor --
        val fontDescriptor = PDFontDescriptor(COSDictionary())
        fontDescriptor.fontName = "GlyphLessFont"
        fontDescriptor.flags = 5
        fontDescriptor.fontBoundingBox = PDRectangle(0f, 0f, 500f, 750f)
        fontDescriptor.italicAngle = 0f
        fontDescriptor.ascent = 750f
        fontDescriptor.descent = 0f
        fontDescriptor.capHeight = 750f
        fontDescriptor.stemV = 80f
        fontDescriptor.cosObject.setItem(COSName.FONT_FILE2, fontFileStream)

        // -- Object 4 : CIDFont descendant --
        val cidFont = COSDictionary()
        cidFont.setName(COSName.TYPE, "Font")
        cidFont.setName(COSName.SUBTYPE, "CIDFontType2")
        cidFont.setName(COSName.BASE_FONT, "GlyphLessFont")
        val cidSystemInfo = COSDictionary()
        cidSystemInfo.setString(COSName.getPDFName("Registry"), "Adobe")
        cidSystemInfo.setString(COSName.getPDFName("Ordering"), "Identity")
        cidSystemInfo.setInt(COSName.getPDFName("Supplement"), 0)
        cidFont.setItem(COSName.getPDFName("CIDSystemInfo"), cidSystemInfo)
        cidFont.setItem(COSName.FONT_DESC, fontDescriptor)
        cidFont.setInt(COSName.getPDFName("DW"), 500)
        cidFont.setItem(COSName.getPDFName("CIDToGIDMap"), cidToGidStream)

        // -- Object 5 : ToUnicode CMap --
        val toUnicode = buildToUnicodeCMap()
        val toUnicodeStream = PDStream(document)
        toUnicodeStream.createOutputStream().use {
            it.write(toUnicode.toByteArray(Charsets.US_ASCII))
        }

        // -- Object 6 : Font Type0 --
        val fontDict = COSDictionary()
        fontDict.setName(COSName.TYPE, "Font")
        fontDict.setName(COSName.SUBTYPE, "Type0")
        fontDict.setName(COSName.BASE_FONT, "GlyphLessFont")
        fontDict.setName(COSName.ENCODING, "Identity-H")
        val descendants = COSArray()
        descendants.add(cidFont)
        fontDict.setItem(COSName.DESCENDANT_FONTS, descendants)
        fontDict.setItem(COSName.TO_UNICODE, toUnicodeStream)
        this.fontDict = fontDict
    }

    private fun buildToUnicodeCMap(): String = buildString {
        append("/CIDInit /ProcSet findresource begin\n")
        append("12 dict begin\n")
        append("begincmap\n")
        append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        append("/CMapName /Adobe-Identify-UCS def\n")
        append("/CMapType 2 def\n")
        append("1 begincodespacerange\n")
        append("<0000> <FFFF>\n")
        append("endcodespacerange\n")
        append("1 beginbfrange\n")
        append("<0000> <FFFF> <0000>\n")
        append("endbfrange\n")
        append("endcmap\n")
        append("CMapName currentdict /CMap defineresource pop\n")
        append("end\nend\n")
    }

    fun addPage(
        page: PDPage,
        ocrTextBoxes: List<OcrTextBox>,
        dimensions: PageDimensions,
    ) {
        if (ocrTextBoxes.isEmpty()) return

        ensureResourcesAreCreated()
        val resources = page.resources ?: PDResources().also { page.resources = it }
        val fontResources = resources.cosObject
            .getDictionaryObject(COSName.FONT) as? COSDictionary
            ?: COSDictionary().also {
                resources.cosObject.setItem(COSName.FONT, it)
            }
        fontResources.setItem(COSName.getPDFName("F1"), fontDict)

        val textStream = buildTextStream(ocrTextBoxes, dimensions)
        val pdTextStream = PDStream(document)
        pdTextStream.createOutputStream(COSName.FLATE_DECODE).use {
            it.write(textStream.toByteArray(Charsets.US_ASCII))
        }

        val contentsArray = COSArray()
        val iter = page.contentStreams
        while (iter.hasNext()) contentsArray.add(iter.next().cosObject)
        contentsArray.add(pdTextStream.cosObject)
        page.cosObject.setItem(COSName.CONTENTS, contentsArray)
    }

    private fun buildTextStream(
        ocrTextBoxes: List<OcrTextBox>,
        dimensions: PageDimensions,
    ): String {
        val scaleX = dimensions.pageWidth / dimensions.imageWidth
        val scaleY = dimensions.pageHeight / dimensions.imageHeight
        val sb = StringBuilder()

        for (textBox in ocrTextBoxes) {
            val x = textBox.box.left * scaleX
            val wordWidth = textBox.box.width * scaleX
            val fontSize = textBox.lineHeight * scaleY * 0.8f
            // nominal width: fontSize / kCharWidth (Tesseract convention)
            val nominalWidth = textBox.text.length * fontSize / 2f
            val hScale = if (nominalWidth > 0f) (wordWidth / nominalWidth) * 100f else 100f
            val baselineY = dimensions.pageHeight - (textBox.lineBottom * scaleY) + fontSize * 0.2f

            val utf16hex = buildString {
                textBox.text.codePoints().forEach { cp ->
                    append(codepointToUtf16beHex(cp))
                }
            }

            sb.append("BT\n")
            sb.append(String.format(Locale.US, "/F1 %.3f Tf\n", fontSize))
            sb.append(String.format(Locale.US, "%.3f Tz\n", hScale))
            sb.append("3 Tr\n")
            sb.append(String.format(Locale.US, "%.3f %.3f Td\n", x, baselineY))
            sb.append("<${utf16hex}0020> Tj\n")
            sb.append("ET\n")
        }
        return sb.toString()
    }

    private fun codepointToUtf16beHex(cp: Int): String {
        return if (cp < 0x10000) {
            "%04X".format(cp)
        } else {
            val a = cp - 0x10000
            val high = (a shr 10) + 0xD800
            val low = (a and 0x3FF) + 0xDC00
            "%04X%04X".format(high, low)
        }
    }
}

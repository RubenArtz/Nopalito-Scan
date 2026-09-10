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

package nopalito.app.domain

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nopalito.app.data.OcrLanguageRepository
import nopalito.imageprocessing.ImageRect
import nopalito.imageprocessing.OcrTextBox
import nopalito.imageprocessing.enhanceBwImage
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.sqrt

class OcrService(
    private val ocrLanguageRepository: OcrLanguageRepository,
    private val scope: CoroutineScope,
) {
    private var tess: TessBaseAPI? = null

    private val mutex = Mutex()

    private var languageString = ""
    fun languageString() = languageString

    fun initialize() {
        scope.launch {
            ocrLanguageRepository.enabledLanguages.collect { _ -> reinitialize() }
        }
    }

    private suspend fun reinitialize() {
        mutex.withLock {
            tess?.recycle()
            tess = null

            val raw = ocrLanguageRepository.buildTesseractLanguageString()
            // Safety cap: previous WiFi bug could enable 100+ languages
            // (afr+amh+ara+...), making Tesseract load dozens of models and
            // OCR 4 min per page. Cap to max 3 languages, prioritizing eng/spa.
            languageString = if (raw.isEmpty()) {
                ""
            } else {
                val parts = raw.split("+").filter { it.isNotBlank() }
                if (parts.size > 3) {
                    val keep = linkedSetOf<String>()
                    // Prioritize most common OCR languages
                    if ("eng" in parts) keep.add("eng")
                    if ("spa" in parts) keep.add("spa")
                    for (p in parts) {
                        if (keep.size >= 3) break
                        keep.add(p)
                    }
                    keep.joinToString("+")
                } else raw
            }
            if (languageString.isEmpty()) return

            val dataPath = ocrLanguageRepository.tessdataDir.parent!!
            val newTess = TessBaseAPI()
            if (!newTess.init(dataPath, languageString)) {
                newTess.recycle()
                return
            }
            // Reduce hallucinations on nearly-blank pages:
            // - tessedit_do_invert=0: don't look for white-on-black text (our
            //   binarization is always black-on-white; inverting would double noise).
            // - textord_heavy_nr=1: aggressive noise suppression in line finding.
            // - PSM_SINGLE_BLOCK by default: a scanned page is a uniform block;
            //   AUTO tries to segment logos/graphics as text.
            newTess.setVariable("tessedit_do_invert", "0")
            newTess.setVariable("textord_heavy_nr", "1")
            newTess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            tess = newTess
        }
    }

    /**
     * Runs OCR over [bitmap] and returns the detected word boxes.
     *
     * Backward compatible: the two new parameters have defaults,
     * so `runOcr(bmp)` and `runOcr(bmp, psm)` still compile.
     *
     * @param pageSegMode Tesseract mode. When null, PSM_SINGLE_BLOCK is used
     * (a scanned page is a uniform block; AUTO hallucinates on blanks/logos).
     * @param minWordConf minimum per-word confidence (0-100). 65 is a good
     * balance: >50 let texture/shadows through, >=75 drops faint receipts.
     * @param skipBlankCheck set true only when the caller already filtered
     * blanks or the image is a small crop where the ink check does not apply.
     */
    suspend fun runOcr(
        bitmap: Bitmap,
        pageSegMode: Int? = null,
        minWordConf: Int = 65,
        skipBlankCheck: Boolean = false,
    ): List<OcrTextBox> {
        if (bitmap.isRecycled) return listOf()
        // Cheap gate (~5ms on a 160px thumbnail) before Tesseract (2-4s).
        // Keeps blank sheets out of the engine, where hallucinations happen.
        if (!skipBlankCheck && isEffectivelyBlank(bitmap)) return listOf()
        mutex.withLock {
            val tess = this.tess ?: return listOf()
            // Normalize to ~200dpi (long side 2200px): x-height 30-45px as
            // Tesseract expects. A fixed 1.2MP left text too small on 12MP
            // photos and never upscaled thumbnails.
            val (ocrBitmap, scaleBack) = prepareBitmapForOcr(bitmap)
            if (ocrBitmap.isRecycled) return listOf()
            val needsRecycle = ocrBitmap !== bitmap
            val effectivePsm = pageSegMode ?: TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            tess.pageSegMode = effectivePsm
            try {
                val textBoxes = mutableListOf<OcrTextBox>()
                tess.setImage(ocrBitmap)
                tess.utF8Text // Trigger text recognition
                // Whole-page gate: when even the average is unreadable, all is noise.
                // (tesseract4android 4.9.0: meanConfidence(), not meanTextConf).
                if (tess.meanConfidence() < 55) return listOf()
                val minWordHeightPx = ocrBitmap.height * 0.005f
                val iterator = tess.resultIterator
                iterator.begin()
                do {
                    val raw = iterator.getUTF8Text(PageIteratorLevel.RIL_WORD) ?: continue
                    val word = raw.trim()
                    if (word.isEmpty()) continue
                    val confidence = iterator.confidence(PageIteratorLevel.RIL_WORD)
                    if (confidence < minWordConf) continue
                    // Short stubs ("a", "12", ".,") are the most common margin
                    // hallucinations; genuine short words score well above 75.
                    if (confidence < 75 && word.length <= 3) continue
                    if (!isPlausibleWord(word)) continue
                    val wordBox = iterator.getBoundingRect(PageIteratorLevel.RIL_WORD)
                    // Degenerate boxes (a 2-3px speck reported as a word).
                    if (wordBox.width() < 8 || wordBox.height() < 12) continue
                    // Ruling lines / underline fragments: extremely wide and flat,
                    // often read as "___" or "----" with passing confidence.
                    if (wordBox.width() > wordBox.height() * 15) continue
                    if (wordBox.height() < minWordHeightPx) continue
                    // Defensive clamp: the iterator sometimes returns rects outside the bitmap.
                    if (wordBox.left < 0 || wordBox.top < 0) continue
                    val lineBox = iterator.getBoundingRect(PageIteratorLevel.RIL_TEXTLINE)
                    val rect = if (scaleBack != 1f) wordBox.scaled(scaleBack).toImageRect()
                    else wordBox.toImageRect()
                    val lineHeight =
                        if (scaleBack != 1f) (lineBox.height() * scaleBack).toInt() else lineBox.height()
                    val lineBottom =
                        if (scaleBack != 1f) (lineBox.bottom * scaleBack).toInt() else lineBox.bottom
                    textBoxes.add(OcrTextBox(word, rect, lineHeight, lineBottom))
                } while (iterator.next(PageIteratorLevel.RIL_WORD))
                iterator.delete()
                // Final blank guard: 1-2 short tokens with a poor mean is the
                // typical "empty sheet with 1 hallucination" pattern.
                if (textBoxes.size <= 2 &&
                    textBoxes.all { it.text.length <= 3 } &&
                    tess.meanConfidence() < 65
                ) {
                    return listOf()
                }
                return textBoxes
            } finally {
                // Restore SINGLE_BLOCK (our default), not AUTO, so batch
                // callers (PDF/DOCX) stay deterministic.
                tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
                if (needsRecycle && !ocrBitmap.isRecycled) ocrBitmap.recycle()
            }
        }
    }

    /**
     * Shared binarization for writers (Option A): same `enhanceBwImage()` used
     * by [nopalito.app.domain.ocr.OcrTextDetector], exposed here so PDF/DOCX
     * don't duplicate OpenCV code. Returns a new bitmap; the caller must
     * recycle it when !== input. Falls back to the original when OpenCV fails
     * (not initialized).
     */
    internal fun preprocessForOcr(source: Bitmap): Bitmap {
        if (source.isRecycled) return source
        return runCatching {
            val rgba = Mat()
            Utils.bitmapToMat(source, rgba)
            val bgr = Mat()
            val out = Mat()
            try {
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                val thresholded = enhanceBwImage(bgr)
                try {
                    Imgproc.cvtColor(thresholded, out, Imgproc.COLOR_BGR2RGBA)
                } finally {
                    thresholded.release()
                }
                val result = createBitmap(out.cols(), out.rows())
                Utils.matToBitmap(out, result)
                result
            } finally {
                rgba.release()
                bgr.release()
                out.release()
            }
        }.getOrNull() ?: source
    }

    /**
     * Normalizes resolution by DPI, not by fixed MP.
     * Long-side target ~2200px (≈200dpi on A4 → x-height 30-45px).
     * Returns (bitmapToUse, inverseScale) to remap boxes to the original.
     */
    private fun prepareBitmapForOcr(bitmap: Bitmap): Pair<Bitmap, Float> {
        val targetLong = 2200f
        val minLong = 1500f
        val maxPixels = 2_600_000.0
        val longSide = max(bitmap.width, bitmap.height).toFloat().coerceAtLeast(1f)
        var scale = 1f
        when {
            longSide > targetLong -> scale = targetLong / longSide
            longSide < minLong -> scale = (minLong / longSide).coerceAtMost(2f)
        }
        // Narrow crops (tall receipts): the long side can pass while the short
        // side stays tiny and x-height unreadable. Floor it as well; the
        // maxPixels guard below still caps total cost.
        val shortSide = minOf(bitmap.width, bitmap.height).toFloat().coerceAtLeast(1f)
        scale = max(scale, (900f / shortSide).coerceAtMost(2f))
        // Safety cap in MP (low-RAM devices).
        val scaledPixels = bitmap.width.toDouble() * bitmap.height.toDouble() * scale * scale
        if (scaledPixels > maxPixels) {
            scale *= sqrt(maxPixels / scaledPixels).toFloat()
        }
        val needsConvert = bitmap.config != Bitmap.Config.ARGB_8888
        if (scale == 1f && !needsConvert) return bitmap to 1f
        val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        // Bilinear (filter=true): preserves thin strokes; nearest breaks them.
        val scaled = bitmap.scale(newW, newH)
        val argb = if (scaled.config != Bitmap.Config.ARGB_8888) {
            val c = scaled.copy(Bitmap.Config.ARGB_8888, false) ?: scaled
            if (c !== scaled && !scaled.isRecycled) scaled.recycle()
            c
        } else scaled
        return argb to (1f / scale)
    }

    /**
     * Lexical anti-hallucination filter. Requires a letter/digit and rejects
     * repetitions ("iiiiii", "11111") and texture garbage ("-_-_-", "|:|").
     */
    private fun isPlausibleWord(s: String): Boolean {
        val t = s.trim()
        if (t.length < 2) return false
        if (!t.any { it.isLetterOrDigit() }) return false
        val alnum = t.count { it.isLetterOrDigit() }
        // <50% alphanumeric → noise ("a—", "1.·.").
        if (alnum.toFloat() / t.length < 0.5f) return false
        // 4+ chars with a single distinct char → texture hallucination.
        if (t.length >= 4 && t.toSet().size <= 1) return false
        return true
    }

    /**
     * Detects a nearly-blank page in ~5ms without OpenCV: 160px thumbnail +
     * ink ratio + luminance mean/variance.
     */
    private fun isEffectivelyBlank(src: Bitmap): Boolean {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return true
        val w = 160
        val h = (160f * src.height / src.width).toInt().coerceIn(1, 160)
        val thumb = runCatching {
            src.scale(w, h)
        }.getOrNull() ?: return false
        try {
            if (thumb.isRecycled) return false
            val px = IntArray(w * h)
            thumb.getPixels(px, 0, w, 0, 0, w, h)
            var dark = 0
            var sum = 0L
            var sum2 = 0L
            for (c in px) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // Rec.601 luma; threshold 140 separates paper (>200 after BW)
                // from ink/shadow (<100).
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                sum += lum
                sum2 += lum.toLong() * lum
                if (lum < 140) dark++
            }
            val n = px.size.toDouble().coerceAtLeast(1.0)
            val mean = sum / n
            val variance = sum2 / n - mean * mean
            val inkRatio = dark / n
            // Nearly zero ink: blank even with paper texture grain.
            if (inkRatio < 0.0015) return true
            // Empty paper: almost no ink AND flat (no text edges). The variance
            // cap is raised for shadowed blanks, which are never perfectly flat.
            if (inkRatio < 0.003 && variance < 1400) return true
            // Washed-out/burned photo: near-white mean and almost no dark pixels.
            if (mean > 242 && inkRatio < 0.01) return true
            return false
        } catch (_: Exception) {
            return false // When in doubt, run OCR (never drop real text)
        } finally {
            if (!thumb.isRecycled) thumb.recycle()
        }
    }

    private fun Rect.scaled(scale: Float): Rect = Rect(
        (left * scale).toInt(),
        (top * scale).toInt(),
        (right * scale).toInt(),
        (bottom * scale).toInt(),
    )

    private fun Rect.toImageRect(): ImageRect = ImageRect(left, top, right, bottom)
}
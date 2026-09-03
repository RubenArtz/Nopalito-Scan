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
            tess = newTess
        }
    }

    suspend fun runOcr(bitmap: Bitmap): List<OcrTextBox> {
        mutex.withLock {
            val tess = this.tess ?: return listOf()
            // Downscale large bitmaps for OCR: Tesseract on 2MP+ takes 30-60s,
            // on ~1MP takes 2-4s with negligible accuracy loss for printed text.
            // Previous path fed full-res BALANCED/HIGH images causing ~4 min per page.
            val (ocrBitmap, scale) = prepareBitmapForOcr(bitmap)
            val needsRecycle = ocrBitmap !== bitmap
            try {
                val textBoxes = mutableListOf<OcrTextBox>()
                tess.setImage(ocrBitmap)
                tess.getUTF8Text() // Trigger text recognition
                val iterator = tess.resultIterator
                iterator.begin()
                do {
                    val word = iterator.getUTF8Text(PageIteratorLevel.RIL_WORD) ?: continue
                    val wordBox = iterator.getBoundingRect(PageIteratorLevel.RIL_WORD)
                    val lineBox = iterator.getBoundingRect(PageIteratorLevel.RIL_TEXTLINE)
                    val confidence = iterator.confidence(PageIteratorLevel.RIL_WORD)
                    if (confidence > 50) {
                        val rect = if (scale != 1f) wordBox.scaled(scale).toImageRect()
                        else wordBox.toImageRect()
                        val lineHeight =
                            if (scale != 1f) (lineBox.height() * scale).toInt() else lineBox.height()
                        val lineBottom =
                            if (scale != 1f) (lineBox.bottom * scale).toInt() else lineBox.bottom
                        textBoxes.add(OcrTextBox(word, rect, lineHeight, lineBottom))
                    }
                } while (iterator.next(PageIteratorLevel.RIL_WORD))
                iterator.delete()
                return textBoxes
            } finally {
                if (needsRecycle) ocrBitmap.recycle()
            }
        }
    }

    /**
     * Scales bitmap down to ~1.2 MP if larger. Returns pair (bitmapToUse, scaleBack).
     * scaleBack is multiplier to map OCR coordinates back to original size.
     */
    private fun prepareBitmapForOcr(bitmap: Bitmap): Pair<Bitmap, Float> {
        val maxPixels = 1_200_000
        val pixels = bitmap.width * bitmap.height
        if (pixels <= maxPixels) return bitmap to 1f
        val scale = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble()).toFloat()
        val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = bitmap.scale(newW, newH)
        val scaleBack = 1f / scale
        return scaled to scaleBack
    }

    private fun Rect.scaled(scale: Float): Rect = Rect(
        (left * scale).toInt(),
        (top * scale).toInt(),
        (right * scale).toInt(),
        (bottom * scale).toInt(),
    )

    private fun Rect.toImageRect(): ImageRect = ImageRect(left, top, right, bottom)
}

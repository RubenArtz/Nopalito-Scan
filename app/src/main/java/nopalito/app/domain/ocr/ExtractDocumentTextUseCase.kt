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

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case: detect text in a page bitmap and extract it as selectable plain
 * text. Runs the engine off the main thread and maps every outcome to a
 * [TextExtractionState] the UI can render directly.
 */
class ExtractDocumentTextUseCase(
    private val detector: OcrTextDetector,
    private val extractor: OcrTextExtractor = OcrTextExtractor(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend operator fun invoke(bitmap: Bitmap): TextExtractionState =
        withContext(dispatcher) {
            try {
                val boxes = detector.detect(bitmap)
                if (boxes.isEmpty()) {
                    TextExtractionState.NoText
                } else {
                    val document = extractor.extract(boxes, bitmap.height)
                    if (document.fullText.isBlank()) {
                        TextExtractionState.NoText
                    } else {
                        TextExtractionState.Success(document)
                    }
                }
            } catch (e: Exception) {
                TextExtractionState.Error(e.message)
            }
        }
}
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

/** UI state of the extract-text flow in the document editor. */
sealed interface TextExtractionState {

    /** Nothing running, sheet hidden. */
    data object Idle : TextExtractionState

    /** OCR running; the scanning animation is shown. */
    data object Scanning : TextExtractionState

    /** Text extracted; the selection sheet is shown. */
    data class Success(val document: ExtractedDocument) : TextExtractionState

    /** OCR finished but no words were detected. */
    data object NoText : TextExtractionState

    /**
     * OCR failed. [debugMessage] is English free text for logs only; the UI
     * shows a localized generic message.
     */
    data class Error(val debugMessage: String? = null) : TextExtractionState
}
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

package nopalito.app.ui.screens.tools.core

import kotlinx.coroutines.flow.MutableStateFlow
import nopalito.app.ui.screens.tools.BatchMode
import nopalito.app.ui.screens.tools.CompressTool
import nopalito.app.ui.screens.tools.PickedFile

/**
 * Bridge between tool features (e.g. "Protect with password" → Compressor).
 *
 * Keeps navigation decoupled: the source screen only emits a [Request] with
 * minimal data (no heavy objects), and the destination feature consumes it
 * from its own ViewModel. No URIs are passed through navigation routes.
 */
class ToolTransfer {

    data class Request(
        val tool: CompressTool,
        val batchMode: BatchMode,
        val files: List<PickedFile>,
        /** Password typed on the source screen (prefill). */
        val password: String,
    )

    private val _pending = MutableStateFlow<Request?>(null)

    /** Publishes a transfer request (consumed only once). */
    fun request(request: Request) {
        _pending.value = request
    }

    /** Reads and clears the pending request; returns null when there is none. */
    fun consume(): Request? = _pending.value?.also { _pending.value = null }
}
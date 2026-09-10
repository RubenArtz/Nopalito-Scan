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

package nopalito.app.share

import android.net.Uri

// Bridges the gap between MainActivity.onCreate/onNewIntent and CameraViewModel
// readiness: the shared URIs are staged here until they can be consumed.
object PendingSharedImport {
    @Volatile
    private var pending: List<Uri>? = null

    fun offer(uris: List<Uri>) {
        if (uris.isNotEmpty()) pending = uris
    }

    fun take(): List<Uri>? {
        val current = pending
        pending = null
        return current?.takeIf { it.isNotEmpty() }
    }
}
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

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

object ShareDocument {

    fun createSendIntent(uris: List<Uri>, mimeType: String): Intent? {
        if (uris.isEmpty()) return null
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(null, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(null, uris[0])
            }
        }
    }

    // Returns false when there is nothing to share or no app handles it, so the
    // caller owns user feedback with its localized strings.
    fun shareUris(
        context: Context,
        uris: List<Uri>,
        mimeType: String,
        chooserTitle: CharSequence
    ): Boolean {
        val intent = createSendIntent(uris, mimeType) ?: return false
        return try {
            context.startActivity(Intent.createChooser(intent, chooserTitle))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    // Files must live under a FileProvider root declared in file_paths.xml; the
    // content URI carries a read-only grant scoped to the receiving app.
    fun shareFiles(
        context: Context,
        files: List<File>,
        mimeType: String,
        chooserTitle: CharSequence
    ): Boolean {
        if (files.isEmpty()) return false
        val uris = files.map { nopalito.app.ui.uriForFile(context, it) }
        return shareUris(context, uris, mimeType, chooserTitle)
    }
}
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

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object SharedImportCache {

    // Shared URIs are short-lived grants: resolve them through ContentResolver,
    // never through File(uri.path).
    suspend fun copyToCache(context: Context, source: Uri, prefix: String = "shared"): File =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val extension = sanitizeExtension(queryExtension(context, source))
            val target = File(
                context.cacheDir,
                "${prefix}_${System.currentTimeMillis()}$extension",
            )
            resolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Cannot open shared uri: $source")
            target
        }

    private fun queryExtension(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val name = cursor.getString(index).orEmpty()
                    val dot = name.lastIndexOf('.')
                    if (dot >= 0 && dot < name.length - 1) return name.substring(dot)
                }
            }
        }
        return ""
    }

    private fun sanitizeExtension(raw: String): String {
        if (raw.isEmpty() || raw.length > 12) return ""
        return raw.filter { it.isLetterOrDigit() || it == '.' }.takeIf { it.startsWith('.') } ?: ""
    }
}
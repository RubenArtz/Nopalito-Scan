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

package nopalito.app.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.first
import nopalito.app.NopalitoApp
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Shared download destination: files saved by the app (cloud downloads, QR
 * exports, export-history re-downloads) land in the user-chosen folder when one
 * is configured (SAF tree URI, persisted permission), otherwise they fall back
 * to the public Downloads/Nopalito Scan folder via MediaStore.
 */
object DownloadLocation {

    /** The user-chosen download folder (SAF tree URI), or null when unset. */
    suspend fun configuredUri(context: Context): Uri? {
        val app = context.applicationContext as? NopalitoApp ?: return null
        return app.appContainer.settingsRepository.downloadDirUri.first()?.toUri()
    }

    /** Saves [bytes] to the configured folder or MediaStore fallback. */
    suspend fun saveBytes(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
    ): Uri? {
        val treeUri = configuredUri(context)
        return if (treeUri != null) {
            saveToTree(context, treeUri, displayName, mimeType) { out -> out.write(bytes) }
        } else {
            saveToMediaStore(context, displayName, mimeType) { out -> out.write(bytes) }
        }
    }

    /**
     * Copies the byte stream from [openInput] into the download destination,
     * reporting progress 0..1 through [onProgress] (throttled to 1% steps).
     */
    suspend fun saveStream(
        context: Context,
        displayName: String,
        mimeType: String,
        totalBytes: Long,
        openInput: () -> InputStream,
        onProgress: ((Float) -> Unit)? = null,
    ): Uri? {
        val treeUri = configuredUri(context)
        return if (treeUri != null) {
            saveToTree(context, treeUri, displayName, mimeType) { out ->
                copyWithProgress(openInput(), out, totalBytes, onProgress)
            }
        } else {
            saveToMediaStore(context, displayName, mimeType) { out ->
                copyWithProgress(openInput(), out, totalBytes, onProgress)
            }
        }
    }

    private fun saveToTree(
        context: Context,
        treeUri: Uri,
        displayName: String,
        mimeType: String,
        write: (OutputStream) -> Unit,
    ): Uri? {
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }
        if (!hasPermission) return null
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val target = tree.createFile(mimeType, displayName) ?: return null
        return runCatching {
            context.contentResolver.openOutputStream(target.uri)?.use { write(it) } ?: return null
            target.uri
        }.getOrNull()
    }

    private fun saveToMediaStore(
        context: Context,
        displayName: String,
        mimeType: String,
        write: (OutputStream) -> Unit,
    ): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Nopalito Scan"
                )
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: return@runCatching null
            context.contentResolver.openOutputStream(uri)?.use { write(it) }
                ?: return@runCatching null
            uri
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS + "/Nopalito Scan"
            )
            dir.mkdirs()
            val file = File(dir, displayName)
            file.outputStream().use { write(it) }
            @Suppress("DEPRECATION")
            Uri.fromFile(file)
        }
    }.getOrNull()

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        total: Long,
        onProgress: ((Float) -> Unit)?,
    ) {
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        var lastPercent = -1
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            read += n
            if (total > 0 && onProgress != null) {
                val percent = ((read * 100) / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(read.toFloat() / total)
                }
            }
        }
        input.close()
    }
}
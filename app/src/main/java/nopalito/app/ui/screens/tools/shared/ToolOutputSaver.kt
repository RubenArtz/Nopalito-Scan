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

package nopalito.app.ui.screens.tools.shared

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import nopalito.app.ui.screens.tools.querySizeBytes
import java.io.File
import java.io.FileOutputStream

/**
 * Writes tool output files to the configured destination: SAF folder
 * (content://) chosen in Settings, a temporary batch folder, or Downloads
 * by default.
 *
 * Follows the same save pattern as the compressor (ToolsViewModel): when the
 * destination is a tree URI the file is written via [DocumentFile], otherwise
 * MediaStore (Android 10+) or the local path (Android 8/9) is used.
 *
 * Technical debt note: ToolsViewModel keeps a private copy of this logic.
 * This component is the source of truth for new tools; migrating the
 * compressor to this component is a future step with no behavioral change.
 */
class ToolOutputSaver(private val context: Context) {

    sealed class SaveRef {
        abstract fun length(context: Context): Long

        class FileRef(val file: File) : SaveRef() {
            override fun length(context: Context): Long = file.length()
        }

        class UriRef(val uri: Uri) : SaveRef() {
            override fun length(context: Context): Long = querySizeBytes(context, uri)
        }
    }

    /**
     * Writes [source] as [fileName] into [batchUri] (when processing a batch),
     * or into the destination [saveLocationUri] (tree URI from Settings), or
     * into Downloads as a fallback.
     */
    fun save(
        fileName: String,
        source: File,
        saveLocationUri: String?,
        batchUri: Uri? = null,
    ): SaveRef {
        if (batchUri != null) {
            return if (batchUri.scheme == ContentResolver.SCHEME_FILE) {
                writeIntoLocal(File(batchUri.path!!), fileName, source)
            } else {
                val folder = DocumentFile.fromTreeUri(context, batchUri)
                    ?: throw IllegalStateException("Cannot open batch folder")
                writeIntoTree(folder, fileName, source)
            }
        }
        if (saveLocationUri != null) {
            val tree = DocumentFile.fromTreeUri(context, saveLocationUri.toUri())
                ?: throw IllegalStateException("Cannot open save folder")
            return writeIntoTree(tree, fileName, source)
        }
        return saveIntoDownloads(fileName, source)
    }

    private fun saveIntoDownloads(fileName: String, source: File): SaveRef {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(fileName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri =
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Cannot create Downloads entry")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Cannot write to Downloads")
            return SaveRef.UriRef(uri)
        }
        return writeIntoLocal(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName,
            source,
        )
    }

    private fun writeIntoTree(folder: DocumentFile, fileName: String, source: File): SaveRef {
        val existing = folder.findFile(fileName)
        val child = existing ?: folder.createFile(mimeTypeFor(fileName), fileName)
        ?: throw IllegalStateException("Cannot create file in folder")
        val stream = context.contentResolver.openOutputStream(child.uri)
            ?: throw IllegalStateException("Cannot write to folder")
        stream.use { out -> source.inputStream().use { ins -> ins.copyTo(out) } }
        return SaveRef.UriRef(child.uri)
    }

    private fun writeIntoLocal(dir: File, fileName: String, source: File): SaveRef {
        dir.mkdirs()
        var name = fileName
        var counter = 2
        var file = File(dir, name)
        while (file.exists() && !file.delete()) {
            name = uniqueName(fileName, counter++)
            file = File(dir, name)
        }
        FileOutputStream(file).use { out -> source.inputStream().use { ins -> ins.copyTo(out) } }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        return SaveRef.FileRef(file)
    }

    private fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            else -> "application/octet-stream"
        }

    private fun uniqueName(name: String, counter: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)}_$counter${name.substring(dot)}"
        else "${name}_$counter"
    }
}
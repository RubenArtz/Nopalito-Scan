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
package nopalito.app.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import nopalito.app.data.publishTempFile
import nopalito.app.data.syncBestEffort
import nopalito.app.domain.ProcessedExportPage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Format encoders whose only accepted input is a finalized processed page. */
object ProcessedImageFileExporter {
    fun writeJpeg(page: ProcessedExportPage, target: File) {
        writeBytesAtomically(target, page.readJpeg().bytes)
    }

    fun writePng(page: ProcessedExportPage, target: File) {
        val jpeg = page.readJpeg()
        val bitmap = BitmapFactory.decodeByteArray(jpeg.bytes, 0, jpeg.bytes.size)
            ?: throw IOException("Failed to decode processed artifact for ${page.pageId}")
        try {
            writeBitmapAtomically(target, bitmap, Bitmap.CompressFormat.PNG, 100)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun writeBytesAtomically(target: File, bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Cannot write an empty export" }
        atomicTarget(target) { output -> output.write(bytes) }
    }

    private fun writeBitmapAtomically(
        target: File,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
    ) {
        atomicTarget(target) { output ->
            check(bitmap.compress(format, quality, output)) { "Failed to encode ${target.name}" }
        }
    }

    private fun atomicTarget(target: File, writer: (FileOutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val temp = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
        try {
            FileOutputStream(temp).use { output ->
                writer(output)
                output.syncBestEffort(target.name)
            }
            if (temp.length() <= 0L) throw IOException("Empty encoded export ${target.name}")
            publishTempFile(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}

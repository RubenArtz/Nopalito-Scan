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

package nopalito.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nopalito.app.data.stats.StatsRepository
import nopalito.app.domain.PageToExport
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

data class GeneratedPdf(
    val file: File,
    val sizeInBytes: Long,
    val pageCount: Int,
)

data class GeneratedDocx(
    val file: File,
    val sizeInBytes: Long,
    val pageCount: Int,
)

fun interface PdfWriter {
    suspend fun writePdfFromJpegs(
        pages: List<PageToExport>,
        outputStream: OutputStream,
        disableOcr: Boolean,
        password: String?,
        onProgress: (Int) -> Unit,
    )
}

fun interface DocxWriter {
    suspend fun writeDocxFromJpegs(
        pages: List<PageToExport>,
        outputStream: OutputStream,
        disableOcr: Boolean,
        password: String?,
        onProgress: (Int) -> Unit,
    )
}

class FileManager(
    private val pdfDir: File,
    private val externalDir: File,
    private val pdfWriter: PdfWriter,
    private val docxWriter: DocxWriter,
    private val statsRepository: StatsRepository? = null,
    private val statsScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    companion object {
        fun addPdfExtensionIfMissing(fileName: String): String {
            return if (fileName.lowercase().endsWith(".pdf"))
                fileName
            else
                "$fileName.pdf"
        }

        fun addDocxExtensionIfMissing(fileName: String): String {
            return if (fileName.lowercase().endsWith(".docx"))
                fileName
            else
                "$fileName.docx"
        }
    }

    suspend fun generatePdf(
        pages: List<PageToExport>,
        disableOcr: Boolean,
        password: String? = null,
        onProgress: (Int) -> Unit
    ): GeneratedPdf {
        pdfDir.mkdirs()
        require(pdfDir.exists() && pdfDir.isDirectory) { "Invalid pdfDir: $pdfDir" }
        val file = File(pdfDir, "${System.currentTimeMillis()}.pdf")
        withContext(Dispatchers.IO) {
            FileOutputStream(file).use {
                pdfWriter.writePdfFromJpegs(pages, it, disableOcr, password, onProgress)
            }
        }
        val sizeBytes = file.length()
        logScanCreatedAsync(pages.size, sizeBytes, disableOcr)
        return GeneratedPdf(file, sizeBytes, pages.size)
    }

    suspend fun generateDocx(
        pages: List<PageToExport>,
        disableOcr: Boolean,
        password: String? = null,
        onProgress: (Int) -> Unit
    ): GeneratedDocx {
        pdfDir.mkdirs()
        require(pdfDir.exists() && pdfDir.isDirectory) { "Invalid pdfDir: $pdfDir" }
        val file = File(pdfDir, "${System.currentTimeMillis()}.docx")
        withContext(Dispatchers.IO) {
            FileOutputStream(file).use {
                docxWriter.writeDocxFromJpegs(pages, it, disableOcr, password, onProgress)
            }
        }
        val sizeBytes = file.length()
        logScanCreatedAsync(pages.size, sizeBytes, disableOcr)
        return GeneratedDocx(file, sizeBytes, pages.size)
    }

    private fun logScanCreatedAsync(pages: Int, sizeBytes: Long, disableOcr: Boolean) {
        statsScope.launch {
            runCatching {
                statsRepository?.logScanCreated(
                    type = "document",
                    pages = pages,
                    sizeKb = sizeBytes / 1024,
                    captureDurationMs = 0L,
                    hasOcr = !disableOcr,
                    filter = "none"
                )
            }
        }
    }

    fun copyToExternalDir(original: File): File {
        if (!externalDir.exists()) {
            externalDir.mkdirs()
        }
        require(externalDir.exists() && externalDir.isDirectory) { "Invalid externalDir: $pdfDir" }
        val desiredFile = File(externalDir, original.name)
        val targetFile = getAvailableFilename(desiredFile)
        original.copyTo(targetFile)
        return targetFile
    }

    private fun getAvailableFilename(desiredFile: File): File {
        var file = desiredFile
        val dir = desiredFile.parentFile
        val nameWithoutExtension = desiredFile.nameWithoutExtension
        val extension = desiredFile.extension
        var counter = 1
        while (file.exists()) {
            file = File(dir, "${nameWithoutExtension}($counter).$extension")
            counter++
        }
        return file
    }

    fun cleanUpOldFiles(thresholdInMillis: Int) {
        val now = System.currentTimeMillis()
        pdfDir.listFiles { file -> now - file.lastModified() > thresholdInMillis }
            ?.forEach { file -> file.delete() }
    }
}

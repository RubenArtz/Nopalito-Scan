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

import android.graphics.BitmapFactory
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Debug-only exporter for an immutable SourceOriginal corpus fixture. */
class CurvatureCorpusExporter(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    data class Result(val pageCount: Int, val totalBytes: Long, val manifest: File)

    fun export(scanRoot: File, outputRoot: File): Result {
        val sourceRoot = File(scanRoot, OriginalStore.ORIGINAL_DIR).canonicalFile
        val metadataFile = File(File(scanRoot, PROCESSED_DIR_NAME), "document.json").canonicalFile
        require(metadataFile.isFile) { "Document metadata is unavailable." }
        val out = outputRoot.canonicalFile
        require(
            !out.toPath().startsWith(scanRoot.canonicalFile.toPath())
        ) { "Export directory must be outside production storage." }
        require(out.name == "curvature-fixtures") { "Export directory must be named curvature-fixtures." }
        val originalsOut = File(out, "originals")
        out.mkdirs(); originalsOut.mkdirs()
        val metadata = json.decodeFromString<DocumentMetadataV2>(metadataFile.readText())
        require(metadata.pages.isNotEmpty()) { "The current document has no pages." }
        var total = 0L
        val exportedPages = metadata.pages.map { page ->
            val relative = page.sourceFile ?: "originals/${page.id}.jpg"
            require(relative == "originals/${page.id}.jpg") { "Invalid SourceOriginal path for ${page.id}." }
            val source = File(scanRoot, relative).canonicalFile
            require(source.toPath().startsWith(sourceRoot.toPath()) && source.isFile) {
                "No valid SourceOriginal for ${page.id}."
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val decoded = runCatching { BitmapFactory.decodeFile(source.path, bounds) }.isSuccess
            if ((!decoded || bounds.outWidth <= 0 || bounds.outHeight <= 0) &&
                (page.sourceWidth ?: 0) > 0 && (page.sourceHeight ?: 0) > 0
            ) {
                bounds.outWidth = page.sourceWidth!!
                bounds.outHeight = page.sourceHeight!!
            }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "SourceOriginal is not a valid JPEG for ${page.id}." }
            val destination = File(originalsOut, "${page.id}.jpg")
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            require(sha256(source) == sha256(destination)) { "SourceOriginal hash changed for ${page.id}." }
            total += destination.length()
            page.copy(
                sourceFile = "originals/${page.id}.jpg",
                sourceFileSize = destination.length(),
                sourceSha256 = sha256(destination),
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                capturedWidth = page.capturedWidth ?: bounds.outWidth,
                capturedHeight = page.capturedHeight ?: bounds.outHeight,
                hasOriginal = true,
            )
        }
        val manifest = File(out, "document.json")
        manifest.writeText(json.encodeToString(metadata.copy(pages = exportedPages)))
        return Result(exportedPages.size, total, manifest)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

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

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class CurvatureCorpusExporterTest {
    private val json = Json { encodeDefaults = true }
    private val jpeg: ByteArray by lazy {
        val image = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "jpg", bytes)
        bytes.toByteArray()
    }

    @Test
    fun sevenOriginalsExportedAndLayoutCreated() {
        val root = Files.createTempDirectory("scan-root").toFile();
        val out = File(root.parentFile, "curvature-fixtures")
        val originals = File(root, "originals").apply { mkdirs() };
        val pages = (1..7).map {
            PageV2(
                id = it.toString(),
                sourceFile = "originals/$it.jpg",
                hasOriginal = true,
                sourceWidth = 2,
                sourceHeight = 2
            )
        }
        File(root, "scanned_pages").mkdirs(); File(root, "scanned_pages/document.json").writeText(
            json.encodeToString(DocumentMetadataV2(pages = pages))
        )
        pages.forEach { File(originals, "${it.id}.jpg").writeBytes(jpeg) }
        val result = CurvatureCorpusExporter().export(root, out)
        assertEquals(7, result.pageCount); assertTrue(
            File(
                out,
                "document.json"
            ).isFile
        ); assertEquals(7, File(out, "originals").listFiles()!!.size)
    }

    @Test
    fun missingOriginalAndInvalidPathAreRejectedWithoutFallback() {
        val root = Files.createTempDirectory("scan-root").toFile(); File(
            root,
            "scanned_pages"
        ).mkdirs(); File(root, "originals").mkdirs()
        val page = PageV2(id = "1", sourceFile = "scanned_pages/1.jpg", hasOriginal = false)
        File(root, "scanned_pages/document.json").writeText(
            json.encodeToString(
                DocumentMetadataV2(
                    pages = listOf(page)
                )
            )
        )
        runCatching {
            CurvatureCorpusExporter().export(
                root,
                File(root.parentFile, "curvature-fixtures")
            )
        }.onSuccess { error("expected rejection") }
    }

    @Test
    fun sourceHashIsPreservedAndProductionRootIsRejected() {
        val root = Files.createTempDirectory("scan-root").toFile(); File(
            root,
            "scanned_pages"
        ).mkdirs();
        val originals = File(root, "originals").apply { mkdirs() }
        val page = PageV2(
            id = "1",
            sourceFile = "originals/1.jpg",
            hasOriginal = true,
            sourceWidth = 2,
            sourceHeight = 2
        ); File(root, "scanned_pages/document.json").writeText(
            json.encodeToString(
                DocumentMetadataV2(pages = listOf(page))
            )
        )
        File(originals, "1.jpg").writeBytes(jpeg);
        val out = File(root.parentFile, "curvature-fixtures");
        val before = sha(File(originals, "1.jpg")); CurvatureCorpusExporter().export(root, out)
        assertEquals(
            before,
            sha(File(out, "originals/1.jpg"))
        ); runCatching {
            CurvatureCorpusExporter().export(
                root,
                File(root, "curvature-fixtures")
            )
        }.onSuccess { error("expected production-root rejection") }
    }

    private fun sha(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}

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

import kotlinx.coroutines.test.runTest
import nopalito.app.domain.ExportQuality
import nopalito.app.domain.ExportSourceType
import nopalito.app.domain.ProcessedExportArtifactType
import nopalito.app.domain.ProcessedExportPage
import nopalito.imageprocessing.ColorMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class FileManagerProcessedArtifactTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `pdf writer receives only the materialized processed artifact`() = runTest {
        val root = temp.newFolder("exports")
        val processed = temp.newFile("processed.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val page = artifact(processed, temp.newFile("camera.jpg"))
        var received: List<ProcessedExportPage>? = null
        var receivedByDocx: List<ProcessedExportPage>? = null
        val manager = FileManager(
            pdfDir = root,
            externalDir = temp.newFolder("external"),
            pdfWriter = { pages, output, _, _, progress ->
                received = pages
                output.write("pdf".toByteArray())
                progress(pages.size)
            },
            docxWriter = { pages, output, _, _, _ ->
                receivedByDocx = pages
                output.write("docx".toByteArray())
            },
        )

        val generated = manager.generatePdf(listOf(page), disableOcr = true) {}

        assertThat(generated.file.readText()).isEqualTo("pdf")
        assertThat(received).containsExactly(page)
        val pdfPages = requireNotNull(received)
        assertThat(pdfPages.single().absolutePath).isEqualTo(processed.absolutePath)
        assertThat(pdfPages.single().absolutePath).isNotEqualTo(page.inputAbsolutePath)

        val docx = manager.generateDocx(listOf(page), disableOcr = true) {}
        assertThat(docx.file.readText()).isEqualTo("docx")
        assertThat(receivedByDocx).containsExactly(page)
    }

    @Test
    fun `writer failure leaves no partial final document`() = runTest {
        val root = temp.newFolder("failed-export")
        val processed = temp.newFile("ready.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val page = artifact(processed, temp.newFile("source.jpg"))
        val manager = FileManager(
            pdfDir = root,
            externalDir = temp.newFolder("failed-external"),
            pdfWriter = { _, output, _, _, _ ->
                output.write("partial".toByteArray())
                throw IOException("simulated writer failure")
            },
            docxWriter = { _, _, _, _, _ -> },
        )

        val failure = runCatching {
            manager.generatePdf(listOf(page), disableOcr = true) {}
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `writer closing its supplied stream does not abort atomic publication`() = runTest {
        val root = temp.newFolder("closing-writer")
        val processed = temp.newFile("closed-ready.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val page = artifact(processed, temp.newFile("closed-source.jpg"))
        val manager = FileManager(
            pdfDir = root,
            externalDir = temp.newFolder("closing-external"),
            pdfWriter = { _, output, _, _, _ ->
                output.write("pdf".toByteArray())
                output.close()
            },
            docxWriter = { _, _, _, _, _ -> },
        )

        val generated = manager.generatePdf(listOf(page), disableOcr = true) {}

        assertThat(generated.file.readText()).isEqualTo("pdf")
    }

    private fun artifact(processed: java.io.File, camera: java.io.File) = ProcessedExportPage(
        pageId = "page-1",
        file = processed,
        sourceType = ExportSourceType.CAMERA_ORIGINAL,
        artifactType = ProcessedExportArtifactType.PROCESSED_FULL_RES,
        inputAbsolutePath = camera.absolutePath,
        inputByteSize = 99,
        inputWidth = 100,
        inputHeight = 80,
        width = 60,
        height = 70,
        quad = emptyList(),
        quadVersion = 4,
        rotation = 90,
        colorMode = ColorMode.COLOR,
        requestedQuality = ExportQuality.ORIGINAL,
        byteSize = processed.length(),
        sha256 = "processed-sha",
    )
}

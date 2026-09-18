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

package nopalito.test.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import nopalito.app.NopalitoApp
import nopalito.app.data.ImageRepository
import nopalito.app.data.OriginalStore
import nopalito.app.domain.CaptureTier
import nopalito.app.domain.ExportPageOverlays
import nopalito.app.domain.ExportQuality
import nopalito.app.domain.ExportSourceType
import nopalito.app.domain.Jpeg
import nopalito.app.domain.PageMetadata
import nopalito.app.domain.Rotation
import nopalito.app.domain.prepareProcessedExportPages
import nopalito.app.platform.AndroidDocxWriter
import nopalito.app.platform.AndroidPdfWriter
import nopalito.app.platform.ImageProcessor
import nopalito.app.platform.ProcessedImageFileExporter
import nopalito.app.platform.processedImage
import nopalito.imageprocessing.ColorMode
import nopalito.imageprocessing.ImageSize
import nopalito.imageprocessing.Point
import nopalito.imageprocessing.Quad
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ProcessedExportRegressionInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun quadPersistsInvalidatesCacheAndFeedsJpegAndPngForEveryQuality() = runBlocking {
        assertTrue(OpenCVLoader.initLocal())
        val root =
            File(context.cacheDir, "processed-export-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val sourceBytes = asymmetricFixture()
            val sampled = Jpeg(sourceBytes).toMat(150_000)
            try {
                assertEquals(400, sampled.width())
                assertEquals(300, sampled.height())
            } finally {
                sampled.release()
            }
            val initialMetadata = PageMetadata(
                normalizedQuad = fullQuad(),
                baseRotation = Rotation.R0,
                autoColorMode = ColorMode.ORIGINAL,
                sourceSize = ImageSize(800, 600),
                opticalMeasures = null,
            )
            val initialProcessed = processedImage(
                Jpeg(sourceBytes), initialMetadata, Rotation.R0, ColorMode.ORIGINAL,
                ExportQuality.BALANCED,
            )
            val repository = ImageRepository(
                root,
                ImageProcessor(180),
                scope,
            ) { _, _, error -> throw AssertionError(error) }
            val captureTemp = OriginalStore.newTempFile(OriginalStore.originalsDir(root)).apply {
                writeBytes(sourceBytes)
            }
            val pageId = repository.addFileCapture(
                originalTemp = captureTemp,
                processed = initialProcessed,
                metadata = initialMetadata,
                colorMode = ColorMode.ORIGINAL,
                tier = CaptureTier.BALANCED,
                capturedWidth = 800,
                capturedHeight = 600,
                workingWidth = 800,
                workingHeight = 600,
                processedWidth = 800,
                processedHeight = 600,
                cameraId = "test",
                rotationDegrees = 0,
                exifOrientation = 1,
                captureMode = "test",
            )

            val quadA = Quad(
                Point(0.18, 0.12), Point(0.84, 0.08),
                Point(0.88, 0.88), Point(0.14, 0.82),
            )
            repository.setUserQuad(pageId, quadA)

            val artifactsA = listOf(
                ExportQuality.ORIGINAL,
                ExportQuality.BALANCED,
                ExportQuality.COMPRESSED,
            ).map { quality ->
                prepareProcessedExportPages(
                    repository, quality, File(root, "export-cache"), "TEST"
                ).single()
            }
            artifactsA.forEach { artifact ->
                assertTrue(artifact.file.exists())
                assertTrue(artifact.width > 0 && artifact.height > 0)
                assertFalse(sourceBytes.contentEquals(artifact.file.readBytes()))
                assertFalse(artifact.width == 800 && artifact.height == 600)
                assertEquals(1, artifact.quadVersion)
                assertEquals(4, artifact.quad.size)
            }
            assertEquals(ExportSourceType.CAMERA_ORIGINAL, artifactsA.first().sourceType)

            val pdf = File(root, "result.pdf")
            val app = context.applicationContext as NopalitoApp
            FileOutputStream(pdf).use { output ->
                AndroidPdfWriter(app.appContainer.ocrService, context.assets)
                    .writePdfFromProcessedPages(
                        listOf(artifactsA.first()), output, true, null
                    ) {}
            }
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(1, renderer.pageCount)
                    renderer.openPage(0).use { pdfPage ->
                        val rendered = Bitmap.createBitmap(
                            pdfPage.width, pdfPage.height, Bitmap.Config.ARGB_8888
                        )
                        rendered.eraseColor(Color.WHITE)
                        pdfPage.render(
                            rendered,
                            null,
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        val expected = BitmapFactory.decodeFile(artifactsA.first().absolutePath)
                        try {
                            val renderedRatio = rendered.width.toDouble() / rendered.height
                            val expectedRatio = expected.width.toDouble() / expected.height
                            assertTrue(kotlin.math.abs(renderedRatio - expectedRatio) < 0.03)
                            assertTrue(normalizedGridDistance(rendered, expected) < 110.0)
                        } finally {
                            rendered.recycle()
                            expected.recycle()
                        }
                    }
                }
            }

            val jpeg = File(root, "result.jpg")
            ProcessedImageFileExporter.writeJpeg(artifactsA.first(), jpeg)
            assertTrue(jpeg.readBytes().contentEquals(artifactsA.first().file.readBytes()))

            val png = File(root, "result.png")
            ProcessedImageFileExporter.writePng(artifactsA.first(), png)
            val pngBitmap = BitmapFactory.decodeFile(png.absolutePath)
            assertTrue(png.exists() && png.length() > 0L)
            assertEquals(artifactsA.first().width, pngBitmap.width)
            assertEquals(artifactsA.first().height, pngBitmap.height)
            val expectedPng = BitmapFactory.decodeFile(artifactsA.first().absolutePath)
            assertTrue(normalizedGridDistance(pngBitmap, expectedPng) < 2.0)
            expectedPng.recycle()
            pngBitmap.recycle()

            val docx = File(root, "result.docx")
            FileOutputStream(docx).use { output ->
                AndroidDocxWriter(app.appContainer.ocrService)
                    .writeDocxFromProcessedPages(
                        listOf(artifactsA.first()), output, true, null
                    ) {}
            }
            ZipFile(docx).use { zip ->
                val embedded =
                    zip.getInputStream(zip.getEntry("word/media/page1.jpg")).use { it.readBytes() }
                assertArrayEquals(artifactsA.first().file.readBytes(), embedded)
            }

            val signature = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.MAGENTA)
            }
            val withOverlay = try {
                prepareProcessedExportPages(
                    repository,
                    ExportQuality.BALANCED,
                    File(root, "overlay-cache"),
                    "WORD",
                    mapOf(
                        pageId to ExportPageOverlays(
                            signatureBitmap = signature,
                            signaturePositionFractionX = 0.5f,
                            signaturePositionFractionY = 0.5f,
                        )
                    ),
                ).single()
            } finally {
                signature.recycle()
            }
            assertNotEquals(artifactsA[1].sha256, withOverlay.sha256)

            repository.setColorMode(pageId, ColorMode.GRAYSCALE)
            repository.rotate(pageId, clockwise = true)
            val rotatedFiltered = prepareProcessedExportPages(
                repository, ExportQuality.ORIGINAL, File(root, "rotation-cache"), "PNG"
            ).single()
            assertTrue(rotatedFiltered.width < rotatedFiltered.height)
            assertEquals(90, rotatedFiltered.rotation)
            val rotatedBitmap = BitmapFactory.decodeFile(rotatedFiltered.absolutePath)
            try {
                val sample =
                    rotatedBitmap.getPixel(rotatedBitmap.width / 2, rotatedBitmap.height / 2)
                assertTrue(kotlin.math.abs(Color.red(sample) - Color.green(sample)) < 8)
                assertTrue(kotlin.math.abs(Color.green(sample) - Color.blue(sample)) < 8)
            } finally {
                rotatedBitmap.recycle()
            }

            val quadB = Quad(
                Point(0.28, 0.20), Point(0.74, 0.16),
                Point(0.79, 0.76), Point(0.24, 0.72),
            )
            repository.setUserQuad(pageId, quadB)
            val artifactB = prepareProcessedExportPages(
                repository, ExportQuality.ORIGINAL, File(root, "export-cache"), "JPEG"
            ).single()
            assertNotEquals(artifactsA.first().absolutePath, artifactB.absolutePath)
            assertNotEquals(artifactsA.first().sha256, artifactB.sha256)
            assertEquals(2, artifactB.quadVersion)

            repository.setIneSession(true)
            val reopened = ImageRepository(root, ImageProcessor(180), scope) { _, _, _ -> }
            assertTrue(reopened.isIneSession())
            val persisted = prepareProcessedExportPages(
                reopened, ExportQuality.ORIGINAL, File(root, "export-cache"), "PDF"
            ).single()
            assertEquals(artifactB.sha256, persisted.sha256)
            assertEquals(artifactB.quad, persisted.quad)

            repository.legacySourceFileForExport(pageId).writeBytes(sourceBytes)
            repository.originalFile(pageId).writeBytes(byteArrayOf(1, 2, 3, 4))
            val corruptFallback = prepareProcessedExportPages(
                repository, ExportQuality.ORIGINAL, File(root, "corrupt-cache"), "PDF"
            ).single()
            assertEquals(ExportSourceType.LEGACY_SOURCE, corruptFallback.sourceType)

            repository.originalFile(pageId).delete()
            repository.legacySourceFileForExport(pageId).delete()
            val fallback = prepareProcessedExportPages(
                repository, ExportQuality.ORIGINAL, File(root, "fallback-cache"), "WORD"
            ).single()
            assertEquals(ExportSourceType.STORED_PROCESSED, fallback.sourceType)
            assertFalse(sourceBytes.contentEquals(fallback.file.readBytes()))

            val secondProcessed = processedImage(
                Jpeg(sourceBytes), initialMetadata, Rotation.R0, ColorMode.ORIGINAL,
                ExportQuality.BALANCED,
            )
            repository.add(secondProcessed, Jpeg(sourceBytes), initialMetadata, ColorMode.ORIGINAL)
            val expectedOrder = repository.pages().map { it.id }
            val orderedArtifacts = prepareProcessedExportPages(
                repository, ExportQuality.BALANCED, File(root, "ordered-cache"), "WORD"
            )
            assertEquals(expectedOrder, orderedArtifacts.map { it.pageId })
            val orderedDocx = File(root, "ordered.docx")
            FileOutputStream(orderedDocx).use { output ->
                AndroidDocxWriter(app.appContainer.ocrService)
                    .writeDocxFromProcessedPages(orderedArtifacts, output, true, null) {}
            }
            ZipFile(orderedDocx).use { zip ->
                orderedArtifacts.forEachIndexed { index, artifact ->
                    val embedded = zip.getInputStream(
                        zip.getEntry("word/media/page${index + 1}.jpg")
                    ).use { it.readBytes() }
                    assertArrayEquals(artifact.file.readBytes(), embedded)
                }
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun replacingACameraPageCannotReuseItsPreviousOriginalOrManualQuad() = runBlocking {
        assertTrue(OpenCVLoader.initLocal())
        val root = File(context.cacheDir, "replace-export-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = asymmetricFixture(Color.rgb(35, 45, 60))
            val second = asymmetricFixture(Color.rgb(85, 25, 25))
            val metadata =
                PageMetadata(fullQuad(), Rotation.R0, ColorMode.ORIGINAL, ImageSize(800, 600), null)
            val repository =
                ImageRepository(root, ImageProcessor(180), scope) { _, _, error ->
                    throw AssertionError(error)
                }
            val firstProcessed = processedImage(
                Jpeg(first), metadata, Rotation.R0, ColorMode.ORIGINAL, ExportQuality.BALANCED,
            )
            val id = repository.addFileCapture(
                OriginalStore.newTempFile(OriginalStore.originalsDir(root))
                    .apply { writeBytes(first) },
                firstProcessed, metadata, ColorMode.ORIGINAL, CaptureTier.BALANCED,
                800, 600, 800, 600, 800, 600, "test", 0, 1, "test",
            )
            repository.setUserQuad(
                id,
                Quad(Point(0.2, 0.15), Point(0.8, 0.15), Point(0.8, 0.85), Point(0.2, 0.85)),
            )
            val secondProcessed = processedImage(
                Jpeg(second), metadata, Rotation.R0, ColorMode.ORIGINAL, ExportQuality.BALANCED,
            )
            repository.replacePage(id, secondProcessed, Jpeg(second), metadata, ColorMode.ORIGINAL)

            val page = repository.pages().single()
            assertEquals(2, page.quadVersion)
            assertEquals(fullQuad(), page.metadata!!.normalizedQuad)
            val artifact = prepareProcessedExportPages(
                repository, ExportQuality.ORIGINAL, File(root, "replace-cache"), "JPEG",
            ).single()
            assertEquals(ExportSourceType.CAMERA_ORIGINAL, artifact.sourceType)
            assertEquals(second.size.toLong(), artifact.inputByteSize)
            assertFalse(artifact.file.readBytes().contentEquals(first))
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private fun asymmetricFixture(background: Int = Color.rgb(35, 45, 60)): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val paint = Paint().apply { isAntiAlias = false }
        paint.color = Color.WHITE
        canvas.drawRect(100f, 60f, 700f, 540f, paint)
        paint.color = Color.RED
        canvas.drawRect(100f, 60f, 180f, 140f, paint)
        paint.color = Color.GREEN
        canvas.drawRect(620f, 60f, 700f, 140f, paint)
        paint.color = Color.BLUE
        canvas.drawRect(620f, 460f, 700f, 540f, paint)
        paint.color = Color.YELLOW
        canvas.drawRect(100f, 460f, 180f, 540f, paint)
        paint.color = Color.BLACK
        paint.textSize = 48f
        canvas.drawText("TOP 123", 270f, 180f, paint)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun fullQuad() = Quad(
        Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0)
    )

    private fun colorDistance(a: Int, b: Int): Int =
        kotlin.math.abs(Color.red(a) - Color.red(b)) +
                kotlin.math.abs(Color.green(a) - Color.green(b)) +
                kotlin.math.abs(Color.blue(a) - Color.blue(b))

    private fun normalizedGridDistance(a: Bitmap, b: Bitmap): Double {
        var total = 0L
        var count = 0
        for (yi in 1..5) {
            for (xi in 1..5) {
                val ax = xi * (a.width - 1) / 6
                val ay = yi * (a.height - 1) / 6
                val bx = xi * (b.width - 1) / 6
                val by = yi * (b.height - 1) / 6
                total += colorDistance(a.getPixel(ax, ay), b.getPixel(bx, by))
                count++
            }
        }
        return total.toDouble() / count
    }
}

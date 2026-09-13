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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import nopalito.app.data.ImageRepository
import nopalito.app.data.Logger
import nopalito.app.data.NormalizedQuad
import nopalito.app.data.PageV2
import nopalito.app.data.PointD
import nopalito.app.data.toMetadata
import nopalito.app.domain.ExportQuality
import nopalito.app.domain.Jpeg
import nopalito.app.domain.ProcessingRecipe
import nopalito.app.domain.Rotation
import nopalito.app.domain.ScanPipelineFlags
import nopalito.app.domain.pagesToExport
import nopalito.app.platform.BaselineReference
import nopalito.app.platform.ImageProcessor
import nopalito.app.platform.processedImage
import nopalito.imageprocessing.ColorMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.android.OpenCVLoader
import java.io.ByteArrayOutputStream
import java.io.File

class BaselineReferenceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(237, 229, 211))
        val paint = Paint().apply { color = Color.BLACK; textSize = 22f; isAntiAlias = true }
        for (i in 0..24) canvas.drawText(
            "Nopalito baseline 0123456789 line $i",
            36f,
            65f + i * 31f,
            paint
        )
        val result = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, result)
        bitmap.recycle()
        return result.toByteArray()
    }

    private fun page(mode: ColorMode = ColorMode.COLOR) = PageV2(
        id = "reference-test", baseRotationDegrees = 90, manualRotationDegrees = 0,
        quad = NormalizedQuad(
            PointD(0.04, 0.03),
            PointD(0.97, 0.04),
            PointD(0.95, 0.96),
            PointD(0.03, 0.97)
        ),
        isColored = true, colorMode = mode, sourceWidth = 640, sourceHeight = 900, jpegQuality = 75,
    )

    @Test
    fun baselineAdapterAndReplayAreByteIdenticalForEveryColorMode() {
        assertTrue(OpenCVLoader.initLocal())
        val source = fixture()
        val hashes = mutableListOf<String>()
        for (mode in ColorMode.entries) {
            val p = page(mode)
            val direct = processedImage(
                Jpeg(source),
                p.toMetadata()!!,
                Rotation.R90,
                mode,
                ExportQuality.BALANCED
            ).bytes
            val wrapped = BaselineReference.render(p, source)
            assertArrayEquals("Adapter changed $mode", direct, wrapped)
            val recipe = BaselineReference.recipe(p, source, wrapped, "original", false)
            assertArrayEquals(
                "Replay changed $mode",
                direct,
                BaselineReference.replay(recipe, source)
            )
            hashes += "$mode ${ProcessingRecipe.sha256(direct)}"
            if (mode == ColorMode.COLOR) {
                val dir = File(context.filesDir, "ab-validation").apply { mkdirs() }
                File(dir, "source.jpg").writeBytes(source)
                File(dir, "baseline.jpg").writeBytes(direct)
                File(
                    dir,
                    "recipe.json"
                ).writeText(ProcessingRecipe.recipeJson.encodeToString(recipe))
            }
        }
        File(context.filesDir, "ab-validation/hashes.txt").writeText(hashes.joinToString("\n"))
    }

    @Test
    fun repositoryFreezeReprocessRestartAndExportPreserveOriginalAndVisibleBaseline() =
        runBlocking {
            assertTrue(OpenCVLoader.initLocal())
            val root =
                File(context.cacheDir, "ab-repository-${System.nanoTime()}").apply { mkdirs() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val p = page()
                val source = fixture()
                val output = BaselineReference.render(p, source)
                val logger = Logger { _, _, error -> throw AssertionError(error) }
                val repository = ImageRepository(root, ImageProcessor(180), scope, logger)
                repository.add(Jpeg(output), Jpeg(source), p.toMetadata()!!, ColorMode.COLOR)
                val scan = repository.pages().single()
                // Exercise SourceOriginal precedence without touching the device's real document store.
                val original = repository.originalFile(scan.id)
                original.parentFile!!.mkdirs(); original.writeBytes(source)
                val before = repository.jpegBytes(scan.key())!!.bytes
                val frozen = repository.createBaselineReference(scan.id, true, ScanPipelineFlags())
                assertArrayEquals(
                    before,
                    repository.variants.imageFile(scan.id, frozen.id).readBytes()
                )
                val generated = repository.createBaselineReference(
                    scan.id,
                    false,
                    ScanPipelineFlags(dewarp = true)
                )
                assertEquals("original", generated.recipe.sourceKind)
                assertArrayEquals(source, original.readBytes())
                assertArrayEquals(before, repository.jpegBytes(scan.key())!!.bytes)
                assertEquals(frozen.id, repository.referenceSelection(scan.id).activeVariantId)
                val again = repository.createBaselineReference(scan.id, false, ScanPipelineFlags())
                assertEquals(generated.identity.recipeHash, again.identity.recipeHash)
                val reopened = ImageRepository(root, ImageProcessor(180), scope, logger)
                assertEquals(frozen.id, reopened.referenceSelection(scan.id).activeVariantId)
                assertArrayEquals(
                    before,
                    reopened.jpegBytes(reopened.pages().single().key())!!.bytes
                )
                val exports = pagesToExport(reopened, ExportQuality.BALANCED)
                assertArrayEquals(before, exports.single().jpeg.get().bytes)
                val dir = File(context.filesDir, "ab-validation").apply { mkdirs() }
                File(dir, "variant.json").writeText(
                    ProcessingRecipe.recipeJson.encodeToString(
                        generated
                    )
                )
                File(dir, "selection.json").writeText(
                    ProcessingRecipe.recipeJson.encodeToString(
                        reopened.referenceSelection(scan.id)
                    )
                )
            } finally {
                scope.cancel(); root.deleteRecursively()
            }
        }
}

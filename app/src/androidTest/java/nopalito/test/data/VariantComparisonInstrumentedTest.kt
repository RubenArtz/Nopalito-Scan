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
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import nopalito.app.domain.Jpeg
import nopalito.app.domain.ProcessingRecipe
import nopalito.app.domain.ScanPipelineFlags
import nopalito.app.platform.ImageProcessor
import nopalito.app.ui.screens.debug.VariantComparisonDialog
import nopalito.imageprocessing.ColorMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.opencv.android.OpenCVLoader
import java.io.ByteArrayOutputStream
import java.io.File

class VariantComparisonInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<TestComposeActivity>()

    @Test
    fun comparisonDisplaysExistingVariantsWithoutChangingFiles() {
        check(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "ab-ui-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream); bitmap.recycle()
            val bytes = stream.toByteArray()
            val repository = ImageRepository(
                root,
                ImageProcessor(100),
                scope,
                Logger { _, _, e -> throw AssertionError(e) })
            val page = PageV2(
                "ui",
                quad = NormalizedQuad(
                    PointD(0.0, 0.0),
                    PointD(1.0, 0.0),
                    PointD(1.0, 1.0),
                    PointD(0.0, 1.0)
                ),
                isColored = true,
                colorMode = ColorMode.COLOR,
                sourceWidth = 300,
                sourceHeight = 400
            )
            val id = runBlocking {
                repository.add(Jpeg(bytes), Jpeg(bytes), page.toMetadata()!!, ColorMode.COLOR)
                repository.pages().single().id.also {
                    repository.createBaselineReference(
                        it,
                        true,
                        ScanPipelineFlags()
                    )
                }
            }

            fun fileHashes() = root.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(root).path to ProcessingRecipe.sha256(it.readBytes())
            }

            val before = fileHashes()
            compose.setContent {
                MaterialTheme {
                    VariantComparisonDialog(id, repository, ScanPipelineFlags(), {}, {})
                }
            }
            compose.onNodeWithTag("variant_comparison_title").assertIsDisplayed()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(
                    context.getString(nopalito.app.R.string.processing_variant_baseline) + " · " + context.getString(
                        nopalito.app.R.string.processing_variant_active_suffix
                    )
                ).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("processing_variant_dialog").assertIsDisplayed()
            compose.onNodeWithTag("reset_zoom_button").performClick()
            compose.waitForIdle()
            assertEquals(before, fileHashes())
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val file = File(context.filesDir, "ab-validation/comparison.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        } finally {
            scope.cancel(); root.deleteRecursively()
        }
    }
}
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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import nopalito.app.data.OverlayRepository
import nopalito.app.ui.components.ImportedSignatureProcessor
import nopalito.app.ui.screens.document.PageOverlays
import nopalito.app.ui.screens.document.SignatureSource
import nopalito.app.ui.screens.document.SignatureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OverlayRepositoryImportedSignatureTest {

    @Test
    fun importedSignatureReloadsFromPersistedPngBytes() = runBlocking {
        val root = File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "overlay-repository-test",
        )
        root.deleteRecursively()

        try {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.setPixel(0, 0, Color.TRANSPARENT)
            bitmap.setPixel(1, 0, Color.argb(80, 1, 2, 3))
            bitmap.setPixel(0, 1, Color.BLUE)
            bitmap.setPixel(1, 1, Color.TRANSPARENT)
            val state = SignatureState(
                source = SignatureSource.IMPORTED,
                importedImageBytes = ImportedSignatureProcessor.toPngBytes(bitmap),
                overlayScale = 1.4f,
                positionFractionX = 0.2f,
                positionFractionY = 0.3f,
            )
            val repository = OverlayRepository(root)
            repository.saveAll(
                mapOf(
                    "page" to PageOverlays(
                        signatureState = state,
                        signatureBitmap = bitmap,
                        signatureSource = SignatureSource.IMPORTED,
                        signaturePositionFraction = Offset(0.2f, 0.3f),
                        signatureScale = 1.4f,
                    )
                )
            )

            File(root, "overlays/signatures/sig_page.png").delete()
            val restored = requireNotNull(repository.loadAll()["page"])
            val restoredBitmap = requireNotNull(restored.signatureBitmap)

            assertEquals(1.4f, restored.signatureScale)
            assertEquals(0, Color.alpha(restoredBitmap.getPixel(0, 0)))
            assertEquals(80, Color.alpha(restoredBitmap.getPixel(1, 0)))
            assertTrue(restored.signatureState!!.importedImageBytes!!.isNotEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}

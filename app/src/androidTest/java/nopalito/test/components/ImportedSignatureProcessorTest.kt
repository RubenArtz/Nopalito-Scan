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

package nopalito.test.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import nopalito.app.ui.components.ImportedSignatureProcessor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportedSignatureProcessorTest {

    @Test
    fun removeBackgroundCreatesTransparency() {
        val bitmap = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.WHITE)
        bitmap.setPixel(1, 0, Color.BLACK)
        bitmap.setPixel(2, 0, Color.argb(128, 40, 40, 40))

        val result = ImportedSignatureProcessor.removeBackground(bitmap, 160)

        assertEquals(0, Color.alpha(result.getPixel(0, 0)))
        assertEquals(255, Color.alpha(result.getPixel(1, 0)))
        assertEquals(128, Color.alpha(result.getPixel(2, 0)))
        assertEquals(2, ImportedSignatureProcessor.alphaBounds(result).width())
    }

    @Test
    fun eraserOnlyClearsAlpha() {
        val bitmap = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)

        ImportedSignatureProcessor.eraseAt(bitmap, 2, 2, 1)

        assertEquals(0, Color.alpha(bitmap.getPixel(2, 2)))
        // Untouched pixel keeps its color and alpha.
        assertEquals(255, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(255, Color.red(bitmap.getPixel(0, 0)))
        // RGB of a fully transparent pixel is meaningless (premultiplied
        // storage zeroes it), so only the alpha is asserted for the erasure.
    }

    @Test
    fun recoloringRetainsTransparencyAndShading() {
        val bitmap = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.argb(64, 20, 20, 20))
        bitmap.setPixel(1, 0, Color.argb(255, 200, 200, 200))
        bitmap.setPixel(2, 0, Color.TRANSPARENT)

        val result = ImportedSignatureProcessor.applyColor(bitmap, Color.BLUE)

        // Dark ink stays dark blue; light pixel stays lighter (shading preserved)
        val dark = result.getPixel(0, 0)
        assertEquals(64, Color.alpha(dark))
        assertTrue(Color.red(dark) <= 10 && Color.green(dark) <= 10)
        assertTrue(Color.blue(dark) in 90..130)
        val light = result.getPixel(1, 0)
        assertTrue(Color.blue(light) > Color.blue(dark))
        assertEquals(0, Color.alpha(result.getPixel(2, 0)))
    }

    @Test
    fun applyColorIsIdempotent() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.argb(255, 20, 20, 20))

        val firstPass = ImportedSignatureProcessor.applyColor(bitmap, Color.BLUE)
        val secondPass = ImportedSignatureProcessor.applyColor(firstPass, Color.BLUE)

        assertEquals(
            firstPass.getPixel(0, 0),
            secondPass.getPixel(0, 0),
        )
    }

    @Test
    fun finalBitmapRoundTripsAsArgbPng() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.TRANSPARENT)
        bitmap.setPixel(1, 0, Color.argb(96, 10, 20, 30))
        bitmap.setPixel(0, 1, Color.BLUE)
        bitmap.setPixel(1, 1, Color.TRANSPARENT)
        val expectedHash = bitmapHash(bitmap)

        val restored = ImportedSignatureProcessor.decodeArgb8888(
            ImportedSignatureProcessor.toPngBytes(bitmap)
        )

        assertNotNull(restored)
        assertEquals(Bitmap.Config.ARGB_8888, restored!!.config)
        assertEquals(expectedHash, bitmapHash(restored))
        assertTrue(Color.alpha(restored.getPixel(1, 0)) > 0)
    }

    @Test
    fun estimateBackgroundThresholdTracksPaperColor() {
        // Bright paper (mode ~250) → threshold near the top of the range.
        val bright = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        bright.eraseColor(Color.rgb(250, 250, 250))
        bright.setPixel(0, 0, Color.rgb(30, 30, 30))
        val brightThreshold = ImportedSignatureProcessor.estimateBackgroundThreshold(bright)
        assertTrue(brightThreshold in 200..240)

        // Dim paper (mode ~140) → threshold must drop well below the old
        // fixed default so dim backgrounds are actually removed.
        val dim = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        dim.eraseColor(Color.rgb(140, 140, 140))
        dim.setPixel(0, 0, Color.rgb(30, 30, 30))
        val dimThreshold = ImportedSignatureProcessor.estimateBackgroundThreshold(dim)
        assertTrue(dimThreshold in 60..140)

        // With the estimated threshold, dim paper is removed while ink stays.
        val removedDim = ImportedSignatureProcessor.removeBackground(dim, dimThreshold)
        assertEquals(0, Color.alpha(removedDim.getPixel(5, 5)))
        assertEquals(255, Color.alpha(removedDim.getPixel(0, 0)))

        bright.recycle()
        dim.recycle()
    }

    @Test
    fun estimatorIgnoresDominantDarkDesk() {
        // Bimodal photo: bright paper plus a pure black desk taking 30% of
        // the frame. The global histogram mode is black, so the threshold
        // must come from the bright peak or the paper would stay opaque.
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(245, 245, 245))
        for (y in 14 until 20) {
            for (x in 0 until 20) bitmap.setPixel(x, y, Color.rgb(0, 0, 0))
        }
        val threshold = ImportedSignatureProcessor.estimateBackgroundThreshold(bitmap)
        assertTrue("threshold was $threshold", threshold >= 200)

        // Removing with that threshold keeps the desk ink-dark but drops paper.
        val removedDesk = ImportedSignatureProcessor.removeBackground(bitmap, threshold)
        assertEquals(0, Color.alpha(removedDesk.getPixel(5, 5)))
        assertEquals(255, Color.alpha(removedDesk.getPixel(5, 17)))

        bitmap.recycle()
    }

    @Test
    fun borderConnectivityRemovesPaperAndDeskButKeepsInkIsland() {
        // Paper (240) on top, dark desk (0) at the bottom, dark ink island in
        // the middle — the bimodal photo a single threshold cannot handle.
        // Mirrors the editor pipeline: threshold pass carves the ink out of
        // the paper, then the connectivity flood erases border-connected
        // leftovers (the desk) while the interior island survives.
        val bitmap = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(240, 240, 240))
        for (x in 0 until 5) bitmap.setPixel(x, 4, Color.rgb(0, 0, 0))
        bitmap.setPixel(2, 2, Color.rgb(30, 30, 30))

        val thresholded = ImportedSignatureProcessor.removeBackground(bitmap, 200)
        val result = ImportedSignatureProcessor.removeBorderConnected(thresholded)

        assertEquals(0, Color.alpha(result.getPixel(0, 0)))
        assertEquals(0, Color.alpha(result.getPixel(4, 4)))
        assertEquals(255, Color.alpha(result.getPixel(2, 2)))
        assertEquals(1, ImportedSignatureProcessor.alphaBounds(result).width())
        assertEquals(1, ImportedSignatureProcessor.alphaBounds(result).height())

        bitmap.recycle()
    }

    private fun bitmapHash(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.contentHashCode()
    }
}

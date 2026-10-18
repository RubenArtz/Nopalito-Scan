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

package nopalito.imageprocessing

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.opencv.core.Point as CvPoint

class ImportedPhotoQuadSelectionTest {

    companion object {
        init {
            runCatching { nu.pattern.OpenCV.loadShared() }
        }
    }

    @Test
    fun `import selection rejects a strong small rectangle inside a document`() {
        val width = 320
        val height = 420
        val probability = Mat(height, width, CvType.CV_32FC1, Scalar(0.05))
        Imgproc.rectangle(
            probability,
            CvPoint(30.0, 25.0),
            CvPoint(290.0, 395.0),
            Scalar(0.65),
            -1,
        )
        Imgproc.rectangle(
            probability,
            CvPoint(110.0, 125.0),
            CvPoint(210.0, 275.0),
            Scalar(0.98),
            -1,
        )

        val selected = assertNotNull(
            detectDocumentQuad(
                probability.asMask(width, height),
                ImageSize(width, height),
                Mode.IMPORT
            ),
            "the larger page boundary should remain selectable",
        )
        val areaRatio = polygonArea(
            listOf(selected.topLeft, selected.topRight, selected.bottomRight, selected.bottomLeft)
                .map { CvPoint(it.x, it.y) },
        ) / (width.toDouble() * height)

        assertTrue(areaRatio >= 0.15, "selected area ratio was $areaRatio")
        probability.release()
    }

    @Test
    fun `import selection returns no quad when only a small region is detected`() {
        val width = 320
        val height = 420
        val probability = Mat(height, width, CvType.CV_32FC1, Scalar(0.05))
        Imgproc.rectangle(
            probability,
            CvPoint(110.0, 125.0),
            CvPoint(210.0, 275.0),
            Scalar(0.98),
            -1,
        )

        val selected = detectDocumentQuad(
            probability.asMask(width, height),
            ImageSize(width, height),
            Mode.IMPORT,
        )

        assertNull(selected, "a small interior region must not become an auto-crop")
        probability.release()
    }

    private fun Mat.asMask(width: Int, height: Int): Mask = object : Mask {
        override val width: Int = width
        override val height: Int = height
        override fun toMat(): Mat = this@asMask
    }
}

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

package nopalito.app.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import nopalito.app.data.NormalizedQuad
import nopalito.app.data.PointD
import nopalito.imageprocessing.Point
import nopalito.imageprocessing.getPerspectiveTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ProjectionCurvatureAnalyzerTest {
    private val analyzer = ProjectionCurvatureAnalyzer()

    @Test
    fun flatRectifiedPageHasSufficientTextEvidenceAndNoChange() {
        val page = printedPage()
        val before = pixelHash(page)
        val result = analyze(page, fullQuad(page))
        assertEquals(CurvatureDecision.NO_CHANGE, result.decision)
        assertTrue(result.evidence.eligibleTextLineCount >= 3)
        assertTrue(result.evidence.lines.any { it.evidenceType == CurvatureEvidenceType.TEXT_BASELINE })
        assertEquals(before, pixelHash(page))
        page.recycle()
    }

    @Test
    fun perspectiveDistortedFlatPageIsRectifiedBeforeCurvatureMeasurement() {
        val rectified = printedPage()
        val scene = perspectiveScene(rectified, curved = false)
        val before = pixelHash(scene.bitmap)
        val result = analyze(scene.bitmap, scene.quad)
        assertEquals(CurvatureDecision.NO_CHANGE, result.decision)
        assertTrue(result.evidence.eligibleTextLineCount >= 3)
        assertEquals("PERSPECTIVE_ANALYSIS_TO_RECTIFIED_PAGE", result.geometry.transformMappingId)
        assertEquals(9, result.geometry.transformMatrix?.size)
        assertEquals(before, pixelHash(scene.bitmap))
        rectified.recycle()
        scene.bitmap.recycle()
    }

    @Test
    fun curvedBaselinesRemainDetectableAfterPerspectiveRectification() {
        val page = printedPage(curved = true, sagPixels = 70f)
        val scene = perspectiveScene(page, curved = false)
        val result = analyze(scene.bitmap, scene.quad)
        assertEquals(CurvatureDecision.CURVATURE_DETECTED, result.decision)
        assertTrue(result.evidence.eligibleTextLineCount >= 3)
        assertTrue(result.evidence.medianAbsoluteCurvature!! >= 0.008)
        page.recycle()
        scene.bitmap.recycle()
    }

    @Test
    fun curvedPageWithPersistedQuadUsesItsCoordinates() {
        val page = printedPage(curved = true, sagPixels = 70f)
        val q = fullQuad(page)
        val result = analyze(page, q)
        assertEquals(CurvatureDecision.CURVATURE_DETECTED, result.decision)
        assertEquals(CurvatureQuadSource.PERSISTED, result.geometry.quadSource)
        assertEquals(q.points(), result.geometry.quadCoordinatesBeforeOrientation)
        page.recycle()
    }

    @Test
    fun invalidQuadIsRejectedUnsafeAndMissingQuadFallsBackToInsufficientEvidence() {
        val page = printedPage()
        val invalid = NormalizedQuad(
            PointD(0.1, 0.1), PointD(0.9, 0.9), PointD(0.1, 0.9), PointD(0.9, 0.1)
        )
        assertEquals(CurvatureDecision.REJECTED_UNSAFE, analyze(page, invalid).decision)
        val missing = analyzer.analyze(CurvatureAnalysisRequest(page, null, 0))
        assertEquals(CurvatureDecision.INSUFFICIENT_EVIDENCE, missing.decision)
        assertEquals(CurvatureQuadSource.MISSING, missing.geometry.quadSource)
        assertEquals("IDENTITY_FULL_IMAGE_FALLBACK", missing.geometry.transformMappingId)
        page.recycle()
    }

    @Test
    fun blankPageReturnsInsufficientEvidence() {
        val page = blankPage()
        val result = analyze(page, fullQuad(page))
        assertEquals(CurvatureDecision.INSUFFICIENT_EVIDENCE, result.decision)
        assertEquals(0, result.evidence.eligibleTextLineCount)
        page.recycle()
    }

    @Test
    fun lowTextPageReturnsInsufficientEvidence() {
        val page = printedPage(lineCount = 2)
        val result = analyze(page, fullQuad(page))
        assertEquals(CurvatureDecision.INSUFFICIENT_EVIDENCE, result.decision)
        assertTrue(result.evidence.eligibleTextLineCount < 5)
        page.recycle()
    }

    @Test
    fun tableOnlyPageIsClassifiedSeparatelyAndCannotDriveTextCurvature() {
        val page = tablePage()
        val result = analyze(page, fullQuad(page))
        assertEquals(CurvatureDecision.INSUFFICIENT_EVIDENCE, result.decision)
        assertTrue(result.evidence.lines.any { it.evidenceType == CurvatureEvidenceType.TABLE_LINE })
        assertFalse(result.evidence.lines.any { it.evidenceType == CurvatureEvidenceType.TEXT_BASELINE && it.acceptedForDecision })
        page.recycle()
    }

    @Test
    fun illustrationOnlyPageIsInsufficientAndDoesNotBecomeTextEvidence() {
        val page = illustrationPage()
        val result = analyze(page, fullQuad(page))
        assertEquals(CurvatureDecision.INSUFFICIENT_EVIDENCE, result.decision)
        assertFalse(result.evidence.lines.any { it.evidenceType == CurvatureEvidenceType.TEXT_BASELINE && it.acceptedForDecision })
        assertTrue(result.evidence.lines.any { it.evidenceType == CurvatureEvidenceType.GRAPHIC_EDGE || it.evidenceType == CurvatureEvidenceType.UNKNOWN })
        page.recycle()
    }

    @Test
    fun mixedTextAndIllustrationUsesOnlyIndependentTextBaselines() {
        val page = printedPage()
        Canvas(page).drawCircle(620f, 760f, 55f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
        })
        val result = analyze(page, fullQuad(page))
        assertEquals(CurvatureDecision.NO_CHANGE, result.decision)
        assertTrue(result.evidence.lines.any { it.evidenceType == CurvatureEvidenceType.TEXT_BASELINE && it.acceptedForDecision })
        page.recycle()
    }

    @Test
    fun handwritingHintNeverPromotesUnvalidatedStrokesToTextBaselines() {
        val page = handwritingPage()
        val result = analyzer.analyze(
            CurvatureAnalysisRequest(
                page,
                fullQuad(page),
                0,
                documentType = "HANDWRITING"
            )
        )
        assertEquals(CurvatureDecision.INSUFFICIENT_EVIDENCE, result.decision)
        assertTrue(result.evidence.lines.any { it.evidenceType == CurvatureEvidenceType.HANDWRITING })
        assertFalse(result.evidence.lines.any { it.evidenceType == CurvatureEvidenceType.TEXT_BASELINE && it.acceptedForDecision })
        page.recycle()
    }

    @Test
    fun exifEffectiveOrientationsMapQuadAndApplyExactlyOnce() {
        for (degrees in listOf(0, 90, 180, 270)) {
            val upright = printedPage()
            val raw = rotateBitmap(upright, (360 - degrees) % 360)
            val result = analyze(raw, fullQuad(raw), degrees)
            assertEquals("orientation=$degrees", CurvatureDecision.NO_CHANGE, result.decision)
            assertEquals(degrees, result.geometry.orientationAppliedDegrees)
            assertEquals(raw.width, result.geometry.sourceImageDimensions.width)
            assertEquals(
                if (degrees % 180 == 0) raw.width else raw.height,
                result.geometry.orientedImageDimensions.width
            )
            val expectedTopLeft = PointD(0.0, 0.0)
            assertEquals(
                expectedTopLeft.x,
                result.geometry.quadCoordinatesAfterOrientation!!.first().x,
                1e-6
            )
            assertEquals(
                expectedTopLeft.y,
                result.geometry.quadCoordinatesAfterOrientation!!.first().y,
                1e-6
            )
            upright.recycle()
            raw.recycle()
        }
    }

    @Test
    fun differentSourceResolutionsPreserveDecisionAndRecordScale() {
        val high = printedPage(800, 1000)
        val low = Bitmap.createScaledBitmap(high, 400, 500, true)
        val highResult = analyze(high, fullQuad(high))
        val lowResult = analyze(low, fullQuad(low))
        assertEquals(CurvatureDecision.NO_CHANGE, highResult.decision)
        assertEquals(highResult.decision, lowResult.decision)
        assertTrue(highResult.geometry.sourceToAnalysisScaleX <= 1.0)
        assertTrue(lowResult.geometry.sourceToAnalysisScaleX <= 1.0)
        assertNotNull(lowResult.geometry.rectifiedPageDimensions)
        high.recycle()
        low.recycle()
    }

    @Test
    fun weakQuadConfidenceIsUnsafeAndEvidenceContainsCandidateDiagnostics() {
        val page = printedPage()
        val rejected = analyzer.analyze(
            CurvatureAnalysisRequest(
                page,
                fullQuad(page),
                0,
                detectionConfidence = 0.2f
            )
        )
        assertEquals(CurvatureDecision.REJECTED_UNSAFE, rejected.decision)
        val supported = analyze(page, fullQuad(page))
        val line =
            supported.evidence.lines.first { it.evidenceType == CurvatureEvidenceType.TEXT_BASELINE }
        assertTrue(line.supportingPointCount >= 5)
        assertNotNull(line.fitResidual)
        assertNotNull(line.curvatureCoefficient)
        assertTrue(line.coverageRatio > 0.0)
        assertTrue(line.confidence in 0.0f..1.0f)
        page.recycle()
    }

    private fun analyze(bitmap: Bitmap, quad: NormalizedQuad, orientation: Int = 0) =
        analyzer.analyze(
            CurvatureAnalysisRequest(
                source = bitmap,
                quad = quad,
                orientationDegrees = orientation,
                detectionConfidence = 0.9f,
                maxAnalysisDimension = 1600,
            )
        )

    private fun printedPage(
        width: Int = 800,
        height: Int = 1000,
        curved: Boolean = false,
        sagPixels: Float = 0f,
        lineCount: Int = 10,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = maxOf(14f, width / 29f)
            typeface =
                android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        val line = "The quick brown fox jumps over the lazy dog 0123456789"
        val lineWidth = paint.measureText(line)
        val left = ((width - lineWidth) / 2f).coerceAtLeast(width * 0.04f)
        repeat(lineCount) { row ->
            val baseline = height * (0.16f + row * (0.72f / maxOf(1, lineCount - 1)))
            if (!curved) {
                canvas.drawText(line, left, baseline, paint)
            } else {
                var x = left
                line.forEach { char ->
                    val t = 2f * (x - width / 2f) / width
                    canvas.drawText(char.toString(), x, baseline + sagPixels * t * t, paint)
                    x += paint.measureText(char.toString())
                }
            }
        }
        return bitmap
    }

    private fun blankPage(width: Int = 800, height: Int = 1000) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.WHITE) }

    private fun tablePage(): Bitmap = blankPage().also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
        for (i in 0..5) {
            val y = 220f + i * 90f
            canvas.drawLine(90f, y, 710f, y, paint)
        }
        for (i in 0..3) {
            val x = 90f + i * 206f
            canvas.drawLine(x, 220f, x, 670f, paint)
        }
    }

    private fun illustrationPage(): Bitmap = blankPage().also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 5f
        }
        canvas.drawCircle(260f, 350f, 110f, paint)
        canvas.drawLine(470f, 470f, 670f, 300f, paint)
        canvas.drawLine(670f, 300f, 700f, 510f, paint)
        canvas.drawLine(700f, 510f, 470f, 470f, paint)
        canvas.drawArc(200f, 600f, 650f, 880f, 10f, 135f, false, paint)
    }

    private fun handwritingPage(): Bitmap = blankPage().also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; strokeWidth = 5f; style = Paint.Style.STROKE
        }
        for (row in 0..7) {
            val path = android.graphics.Path().apply { moveTo(90f, 230f + row * 70f) }
            for (x in 100..700 step 20) {
                val y = 230f + row * 70f + ((x / 20 + row) % 3 - 1) * 8f
                path.lineTo(x.toFloat(), y)
            }
            canvas.drawPath(path, paint)
        }
    }

    private data class Scene(val bitmap: Bitmap, val quad: NormalizedQuad)

    private fun perspectiveScene(page: Bitmap, curved: Boolean): Scene {
        @Suppress("UNUSED_VARIABLE") val requestedGeometry = curved
        val width = 1100
        val height = 1200
        val corners = listOf(
            PointD(0.16, 0.10), PointD(0.84, 0.17), PointD(0.90, 0.88), PointD(0.10, 0.82),
        )
        val sourceMat = Mat()
        val scene = Mat(height, width, CvType.CV_8UC4, Scalar(228.0, 228.0, 228.0, 255.0))
        val pageMat = Mat()
        val warpedPage = Mat()
        val mask = Mat(page.height, page.width, CvType.CV_8UC1, Scalar.all(255.0))
        val warpedMask = Mat()
        val h = getPerspectiveTransform(
            listOf(
                Point(0.0, 0.0),
                Point(page.width - 1.0, 0.0),
                Point(page.width - 1.0, page.height - 1.0),
                Point(0.0, page.height - 1.0)
            ),
            corners.map { Point(it.x * width, it.y * height) },
        )
        try {
            Utils.bitmapToMat(page, pageMat)
            Imgproc.warpPerspective(
                pageMat,
                warpedPage,
                h,
                Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_CUBIC
            )
            Imgproc.warpPerspective(
                mask,
                warpedMask,
                h,
                Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_NEAREST
            )
            warpedPage.copyTo(scene, warpedMask)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(scene, bitmap)
            return Scene(
                bitmap,
                NormalizedQuad(corners[0], corners[1], corners[2], corners[3]),
            )
        } finally {
            sourceMat.release()
            scene.release()
            pageMat.release()
            warpedPage.release()
            mask.release()
            warpedMask.release()
            h.release()
        }
    }

    private fun fullQuad(bitmap: Bitmap) = NormalizedQuad(
        PointD(0.0, 0.0), PointD(1.0, 0.0), PointD(1.0, 1.0), PointD(0.0, 1.0)
    )

    private fun NormalizedQuad.points() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source.copy(Bitmap.Config.ARGB_8888, true)
        val src = Mat()
        val rotated = Mat()
        try {
            Utils.bitmapToMat(source, src)
            when (degrees) {
                90 -> Core.rotate(src, rotated, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(src, rotated, Core.ROTATE_180)
                270 -> Core.rotate(src, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            }
            val output =
                Bitmap.createBitmap(rotated.cols(), rotated.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rotated, output)
            return output
        } finally {
            src.release()
            rotated.release()
        }
    }

    private fun pixelHash(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { index, pixel ->
            bytes[index * 4] = (pixel ushr 24).toByte()
            bytes[index * 4 + 1] = (pixel ushr 16).toByte()
            bytes[index * 4 + 2] = (pixel ushr 8).toByte()
            bytes[index * 4 + 3] = pixel.toByte()
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeOpenCv() {
            assertTrue("OpenCV native library must be available", OpenCVLoader.initLocal())
        }
    }
}

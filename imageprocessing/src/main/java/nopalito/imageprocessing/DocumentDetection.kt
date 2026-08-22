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

import nopalito.imageprocessing.quad.findQuadFromContourOrientation
import nopalito.imageprocessing.quad.minAreaRect
import nopalito.imageprocessing.quad.scoreQuadAgainstProbmap
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.sqrt

interface Mask {
    val width: Int
    val height: Int
    fun toMat(): Mat
}

enum class Mode {
    CAPTURE, IMPORT, LIVE_ANALYSIS
}

/**
 * Minimum probmap score ([scoreQuadAgainstProbmap]) for a LIVE_ANALYSIS quad
 * to be trusted and drawn. Garbage detections from noisy low-threshold masks
 * score far below this; real documents score close to 1. Capture keeps the old
 * un-gated behavior (its result is only used when the user actually shoots).
 */
private const val LIVE_ANALYSIS_MIN_QUAD_SCORE = 0.5

fun detectDocumentQuad(mask: Mask, originalSize: ImageSize, mode: Mode): Quad? {
    val mat = mask.toMat()
    // Best thresholds on test dataset: {0.95=146, 0.85=39, 0.75=35, 0.90=8, 0.70=1, 0.35=1}
    // For LIVE_ANALYSIS we use a curated subset (same best thresholds as CAPTURE, fewer entries for speed)
    val thresholds =
        if (mode == Mode.CAPTURE) listOf(0.5, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95)
        else listOf(0.5, 0.7, 0.85, 0.9, 0.95)
    val minQuadScore = if (mode == Mode.LIVE_ANALYSIS) LIVE_ANALYSIS_MIN_QUAD_SCORE else 0.0
    var vertices = findQuadFromOrientationWithAdaptiveThreshold(mat, originalSize, thresholds, minQuadScore)
        ?.map { Point(it.x, it.y) }

    if (vertices == null && mode == Mode.CAPTURE) {
        // Fallback: bounding rectangle
        val biggest = biggestContour(mat)
        if (biggest != null) {
            val polygon = biggest.toList().map { Point(it.x, it.y) }
            vertices = minAreaRect(polygon, mask.width, mask.height)
        }
    }
    val maskSize = ImageSize(mask.width, mask.height)
    return if (vertices?.size == 4 && vertices.all { isInsideImage(it, maskSize) })
        createQuad(vertices)
    else null
}

fun findQuadFromOrientationWithAdaptiveThreshold(
    maskMat: Mat,
    originalSize: ImageSize,
    thresholds: List<Double>,
    minScore: Double = 0.0,
): List<org.opencv.core.Point>? {
    val probmapU8 = Mat()
    val probmap = maskMat
    probmap.convertTo(probmapU8, CvType.CV_8U, 255.0)
    val probmapSmooth = Mat()
    Imgproc.GaussianBlur(probmapU8, probmapSmooth, Size(3.0, 3.0), 0.0)

    var bestQuad: List<org.opencv.core.Point>? = null
    var bestScore = 0.0
    for (thr in thresholds) {
        val bin = Mat()
        Imgproc.threshold(probmapSmooth, bin, thr * 255.0, 255.0, Imgproc.THRESH_BINARY)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)
        val quad = findQuadFromOrientation(bin, originalSize)
        if (quad != null) {
            val probFloat = Mat()
            probmap.convertTo(probFloat, CvType.CV_32F)
            val score = scoreQuadAgainstProbmap(quad, probFloat, minQuadAreaRatio = 0.02)
            if (score > bestScore && score >= minScore) {
                bestScore = score
                bestQuad = quad
            }
        }
        bin.release()
    }

    probmapSmooth.release()
    probmapU8.release()
    return bestQuad
}

fun isInsideImage(p: Point, imageSize: ImageSize): Boolean {
    return p.x >= 0 && p.x <= imageSize.width
            && p.y >= 0 && p.y <= imageSize.height
}

fun findQuadFromOrientation(maskMat: Mat, originalSize: ImageSize): List<org.opencv.core.Point>? {
    val contour = biggestContour(maskMat)
    contour ?: return null

    val scaleX = originalSize.width / maskMat.size().width
    val scaleY = originalSize.height / maskMat.size().height

    // The mask may have a different width/height ratio than the original image.
    // It's crucial for angles that the width/height ratio is the one of the original image.
    return findQuadFromContourOrientation(
        contour.toList().map { org.opencv.core.Point(it.x * scaleX, it.y * scaleY) }
    )?.map { org.opencv.core.Point(it.x / scaleX, it.y / scaleY) }
}

fun biggestContour(mat: Mat): MatOfPoint? {
    val refinedMask = refineMask(mat)

    val blurred = Mat()
    Imgproc.GaussianBlur(refinedMask, blurred, Size(5.0, 5.0), 0.0)

    val edges = Mat()
    Imgproc.Canny(blurred, edges, 75.0, 200.0)

    val contours = mutableListOf<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)

    var biggest: MatOfPoint? = null
    var maxArea = 0.0

    for (contour in contours) {
        val area = polygonArea(contour.toList())
        if (area > maxArea) {
            maxArea = area
            biggest = contour
        }
    }
    return biggest
}

fun polygonArea(points: List<org.opencv.core.Point>): Double {
    var area = 0.0
    for (i in points.indices) {
        val p = points[i]
        val q = points[(i + 1) % points.size]
        area += p.x * q.y - q.x * p.y
    }
    return abs(area / 2.0)
}

/**
 * Applies morphological operations to improve a document mask.
 */
fun refineMask(original: Mat): Mat {
    // Step 0: Ensure the mask is binary (just in case)
    val binaryMask = Mat()
    Imgproc.threshold(original, binaryMask, 128.0, 255.0, Imgproc.THRESH_BINARY)

    // Step 1: Closing (fills small holes)
    val kernelClose = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
    val closed = Mat()
    Imgproc.morphologyEx(binaryMask, closed, Imgproc.MORPH_CLOSE, kernelClose)

    // Step 2: Gentle opening (removes isolated noise)
    val kernelOpen = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
    val opened = Mat()
    Imgproc.morphologyEx(closed, opened, Imgproc.MORPH_OPEN, kernelOpen)

    return opened
}

fun extractDocument(
    inputMat: Mat,
    quad: Quad,
    rotationDegrees: Int,
    colorMode: ColorMode,
    maxPixels: Long,
    opticalMeasures: OpticalMeasures? = null,
): Mat {
    val estimatedDimensions = estimateRealDimensions(
        quad,
        inputMat.cols(),
        inputMat.rows(),
        opticalMeasures,
    ).snapToStandardFormat()
    val (targetWidth, targetHeight) = estimatedDimensions.toPixelDimensions(quad)
    val transform = getPerspectiveTransform(
        listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft),
        listOf(
            Point(0.0, 0.0),
            Point(targetWidth, 0.0),
            Point(targetWidth, targetHeight),
            Point(0.0, targetHeight),
        )
    )

    val warped = Mat()
    val outputSize = Size(targetWidth, targetHeight)
    Imgproc.warpPerspective(inputMat, warped, transform, outputSize)

    val resized = resizeForMaxPixels(warped, maxPixels.toDouble())
    val enhanced = enhanceCapturedImage(resized, colorMode)
    // Blur corrector: compensate out-of-focus / motion-blurred captures
    // before finalizing the page (no-op on already sharp frames).
    val corrected = correctBlur(enhanced)
    val rotated = rotate(corrected, rotationDegrees)

    warped.release()
    resized.release()
    enhanced.release()

    return rotated
}

/**
 * Computes the 3x3 perspective transform mapping `src` onto `dst` using the
 * Direct Linear Transform (the same algorithm as OpenCV's
 * `getPerspectiveTransform`), solved with Gaussian elimination with partial
 * pivoting. Implemented in pure Kotlin because OpenCV 5.0 moved this API to
 * `org.opencv.geometry.Geometry`, which is unavailable to the JVM module.
 */
fun getPerspectiveTransform(
    src: List<Point>,
    dst: List<Point>,
): Mat {
    require(src.size == 4 && dst.size == 4) { "Exactly 4 correspondences required" }

    val a = Array(8) { DoubleArray(8) }
    val b = DoubleArray(8)
    for (i in 0 until 4) {
        val x = src[i].x
        val y = src[i].y
        val u = dst[i].x
        val v = dst[i].y
        a[2 * i][0] = x
        a[2 * i][1] = y
        a[2 * i][2] = 1.0
        a[2 * i][6] = -x * u
        a[2 * i][7] = -y * u
        b[2 * i] = u
        a[2 * i + 1][3] = x
        a[2 * i + 1][4] = y
        a[2 * i + 1][5] = 1.0
        a[2 * i + 1][6] = -x * v
        a[2 * i + 1][7] = -y * v
        b[2 * i + 1] = v
    }

    val n = 8
    for (col in 0 until n) {
        var pivot = col
        for (row in col + 1 until n) {
            if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
        }
        if (pivot != col) {
            val tmp = a[pivot]
            a[pivot] = a[col]
            a[col] = tmp
            val tb = b[pivot]
            b[pivot] = b[col]
            b[col] = tb
        }
        val scale = a[col][col]
        if (abs(scale) < 1e-12) {
            throw IllegalArgumentException("Degenerate quad: perspective transform is singular")
        }
        for (j in col until n) a[col][j] /= scale
        b[col] /= scale
        for (row in 0 until n) {
            if (row != col && abs(a[row][col]) > 1e-14) {
                val factor = a[row][col]
                for (j in col until n) a[row][j] -= factor * a[col][j]
                b[row] -= factor * b[col]
            }
        }
    }

    val h = DoubleArray(9)
    for (i in 0 until 8) h[i] = b[i]
    h[8] = 1.0

    val m = Mat(3, 3, CvType.CV_64FC1)
    m.put(0, 0, *h)
    return m
}

fun EstimatedDimensions.toPixelDimensions(quad: Quad): Pair<Double, Double> {
    val w = (norm(quad.topLeft, quad.topRight) + norm(quad.bottomLeft, quad.bottomRight)) / 2
    val h = (norm(quad.topLeft, quad.bottomLeft) + norm(quad.topRight, quad.bottomRight)) / 2
    val projectedArea = w * h

    val ratio = aspectRatio
    val targetWidth = sqrt(projectedArea / ratio)
    val targetHeight = targetWidth * ratio
    return Pair(targetWidth, targetHeight)
}

fun rotate(input: Mat, degrees: Int): Mat {
    val output = Mat()
    when ((degrees % 360 + 360) % 360) {
        0 -> input.copyTo(output)
        90 -> Core.rotate(input, output, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(input, output, Core.ROTATE_180)
        270 -> Core.rotate(input, output, Core.ROTATE_90_COUNTERCLOCKWISE)
        else -> throw IllegalArgumentException("Only 0, 90, 180, 270 degrees are supported")
    }
    return output
}

fun Point.toCv(): org.opencv.core.Point {
    return org.opencv.core.Point(x, y)
}

fun Size.toImageSize(): ImageSize {
    return ImageSize(width, height)
}

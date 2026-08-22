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

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

enum class ColorMode {
    COLOR,
    GRAYSCALE,
}

// ── Blur correction ────────────────────────────────────────────────────────
//
// Camera captures can come out slightly out of focus or motion-blurred.
// The corrector measures sharpness with the classic variance-of-Laplacian
// focus metric and, only when the frame scores below threshold, compensates
// with an unsharp mask whose strength grows the softer the capture is.

/** Default sharpness floor: captures scoring under this get sharpened. */
const val DEFAULT_BLUR_VARIANCE_THRESHOLD = 120.0

private const val UNSHARP_SIGMA = 1.8
private const val MIN_SHARPEN_AMOUNT = 0.45
private const val MAX_SHARPEN_AMOUNT = 1.0

/**
 * Estimates image sharpness as the variance of the Laplacian (higher value =
 * sharper content). Works on grayscale and color inputs alike.
 */
fun estimateBlurVariance(mat: Mat): Double {
    val gray = if (mat.channels() == 1) mat else {
        val converted = Mat()
        Imgproc.cvtColor(mat, converted, Imgproc.COLOR_BGR2GRAY)
        converted
    }
    val laplacian = Mat()
    return try {
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
        val mean = MatOfDouble()
        val stdDev = MatOfDouble()
        Core.meanStdDev(laplacian, mean, stdDev)
        val deviation = stdDev.get(0, 0)[0]
        deviation * deviation
    } finally {
        laplacian.release()
        if (gray !== mat) gray.release()
    }
}

/**
 * Corrects soft captures: returns [bgr] untouched when it is sharp enough
 * (same instance, zero cost); otherwise applies an unsharp mask whose
 * amount scales from [MIN_SHARPEN_AMOUNT] up to [MAX_SHARPEN_AMOUNT]
 * depending on how far below [blurVarianceThreshold] the capture scored.
 */
fun correctBlur(
    bgr: Mat,
    blurVarianceThreshold: Double = DEFAULT_BLUR_VARIANCE_THRESHOLD,
): Mat {
    val score = estimateBlurVariance(bgr)
    if (score >= blurVarianceThreshold) return bgr

    val severity = ((blurVarianceThreshold - score) / blurVarianceThreshold)
        .coerceIn(0.0, 1.0)
    val amount = MIN_SHARPEN_AMOUNT + (MAX_SHARPEN_AMOUNT - MIN_SHARPEN_AMOUNT) * severity

    val blurred = Mat()
    Imgproc.GaussianBlur(bgr, blurred, Size(0.0, 0.0), UNSHARP_SIGMA)
    val sharpened = Mat()
    Core.addWeighted(bgr, 1.0 + amount, blurred, -amount, 0.0, sharpened)
    blurred.release()
    return sharpened
}

fun enhanceCapturedImage(img: Mat, colorMode: ColorMode): Mat {
    return when (colorMode) {
        ColorMode.COLOR -> multiScaleRetinexOnL(img)
        ColorMode.GRAYSCALE -> enhanceGrayscaleImage(img)
    }
}

fun multiScaleRetinexOnL(bgr: Mat): Mat {

    // --- 1. BGR -> Lab ---
    val lab = Mat()
    Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)

    val labChannels = ArrayList<Mat>(3)
    Core.split(lab, labChannels)

    val l = labChannels[0] // CV_8U [0..255]

    // --- 2. Prepare L (float) ---
    val lFloat = Mat()
    l.convertTo(lFloat, CvType.CV_32F)
    Core.add(lFloat, Scalar(1.0), lFloat)

    val scaleFactor = 2.0
    val smallSize = Size(
        lFloat.cols() / scaleFactor,
        lFloat.rows() / scaleFactor
    )

    val lSmall = Mat()
    Imgproc.resize(lFloat, lSmall, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)

    // --- 3. log(L) once ---
    val logLSmall = Mat()
    Core.log(lSmall, logLSmall)

    val maxDimSmall = max(smallSize.width, smallSize.height)
    val kernelSizes = listOf(
        maxDimSmall / 80.0,
        maxDimSmall / 10.0,
        maxDimSmall / 2.0,
    )

    val weight = 1.0 / kernelSizes.size
    val retinexSmall = Mat.zeros(lSmall.size(), CvType.CV_32F)

    val blurLog = Mat()
    val diff = Mat()

    for (ks in kernelSizes) {
        val k = ks.toInt().coerceAtLeast(3) or 1

        Imgproc.boxFilter(
            logLSmall,
            blurLog,
            -1,
            Size(k.toDouble(), k.toDouble())
        )

        Core.subtract(logLSmall, blurLog, diff)
        Core.addWeighted(retinexSmall, 1.0, diff, weight, 0.0, retinexSmall)
    }

    // --- 4. Normalize Retinex (relative [0..1]) ---
    val minMax = Core.minMaxLoc(retinexSmall)
    val retinexNormSmall = Mat()
    Core.subtract(retinexSmall, Scalar(minMax.minVal), retinexNormSmall)

    val range = minMax.maxVal - minMax.minVal
    if (range > 1e-6) {
        Core.multiply(retinexNormSmall, Scalar(1.0 / range), retinexNormSmall)
    }

    // --- Upscale Retinex back to full resolution ---
    val retinexNorm = Mat()
    Imgproc.resize(
        retinexNormSmall,
        retinexNorm,
        lFloat.size(),
        0.0,
        0.0,
        Imgproc.INTER_CUBIC
    )

    // --- 5. Re-center around original luminance ---
    val lOriginalFloat = Mat()
    l.convertTo(lOriginalFloat, CvType.CV_32F)

    val meanL = Core.mean(lOriginalFloat).`val`[0]
    val amplitude = 60.0

    val correctedL = Mat()
    Core.multiply(retinexNorm, Scalar(amplitude), correctedL)
    Core.add(correctedL, Scalar(meanL - amplitude / 2.0), correctedL)

    // --- 6. Blend with original L ---
    val alpha = 0.6
    Core.addWeighted(
        lOriginalFloat, 1.0 - alpha,
        correctedL, alpha,
        0.0,
        correctedL
    )

    // --- 7. Restore contrast ---
    val pLowOrig = percentileL(lOriginalFloat, 0.001)
    val pLow = percentileL(correctedL, 0.001)
    val pHigh = percentileL(correctedL, 0.995)

    val targetLow = min(pLow, pLowOrig)
    val targetHigh = 245.0
    val scale = (targetHigh - targetLow) / (pHigh - pLow + 1e-6)

    Core.subtract(correctedL, Scalar(pLow), correctedL)
    Core.multiply(correctedL, Scalar(scale), correctedL)
    Core.add(correctedL, Scalar(targetLow), correctedL)

    // --- 8. Clamp and write back ---
    Core.min(correctedL, Scalar(255.0), correctedL)
    Core.max(correctedL, Scalar(0.0), correctedL)

    correctedL.convertTo(labChannels[0], CvType.CV_8U)

    // --- 9. Lab -> BGR ---
    Core.merge(labChannels, lab)
    val result = Mat()
    Imgproc.cvtColor(lab, result, Imgproc.COLOR_Lab2BGR)

    // --- Cleanup ---
    lab.release()
    lFloat.release()
    lSmall.release()
    logLSmall.release()
    blurLog.release()
    diff.release()
    retinexSmall.release()
    retinexNormSmall.release()
    retinexNorm.release()
    lOriginalFloat.release()
    correctedL.release()
    labChannels.forEach { it.release() }

    return result
}

fun percentileL(l: Mat, p: Double): Double {
    val hist = Mat()
    try {
        Imgproc.calcHist(
            listOf(l),
            MatOfInt(0),
            Mat(),
            hist,
            MatOfInt(256),
            MatOfFloat(0f, 256f)
        )

        val total = l.total().toDouble()
        if (total <= 0.0 || hist.empty()) return 255.0

        // Read all bins in a single bulk call. calcHist always produces a
        // CV_32F histogram, so the float[] overload is safe. The per-element
        // Mat.get(row, col) variant is avoided on purpose: its JNI binding
        // silently returns null whenever OpenCV considers (row, col) out of
        // bounds or the Mat carries no data (e.g. an empty histogram after a
        // failed native allocation), which would throw an NPE here.
        val bins = minOf(256, hist.rows() * hist.cols()).coerceAtLeast(1)
        val counts = FloatArray(bins)
        val readCount = hist.get(0, 0, counts)

        var sum = 0.0
        for (i in 0 until bins) {
            if (readCount > 0) sum += counts[i].toDouble()
            if (sum / total >= p) return i.toDouble()
        }
        return 255.0
    } finally {
        hist.release()
    }
}

/**
 * Safely reads a single CV_32F element of [mat]. Returns null when the value
 * cannot be retrieved (empty Mat, out-of-bounds index or depth mismatch).
 *
 * Uses the bulk Mat.get(row, col, float[]) variant on purpose: the
 * single-element Mat.get(row, col) binding silently returns a null array under
 * exactly those conditions, which crashes with "Attempt to read from null
 * array" at the [0] indexing site.
 */
private fun getFloat(mat: Mat, row: Int, col: Int): Double? {
    return runCatching {
        val buf = FloatArray(1)
        if (mat.get(row, col, buf) > 0) buf[0].toDouble() else null
    }.getOrNull()
}

fun enhanceGrayscaleImage(img: Mat): Mat {

    // -- 1. Convert to grayscale --------
    val gray = Mat()
    when (img.channels()) {
        4 -> Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGRA2GRAY)
        3 -> Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        else -> img.copyTo(gray)
    }

    // -- 2. Multi-scale Retinex ---------
    val maxDim = max(gray.cols(), gray.rows()).toDouble()

    val imgFloat = Mat()
    gray.convertTo(imgFloat, CvType.CV_32F)
    Core.add(imgFloat, Scalar(1.0), imgFloat)

    val logImg = Mat()
    Core.log(imgFloat, logImg)

    val kernelSizes = listOf(maxDim / 6, maxDim / 50)
    val weight = 1.0 / kernelSizes.size
    val retinex = Mat.zeros(gray.size(), CvType.CV_32F)
    val blur = Mat()
    val logBlur = Mat()
    val diff = Mat()

    for (kernelSize in kernelSizes) {
        Imgproc.boxFilter(imgFloat, blur, -1, Size(kernelSize, kernelSize))
        Core.add(blur, Scalar(1.0), blur)
        Core.log(blur, logBlur)
        Core.subtract(logImg, logBlur, diff)
        val diffGray = Mat()
        if (diff.channels() > 1) {
            Imgproc.cvtColor(diff, diffGray, Imgproc.COLOR_BGRA2GRAY)
        } else {
            diff.copyTo(diffGray)
        }
        Core.addWeighted(retinex, 1.0, diffGray, weight, 0.0, retinex)
        diffGray.release()
    }

    // -- 3. exp() + p1/p99 normalization ---------
    // exp() compensates for the compression of bright tones caused by
    // the Retinex log-space computation, making annotations and light
    // gray areas more visible.
    val retinexExp = Mat()
    Core.exp(retinex, retinexExp)

    val flat = Mat()
    retinexExp.reshape(1, 1).copyTo(flat)
    val sorted = Mat()
    Core.sort(flat, sorted, Core.SORT_ASCENDING)
    val n = sorted.cols()
    val pLow = getFloat(sorted, 0, (n * 0.004).toInt()) ?: 0.0
    val pHigh = getFloat(sorted, 0, (n * 0.99).toInt()) ?: 255.0
    flat.release(); sorted.release()

    val normalized = Mat()
    Core.subtract(retinexExp, Scalar(pLow), normalized)
    val scale = if (pHigh > pLow) 255.0 / (pHigh - pLow) else 1.0
    Core.multiply(normalized, Scalar(scale), normalized)
    Core.min(normalized, Scalar(255.0), normalized)
    Core.max(normalized, Scalar(0.0), normalized)
    retinexExp.release()

    val result8u = Mat()
    normalized.convertTo(result8u, CvType.CV_8U)
    normalized.release()

    // -- 4. Stretch toward white --------
    // Find the histogram mode in [180..255] as an estimate of the background level,
    // then stretch so that level maps to 255.
    // If modeVal >= 254, Retinex has over-amplified the image (typically happens
    // when the document contains large dark areas). In that case, fall back to
    // a simple normalization of the original grayscale image.
    val hist = Mat()
    Imgproc.calcHist(
        listOf(result8u), MatOfInt(0), Mat(), hist,
        MatOfInt(256), MatOfFloat(0f, 256f)
    )

    var modeVal = 220
    var modeCount = 0.0
    if (!hist.empty()) {
        // Bulk-read all bins in one call; per-element Mat.get(row, col) can
        // return a null array and crash the loop (see getFloat doc).
        val bins = minOf(256, hist.rows() * hist.cols())
        val counts = FloatArray(bins.coerceAtLeast(1))
        if (hist.get(0, 0, counts) > 0) {
            for (i in minOf(180, bins - 1) until bins) {
                val c = counts[i].toDouble()
                if (c > modeCount) {
                    modeCount = c
                    modeVal = i
                }
            }
        }
    }
    hist.release()

    val stretched8u = Mat()

    if (modeVal >= 254) {
        val grayF = Mat()
        gray.convertTo(grayF, CvType.CV_32F)
        val grayFlat = Mat()
        grayF.reshape(1, 1).copyTo(grayFlat)
        val graySorted = Mat()
        Core.sort(grayFlat, graySorted, Core.SORT_ASCENDING)
        val gN = graySorted.cols()
        val gLow = getFloat(graySorted, 0, (gN * 0.01).toInt()) ?: 0.0
        val gHigh = getFloat(graySorted, 0, (gN * 0.99).toInt()) ?: 255.0
        grayFlat.release(); graySorted.release()
        Core.subtract(grayF, Scalar(gLow), grayF)
        Core.multiply(grayF, Scalar(255.0 / (gHigh - gLow + 1e-6)), grayF)
        Core.min(grayF, Scalar(255.0), grayF)
        Core.max(grayF, Scalar(0.0), grayF)
        grayF.convertTo(stretched8u, CvType.CV_8U)
        grayF.release()
    } else {
        val stretchedF = Mat()
        result8u.convertTo(stretchedF, CvType.CV_32F)
        Core.multiply(stretchedF, Scalar(255.0 / modeVal), stretchedF)
        Core.min(stretchedF, Scalar(255.0), stretchedF)
        stretchedF.convertTo(stretched8u, CvType.CV_8U)
        stretchedF.release()
    }

    // -- 5. Bilateral denoising ---------
    // Smooths background texture and fine grain amplified by exp() and stretch,
    // while preserving sharp edges (text, lines, annotations).
    val denoised = Mat()
    Imgproc.bilateralFilter(stretched8u, denoised, 9, 20.0, 10.0)

    val finalBgr = Mat()
    Imgproc.cvtColor(denoised, finalBgr, Imgproc.COLOR_GRAY2BGR)

    // -- Cleanup -----------
    gray.release(); imgFloat.release(); logImg.release()
    blur.release(); logBlur.release(); diff.release()
    retinex.release(); result8u.release()
    stretched8u.release(); denoised.release()

    return finalBgr
}

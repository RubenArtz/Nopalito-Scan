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

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.imgproc.Imgproc
import java.lang.reflect.Method

/**
 * OpenCV 5 moved `goodFeaturesToTrack` from [Imgproc] to
 * `org.opencv.features.Features`. This JVM module compiles against the
 * openpnp OpenCV 4.9 jar (it bundles the desktop natives used by the unit
 * tests), where only the old location exists, while the Android app runs
 * against official OpenCV 5 where the old location is gone — calling
 * `Imgproc.goodFeaturesToTrack` directly crashes with NoSuchMethodError on
 * device (same class of failure getPerspectiveTransform/contourArea had).
 *
 * The call is therefore resolved reflectively at runtime: Features first
 * (Android/OpenCV 5), falling back to Imgproc (JVM/openpnp 4.9). Both expose
 * the identical parameter list below.
 *
 * NOTE: the release build strips unreferenced classes, so the app keeps
 * `org.opencv.features.**` via a ProGuard keep rule.
 */
internal object HarrisCorners {

    private const val METHOD_NAME = "goodFeaturesToTrack"

    private val featuresMethod: Method? by lazy {
        runCatching {
            Class.forName("org.opencv.features.Features").getMethod(
                METHOD_NAME,
                Mat::class.java,
                MatOfPoint::class.java,
                Int::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Mat::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    private val imgprocMethod: Method? by lazy {
        runCatching {
            Imgproc::class.java.getMethod(
                METHOD_NAME,
                Mat::class.java,
                MatOfPoint::class.java,
                Int::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Mat::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    /**
     * `goodFeaturesToTrack(image, corners, maxCorners, qualityLevel,
     * minDistance, mask, blockSize, gradientSize, useHarrisDetector, k)`.
     */
    fun goodFeaturesToTrack(
        image: Mat,
        corners: MatOfPoint,
        maxCorners: Int,
        qualityLevel: Double,
        minDistance: Double,
        mask: Mat,
        blockSize: Int,
        gradientSize: Int,
        useHarrisDetector: Boolean,
        k: Double,
    ) {
        val method = featuresMethod ?: imgprocMethod
        ?: throw IllegalStateException(
            "goodFeaturesToTrack unavailable: neither org.opencv.features.Features nor Imgproc provide it"
        )
        try {
            method.invoke(
                null,
                image,
                corners,
                maxCorners,
                qualityLevel,
                minDistance,
                mask,
                blockSize,
                gradientSize,
                useHarrisDetector,
                k,
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }
}

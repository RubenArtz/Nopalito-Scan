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

import kotlin.math.abs
import kotlin.math.max

/** How the (rotated) analysis frame is scaled onto the preview surface. */
enum class PreviewScaleType {
    /**
     * Uniform scale so the frame covers the whole preview, cropping the
     * overflow (matches PreviewView.ScaleType.FILL_CENTER).
     */
    FILL_CENTER,
}

/**
 * Maps a document quad detected in segmentation-mask coordinates to
 * PreviewView coordinates.
 *
 * The segmentation mask may have a different aspect ratio than the analysis
 * frame (the model used by this project outputs a square mask for a 4:3
 * frame), so the mask is first un-squished to analysis-frame coordinates with
 * independent x/y scales. The quad is then rotated to display orientation
 * with [rotationDegrees] and finally the (rotated) analysis frame is
 * center-cropped onto the preview rectangle.
 *
 * @param quad quad corners in mask coordinates, sensor orientation.
 * @param maskSize segmentation mask dimensions (e.g. 256x256).
 * @param analysisSize analysis frame dimensions in sensor orientation
 *   (e.g. 640x480).
 * @param previewSize the PreviewView/canvas size in pixels.
 * @param rotationDegrees ImageProxy rotation (0, 90, 180 or 270).
 */
fun mapAnalysisQuadToPreview(
    quad: Quad,
    maskSize: ImageSize,
    analysisSize: ImageSize,
    previewSize: ImageSize,
    rotationDegrees: Int,
    scaleType: PreviewScaleType = PreviewScaleType.FILL_CENTER,
): Quad {
    // 1) Un-squish: mask -> analysis frame (independent per-axis scales).
    val inFrame = quad.scaledTo(
        maskSize.width, maskSize.height,
        analysisSize.width, analysisSize.height,
    )

    // 2) Rotate to display orientation. rotate90 expects the frame dims of
    // the coordinate space it transforms.
    val rotated = inFrame.rotate90(rotationDegrees / 90, analysisSize)

    // The rotated frame is (w x h) or (h x w) depending on the rotation.
    val rotatedFrameSize =
        if ((rotationDegrees / 90) % 2 != 0) ImageSize(analysisSize.height, analysisSize.width)
        else analysisSize

    return when (scaleType) {
        PreviewScaleType.FILL_CENTER -> {
            val scale = max(
                previewSize.width / rotatedFrameSize.width,
                previewSize.height / rotatedFrameSize.height,
            )
            val offsetX = (previewSize.width - rotatedFrameSize.width * scale) / 2.0
            val offsetY = (previewSize.height - rotatedFrameSize.height * scale) / 2.0
            fun map(p: Point) = Point(p.x * scale + offsetX, p.y * scale + offsetY)
            Quad(
                map(rotated.topLeft),
                map(rotated.topRight),
                map(rotated.bottomRight),
                map(rotated.bottomLeft),
            )
        }
    }
}

/**
 * Maps a single point detected in segmentation-mask coordinates to
 * PreviewView coordinates, with the exact same un-squish -> rotate ->
 * center-crop chain as [mapAnalysisQuadToPreview].
 */
fun mapAnalysisPointToPreview(
    point: Point,
    maskSize: ImageSize,
    analysisSize: ImageSize,
    previewSize: ImageSize,
    rotationDegrees: Int,
    scaleType: PreviewScaleType = PreviewScaleType.FILL_CENTER,
): Point {
    // 1) Un-squish: mask -> analysis frame (independent per-axis scales).
    val inFrame = Point(
        point.x * analysisSize.width / maskSize.width,
        point.y * analysisSize.height / maskSize.height,
    )

    // 2) Rotate to display orientation (same math as Quad.rotate90).
    val iterations = rotationDegrees / 90
    val rotated = when (iterations % 4) {
        1 -> Point(analysisSize.height - inFrame.y, inFrame.x)
        2 -> Point(analysisSize.width - inFrame.x, analysisSize.height - inFrame.y)
        3 -> Point(inFrame.y, analysisSize.width - inFrame.x)
        else -> inFrame
    }

    val rotatedFrameSize =
        if ((rotationDegrees / 90) % 2 != 0) ImageSize(analysisSize.height, analysisSize.width)
        else analysisSize

    return when (scaleType) {
        PreviewScaleType.FILL_CENTER -> {
            val scale = max(
                previewSize.width / rotatedFrameSize.width,
                previewSize.height / rotatedFrameSize.height,
            )
            val offsetX = (previewSize.width - rotatedFrameSize.width * scale) / 2.0
            val offsetY = (previewSize.height - rotatedFrameSize.height * scale) / 2.0
            Point(rotated.x * scale + offsetX, rotated.y * scale + offsetY)
        }
    }
}

/**
 * Returns `true` when [quad] (mask coordinates) maps onto a rectangle inside
 * the frame defined by [frameLeft]/[frameTop]/[frameRight]/[frameBottom] with
 * its center inside the frame and an on-screen area of at least
 * [minCoverageFraction] of the frame area.
 *
 * Used by the ID guide overlay to light up only when the tracked card is
 * actually placed inside the guide frame.
 */
fun isQuadAlignedWithFrame(
    quad: Quad,
    maskSize: ImageSize,
    analysisSize: ImageSize,
    rotationDegrees: Int,
    previewSize: ImageSize,
    frameLeft: Double,
    frameTop: Double,
    frameRight: Double,
    frameBottom: Double,
    minCoverageFraction: Double = 0.2,
): Boolean {
    val mapped = mapAnalysisQuadToPreview(quad, maskSize, analysisSize, previewSize, rotationDegrees)
    val corners = listOf(mapped.topLeft, mapped.topRight, mapped.bottomRight, mapped.bottomLeft)
    val centerX = corners.sumOf { it.x } / 4.0
    val centerY = corners.sumOf { it.y } / 4.0
    if (centerX < frameLeft || centerX > frameRight) return false
    if (centerY < frameTop || centerY > frameBottom) return false
    val frameArea = (frameRight - frameLeft) * (frameBottom - frameTop)
    if (frameArea <= 0.0) return false
    return quadArea(corners) >= frameArea * minCoverageFraction
}

/** Shoelace area of a convex quad in arbitrary coordinates. */
private fun quadArea(corners: List<Point>): Double {
    var twice = 0.0
    for (i in corners.indices) {
        val a = corners[i]
        val b = corners[(i + 1) % corners.size]
        twice += a.x * b.y - b.x * a.y
    }
    return abs(twice) / 2.0
}
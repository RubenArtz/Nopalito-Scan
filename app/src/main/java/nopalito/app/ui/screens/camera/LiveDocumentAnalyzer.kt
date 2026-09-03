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

package nopalito.app.ui.screens.camera

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.runBlocking
import nopalito.app.BuildConfig
import nopalito.app.domain.ImageSegmentationService
import nopalito.app.ui.screens.camera.LiveDocumentAnalyzer.Companion.FULL_DETECTION_EVERY_FRAMES
import nopalito.imageprocessing.FullDetectionReason
import nopalito.imageprocessing.ImageSize
import nopalito.imageprocessing.Mode
import nopalito.imageprocessing.OpticalFlowQuadTracker
import nopalito.imageprocessing.PartialShapeDetector
import nopalito.imageprocessing.Quad
import nopalito.imageprocessing.TrackMode
import nopalito.imageprocessing.TrackUpdate
import nopalito.imageprocessing.detectDocumentQuad
import nopalito.imageprocessing.scaledTo
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.ArrayDeque

/**
 * Per-frame live document analysis pipeline.
 *
 * Designed to run synchronously on the dedicated ImageAnalysis executor so
 * that CameraX `STRATEGY_KEEP_ONLY_LATEST` actually coalesces frames: while a
 * frame is being processed, older queued frames are dropped and only the
 * newest one is delivered next. The [ImageProxy] is always closed in a
 * `finally` block.
 *
 * Two paths:
 * - full detection (YUV -> Bitmap -> LiteRT segmentation -> contour quad)
 *   only on acquisition, after tracking loss, on rotation changes and every
 *   [FULL_DETECTION_EVERY_FRAMES] frames as a drift guard;
 * - in between, the four corners are tracked with Lucas-Kanade optical flow
 *   directly on the Y plane (no Bitmap conversion, no inference).
 *
 * Diagnostics (per-frame time, mode, tracking error, fps, restart reasons)
 * are aggregated and logged at most once per second, only in debug builds.
 */
class LiveDocumentAnalyzer(
    private val segmentationService: ImageSegmentationService,
) {

    companion object {
        private const val TAG = "LiveAnalysis"
        private const val FULL_DETECTION_EVERY_FRAMES = 10
        private const val LOG_INTERVAL_MS = 1000L
    }

    private val tracker =
        OpticalFlowQuadTracker(fullDetectionEveryFrames = FULL_DETECTION_EVERY_FRAMES)

    /** Progressive corner feedback while no full quad is available. */
    private val partialShapeDetector = PartialShapeDetector()

    private var grayMat = Mat()
    private var grayRow = ByteArray(0)

    private var lastMaskSize: ImageSize? = null
    private var lastRotationDegrees: Int? = null
    private var lastBinaryMaskProvider: () -> Bitmap? = { null }

    private var frameCount = 0L
    private var totalAnalysisMs = 0L
    private var lastAnalysisTimeMs = 0L
    private var lastMode = TrackMode.FULL_DETECTION
    private var lastTrackingError = 0f
    private var lastFps = 0f
    private var lastLogMs = 0L
    private var restartCount = 0
    private var lastRestartReason: String? = null
    private var fpsWindow = ArrayDeque<Long>()
    private var frameWidth = 0
    private var frameHeight = 0

    fun reset() {
        tracker.reset()
        lastMaskSize = null
        lastRotationDegrees = null
        lastBinaryMaskProvider = { null }
        frameCount = 0L
        totalAnalysisMs = 0L
        lastAnalysisTimeMs = 0L
        lastFps = 0f
        fpsWindow.clear()
    }

    fun release() {
        tracker.release()
        grayMat.release()
    }

    /** Processes one frame; closes [imageProxy] in all cases. */
    fun analyzeFrame(imageProxy: ImageProxy): LiveAnalysisState {
        val start = SystemClock.uptimeMillis()
        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (lastRotationDegrees != null && lastRotationDegrees != rotationDegrees) {
                recordRestart("rotation changed $lastRotationDegrees -> $rotationDegrees")
                tracker.reset()
                lastMaskSize = null
            }
            lastRotationDegrees = rotationDegrees
            frameWidth = imageProxy.width
            frameHeight = imageProxy.height

            val frameGray = grayFromYPlane(imageProxy)

            var inferenceTime = 0L
            var detectionQuadInMask: Quad? = null
            var fullDetectionRan = false

            if (tracker.shouldRunFullDetection()) {
                fullDetectionRan = true

                val result =
                    runBlocking { segmentationService.runSegmentationAndReturn(imageProxy.toBitmap()) }
                inferenceTime = result?.inferenceTime ?: 0L
                val segmentation = result?.segmentation
                if (segmentation != null) {
                    lastMaskSize = segmentation.maskSize()
                    detectionQuadInMask = detectDocumentQuad(
                        segmentation,
                        ImageSize(imageProxy.width, imageProxy.height),
                        Mode.LIVE_ANALYSIS,
                    )
                    lastBinaryMaskProvider = {
                        var mask: Bitmap = segmentation.toBinaryMask()
                        if (rotationDegrees != 0) {
                            mask = rotateBitmap(mask, rotationDegrees.toFloat())
                        }
                        mask
                    }
                }
            }

            val maskSize = lastMaskSize
            val update = if (maskSize != null) {
                tracker.update(
                    frameGray,
                    fullDetectionRan,
                    detectionQuadInMask,
                    maskSize.width,
                    maskSize.height,
                )
            } else {
                TrackUpdate(null, TrackMode.FULL_DETECTION, 0f, 0, FullDetectionReason.INITIAL)
            }

            // Progressive feedback: while the tracker has no quadrilateral,
            // look for partial structure (L-corners, edges) so the overlay can
            // draw whatever part of the document is already visible.
            val partialShape = if (update.quadInMask == null && maskSize != null) {
                runCatching {
                    partialShapeDetector.detect(frameGray)?.scaledTo(
                        frameGray.cols().toDouble(),
                        frameGray.rows().toDouble(),
                        maskSize.width,
                        maskSize.height,
                    )
                }.getOrNull()
            } else {
                null
            }
            // Count real tracker restarts (optical-flow failure or an invalid
            // detection), not plain "no document in view" frames.
            if (update.fullDetectionReason == FullDetectionReason.INVALID_QUAD ||
                (update.mode == TrackMode.OPTICAL_FLOW &&
                        update.fullDetectionReason == FullDetectionReason.TRACK_LOST)
            ) {
                recordRestart("${update.fullDetectionReason}")
            }

            recordFrame(SystemClock.uptimeMillis() - start, update.mode, update.meanTrackingError)

            // The quad is published in sensor-orientation mask coordinates;
            // the overlay applies mapAnalysisQuadToPreview to draw it.
            return LiveAnalysisState(
                inferenceTime = inferenceTime,
                maskSize = maskSize,
                binaryMaskProvider = lastBinaryMaskProvider,
                stableQuad = update.quadInMask,
                partialShape = partialShape,
                analysisFrameSize = ImageSize(imageProxy.width, imageProxy.height),
                rotationDegrees = rotationDegrees,
                analysisTimeMs = lastAnalysisTimeMs,
                detectionMode = update.mode,
                trackingError = update.meanTrackingError,
                analysisFps = lastFps,
            )
        } catch (e: Exception) {
            recordRestart("exception: ${e.message}")
            Log.w(TAG, "Live analysis failed", e)
            return LiveAnalysisState()
        } finally {
            imageProxy.close()
        }
    }

    /** Reuses a single Mat across frames to avoid per-frame allocations. */
    private fun grayFromYPlane(imageProxy: ImageProxy): Mat {
        val yPlane = imageProxy.planes[0]
        val buffer = yPlane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = yPlane.rowStride

        if (grayMat.rows() != height || grayMat.cols() != width) {
            grayMat.release()
            grayMat = Mat(height, width, CvType.CV_8UC1)
        }
        if (grayRow.size < width) {
            grayRow = ByteArray(width)
        }

        buffer.rewind()
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(grayRow, 0, width)
            grayMat.put(row, 0, grayRow)
        }
        return grayMat
    }

    private fun recordFrame(durationMs: Long, mode: TrackMode, trackingError: Float) {
        frameCount++
        totalAnalysisMs += durationMs
        lastAnalysisTimeMs = durationMs
        lastMode = mode
        lastTrackingError = trackingError

        val now = SystemClock.uptimeMillis()
        fpsWindow.addLast(now)
        while (fpsWindow.isNotEmpty() && now - fpsWindow.first > LOG_INTERVAL_MS) {
            fpsWindow.removeFirst()
        }
        lastFps = fpsWindow.size.toFloat()

        if (BuildConfig.DEBUG && now - lastLogMs >= LOG_INTERVAL_MS) {
            lastLogMs = now
            Log.d(
                TAG,
                "fps=%.1f avg=%dms last=%dms mode=%s err=%.2f resolution=${frameWidth}x${frameHeight} mask=${lastMaskSize} rot=${lastRotationDegrees} restarts=$restartCount reason=${lastRestartReason ?: "-"}".format(
                    lastFps,
                    totalAnalysisMs / frameCount,
                    durationMs,
                    lastMode,
                    lastTrackingError,
                )
            )
        }
    }

    private fun recordRestart(reason: String) {
        restartCount++
        lastRestartReason = reason
    }
}

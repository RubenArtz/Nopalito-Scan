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
import android.hardware.camera2.*
import android.util.Log
import android.util.Size
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.scale
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import nopalito.app.R
import nopalito.app.ui.components.CameraPermissionState
import nopalito.imageprocessing.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    captureController: CameraCaptureController,
    onPreviewViewReady: (PreviewView) -> Unit,
    cameraPermission: CameraPermissionState,
    onError: (String, Throwable) -> Unit,
    onImageAnalyzed: ((ImageProxy) -> Unit)? = null,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (!cameraPermission.isGranted) {
            cameraPermission.request()
        }
    }

    val cameraProviderFuture by remember {
        mutableStateOf(ProcessCameraProvider.getInstance(context))
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        onDispose {
            cameraProviderFuture.get().unbindAll()
            analysisExecutor.shutdown()
        }
    }

    var bindState by remember { mutableStateOf<CameraBindState>(CameraBindState.Idle) }
    var retryKey by remember { mutableIntStateOf(0) }
    var previewView: PreviewView? by remember { mutableStateOf(null) }

    when (bindState) {
        is CameraBindState.Error -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.camera_unavailable))
                Spacer(Modifier.height(8.dp))
                Button(onClick = { retryKey++ }) {
                    Text(stringResource(R.string.retry))
                }
            }
        }

        else -> {
            AndroidView(
                modifier = modifier,
                factory = {
                    PreviewView(it).apply {
                        // TextureView forces rendering on Compose UI layer
                        // instead of a separate SurfaceView, allowing the
                        // Canvas overlay to draw on top of the preview correctly.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        // Center-crop fills the whole screen (edge-to-edge) without letterboxing.
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        onPreviewViewReady(this)
                        previewView = this
                    }
                }
            )

            LaunchedEffect(previewView, retryKey) {
                val view = previewView ?: return@LaunchedEffect

                val provider = cameraProviderFuture.get()

                val result = runCatching {
                    bindCameraUseCases(
                        lifecycleOwner,
                        provider,
                        view,
                        captureController,
                        onImageAnalyzed = onImageAnalyzed,
                        analysisExecutor = analysisExecutor,
                    )
                }

                bindState = result.fold(
                    onSuccess = { CameraBindState.Bound },
                    onFailure = {
                        onError("Camera unavailable", it)
                        CameraBindState.Error(it)
                    }
                )
            }
        }
    }

}

@OptIn(ExperimentalCamera2Interop::class)
fun bindCameraUseCases(
    lifecycleOwner: LifecycleOwner,
    cameraProvider: ProcessCameraProvider,
    previewView: PreviewView,
    captureController: CameraCaptureController,
    captureMode: nopalito.app.ui.screens.settings.CaptureMode = nopalito.app.ui.screens.settings.CaptureMode.BATCH,
    onImageAnalyzed: ((ImageProxy) -> Unit)? = null,
    analysisExecutor: java.util.concurrent.Executor? = null,
) {
    cameraProvider.unbindAll()

    val ratio_4_3 = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .build()
    val preview: Preview = Preview.Builder().setResolutionSelector(ratio_4_3).build()
    preview.surfaceProvider = previewView.surfaceProvider

    val cameraSelector: CameraSelector =
        CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

    val imageCaptureMode =
        if (captureMode == nopalito.app.ui.screens.settings.CaptureMode.INDIVIDUAL)
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        else
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY

    val imageCaptureBuilder = ImageCapture.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(4400, 3300),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .setAspectRatioStrategy(
                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                )
                .build()
        )
        .setCaptureMode(imageCaptureMode)

    Camera2Interop.Extender(imageCaptureBuilder)
        .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let {
                    captureController.lastFocusDistanceDiopters = it
                }
            }
        })

    val imageCapture = imageCaptureBuilder.build()
    captureController.imageCapture = imageCapture

    // --- IMAGE ANALYSIS: Connects the detection pipeline ---
    val imageAnalysis = onImageAnalyzed?.let { analyzer ->
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(analysisExecutor ?: Executors.newSingleThreadExecutor()) { imageProxy ->
                    analyzer(imageProxy)
                }
            }
    }

    val useCases = buildList {
        add(preview)
        add(imageCapture)
        imageAnalysis?.let { add(it) }
    }

    val camera = cameraProvider.bindToLifecycle(
        lifecycleOwner,
        cameraSelector,
        *useCases.toTypedArray()
    )
    captureController.cameraControl = camera.cameraControl
    captureController.setCameraCharacteristics(Camera2CameraInfo.from(camera.cameraInfo))
}

@Composable
fun AnalysisOverlay(liveAnalysisState: LiveAnalysisState, debugMode: Boolean) {
    val maskSize = liveAnalysisState.maskSize ?: return
    val targetQuad = liveAnalysisState.stableQuad
    var displayedQuad by remember { mutableStateOf<Quad?>(null) }

    // Smooth animation: lerps toward target quad each frame
    LaunchedEffect(targetQuad) {
        if (targetQuad == null) {
            displayedQuad = null
            return@LaunchedEffect
        }

        // If no quad is currently displayed, use target directly
        if (displayedQuad == null) {
            displayedQuad = targetQuad
        }

        // Animation loop with controlled delays
        while (true) {
            displayedQuad = displayedQuad?.let { current ->
                lerpQuad(current, targetQuad, 0.12f)
            } ?: targetQuad

            // Wait ~16ms for ~60fps
            withInfiniteAnimationFrameMillis { }
        }
    }

    // Debug log
    Log.d(
        "AnalysisOverlay",
        "targetQuad=${liveAnalysisState.stableQuad}, displayedQuad=$displayedQuad"
    )

    val cornerRadius = 12f

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (debugMode) {
            val binaryMask = liveAnalysisState.binaryMaskProvider.invoke()
            binaryMask?.let { drawMask(this, it) }
        }
        displayedQuad?.let { quad ->
            // PreviewView uses FILL_CENTER (center-crop): map the detection quad from the
            // full analysis frame to the visible, uniformly-scaled and centered region.
            val scale = max(size.width / maskSize.width, size.height / maskSize.height)
            val offsetX = (size.width - maskSize.width * scale) / 2.0
            val offsetY = (size.height - maskSize.height * scale) / 2.0
            fun map(p: Point) = Point(p.x * scale + offsetX, p.y * scale + offsetY)
            val scaledQuad = Quad(
                map(quad.topLeft),
                map(quad.topRight),
                map(quad.bottomRight),
                map(quad.bottomLeft)
            )

            // Draw lines in CamScanner style (bright green/blue)
            val lineColor = Color(0xFF0CAD55) // Bright green
            val strokeWidth = 6f

            scaledQuad.edges().forEach { edge ->
                drawLine(
                    color = lineColor,
                    start = edge.from.toOffset(),
                    end = edge.to.toOffset(),
                    strokeWidth = strokeWidth
                )
            }

            // Draw circles at the 4 corners
            val cornerPoints = listOf(
                scaledQuad.topLeft.toOffset(),
                scaledQuad.topRight.toOffset(),
                scaledQuad.bottomRight.toOffset(),
                scaledQuad.bottomLeft.toOffset()
            )
            cornerPoints.forEach { point ->
                drawCircle(
                    color = Color(0xFF0CAD55),
                    radius = cornerRadius,
                    center = point
                )
                drawCircle(
                    color = Color.White,
                    radius = cornerRadius * 0.5f,
                    center = point
                )
            }
        }
    }
}

private fun drawMask(drawScope: DrawScope, binaryMask: Bitmap) {
    val maskOverlay = replaceColor(binaryMask, Color.Black, Color.Transparent)
    val size = drawScope.size
    drawScope.drawImage(
        maskOverlay.scale(size.width.toInt(), size.height.toInt()).asImageBitmap(),
        colorFilter = ColorFilter.tint(Color(0x8000FF00), BlendMode.SrcIn)
    )
}

fun replaceColor(bitmap: Bitmap, toReplace: Color, replacement: Color): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

    val pixels = IntArray(width * height)
    result.getPixels(pixels, 0, width, 0, 0, width, height)

    val target = toReplace.toArgb()
    val newColor = replacement.toArgb()

    for (i in pixels.indices) {
        if (pixels[i] == target) {
            pixels[i] = newColor
        }
    }

    result.setPixels(pixels, 0, width, 0, 0, width, height)
    return result
}

fun Point.toOffset() = Offset(x.toFloat(), y.toFloat())

class CameraCaptureController {
    var cameraControl: CameraControl? = null
    var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()
    var previewView: PreviewView? = null
    var cameraIntrinsics: CameraIntrinsics? = null
    var canUseFocusDistance = false

    @Volatile
    var lastFocusDistanceDiopters: Float? = null

    fun shutdown() {
        executor.shutdown()
    }

    fun takePicture(onImageCaptured: (ImageProxy?, OpticalMeasures?) -> Unit) {
        imageCapture?.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val diopters = lastFocusDistanceDiopters
                    val subjectDistanceInMm =
                        if (canUseFocusDistance && diopters != null && diopters != 0.0f) {
                            1000 / diopters
                        } else {
                            null
                        }
                    onImageCaptured(
                        imageProxy,
                        cameraIntrinsics?.let { OpticalMeasures(it, subjectDistanceInMm) })
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraCapture", "Image capture failed: ${exception.message}", exception)
                    onImageCaptured(null, null)
                }
            }
        )
    }

    fun tapToFocus(tapOffset: Offset) {
        val view = previewView ?: return
        val control = cameraControl ?: return

        val factory = view.meteringPointFactory
        val point = factory.createPoint(tapOffset.x, tapOffset.y)

        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()

        control.startFocusAndMetering(action)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setCameraCharacteristics(cameraInfo: Camera2CameraInfo) {
        val focalLengths = cameraInfo.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
        )
        val sensorSize = cameraInfo.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
        )
        cameraIntrinsics =
            if (focalLengths == null || focalLengths.size != 1 || sensorSize == null) {
                null
            } else {
                cameraIntrinsics(focalLengths[0], max(sensorSize.width, sensorSize.height))
            }
        val calibration = cameraInfo.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION
        )
        canUseFocusDistance =
            calibration == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED
                    || calibration == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE
    }
}

sealed interface CameraBindState {
    object Idle : CameraBindState
    object Bound : CameraBindState
    data class Error(val throwable: Throwable) : CameraBindState
}
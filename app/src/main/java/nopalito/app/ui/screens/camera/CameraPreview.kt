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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.scale
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import nopalito.app.BuildConfig
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
    onCameraBound: (String?) -> Unit = {},
    torchEnabled: Boolean = false,
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

            LaunchedEffect(previewView, retryKey, torchEnabled) {
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
                        onCameraBound = onCameraBound,
                        torchEnabled = torchEnabled,
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
    onImageAnalyzed: ((ImageProxy) -> Unit)? = null,
    analysisExecutor: java.util.concurrent.Executor? = null,
    onCameraBound: (String?) -> Unit = {},
    torchEnabled: Boolean = false,
) {
    cameraProvider.unbindAll()

    // With the torch on, the ultra-wide lens is skipped: the auxiliary back
    // cameras expose no flash unit of their own, so the default back camera
    // is used instead (its torch actually works). Back to 0.6x when off.
    val wideCameraId = if (torchEnabled) null else findUltraWideCameraId(cameraProvider)
    val cameraSelector = if (wideCameraId != null) {
        CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).getCameraId() == wideCameraId }
            }
            .build()
    } else {
        CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
    }

    val ratio_4_3 = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .build()
    val preview: Preview = Preview.Builder().setResolutionSelector(ratio_4_3).build()
    preview.surfaceProvider = previewView.surfaceProvider

    // Photos are always captured in HD: maximum quality and the highest
    // resolution JPEG the device supports (best effort). ImageAnalysis is
    // intentionally untouched: the live pipeline stays at low resolution.
    val imageCaptureBuilder = ImageCapture.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(8160, 6120),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .setAspectRatioStrategy(
                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                )
                .build()
        )
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

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
    captureController.cameraHasFlashUnit = camera.cameraInfo.hasFlashUnit()
    captureController.setCameraCharacteristics(Camera2CameraInfo.from(camera.cameraInfo))
    val cameraLabel = if (wideCameraId != null) "0.6x · HD" else "1x · HD"
    onCameraBound(cameraLabel)
    // Apply the requested torch as soon as the (flash-capable) camera is bound.
    if (torchEnabled && camera.cameraInfo.hasFlashUnit()) {
        camera.cameraControl.enableTorch(true)
    }
    if (BuildConfig.DEBUG) {
        Log.d(
            "Camera",
            "bound camera=${Camera2CameraInfo.from(camera.cameraInfo).getCameraId()} " +
                "label=$cameraLabel flashUnit=${camera.cameraInfo.hasFlashUnit()}"
        )
    }
}

@OptIn(ExperimentalCamera2Interop::class)
private fun findUltraWideCameraId(provider: ProcessCameraProvider): String? {
    val backLenses = provider.availableCameraInfos.mapNotNull { info ->
        val camera2Info = Camera2CameraInfo.from(info)
        val facing = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
            val focalLengths = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )
            val minFocal = focalLengths?.minOrNull()
            if (minFocal != null) {
                LensSpec(camera2Info.getCameraId(), minFocal.toDouble())
            } else {
                null
            }
        } else {
            null
        }
    }
    if (BuildConfig.DEBUG) {
        Log.d(
            "Camera",
            "back lenses: ${backLenses.joinToString { "${it.id}:${it.minFocalLengthMm}mm" }}"
        )
    }
    return pickUltraWideLens(backLenses)?.id
}

/**
 * Draws the live document quadrilateral on top of the preview.
 *
 * Two separate states keep the drawing independent from the analysis thread:
 * [LiveAnalysisState.stableQuad] is the raw tracked/detected target in
 * sensor-orientation mask coordinates (written by the analyzer), while the
 * displayed quad is integrated with a damped spring here on the UI side
 * (animation frame clock) so the overlay follows the document smoothly
 * without vibrating or jumping. Fade-in/fade-out is driven by the same loop,
 * so appearing/disappearing is always animated.
 *
 * The mask -> PreviewView transform uses [mapAnalysisQuadToPreview], which
 * un-squishes the segmentation mask to the analysis frame (the mask may have
 * a different aspect ratio than the frame), applies the sensor rotation and
 * center-crops onto the canvas.
 *
 * In debug mode three quads are drawn to isolate geometry problems:
 * - blue: raw detection mapped WITHOUT rotation;
 * - orange: mapped target (pre-spring);
 * - green: the spring-smoothed quad actually displayed.
 */
@Composable
fun AnalysisOverlay(
    liveAnalysisState: LiveAnalysisState,
    debugMode: Boolean,
    ineMode: Boolean = false,
) {
    val maskSize = liveAnalysisState.maskSize ?: return
    val frameSize = liveAnalysisState.analysisFrameSize ?: maskSize
    val targetQuad = liveAnalysisState.stableQuad
    val rotationDegrees = liveAnalysisState.rotationDegrees
    val currentTarget by rememberUpdatedState(targetQuad)
    // Progressive partial geometry (L-corners / edges) while no full quad.
    val currentPartial by rememberUpdatedState(liveAnalysisState.partialShape)
    val spring = remember(rotationDegrees) { QuadSpring() }
    var displayedQuad by remember { mutableStateOf<Quad?>(null) }
    var displayedPartial by remember { mutableStateOf<PartialShape?>(null) }
    var fade by remember { mutableFloatStateOf(0f) }
    var partialFade by remember { mutableFloatStateOf(0f) }
    var lastFrameMillis by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            withInfiniteAnimationFrameMillis { frameMillis ->
                val deltaMillis =
                    if (lastFrameMillis == 0L) 16L else (frameMillis - lastFrameMillis).coerceIn(0L, 50L)
                lastFrameMillis = frameMillis
                val dtSeconds = deltaMillis / 1000f

                val target = currentTarget
                if (target != null) {
                    displayedQuad =
                        spring.update(target, dtSeconds, maskSize.width, maskSize.height)
                    fade = (fade + (1f - fade) * (dtSeconds * 10f)).coerceAtMost(1f)
                    displayedPartial = null
                    partialFade = (partialFade - dtSeconds * 8f).coerceAtLeast(0f)
                } else {
                    displayedQuad = spring.update(null, dtSeconds, maskSize.width, maskSize.height)
                    fade = (fade * (1f - dtSeconds * 8f)).coerceAtLeast(0f)
                    val partial = currentPartial
                    if (partial != null) displayedPartial = partial
                    partialFade =
                        if (displayedPartial != null && currentPartial != null) {
                            (partialFade + dtSeconds * 10f).coerceAtMost(1f)
                        } else {
                            (partialFade - dtSeconds * 6f).coerceAtLeast(0f)
                        }
                }
            }
        }
    }

    val density = LocalDensity.current
    val strokeWidth = with(density) { 2.5.dp.toPx() }
    val green = Color(0xFF0CAD55)

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (debugMode) {
            liveAnalysisState.binaryMaskProvider.invoke()?.let { drawMask(this, it) }
        }
        // In ID (INE) mode the detection pipeline keeps running (auto-capture,
        // tracking) but the green quad is not drawn: the guide frame is the
        // only visual the user should see.
        if (ineMode) return@Canvas
        val quad = displayedQuad ?: return@Canvas
        if (fade <= 0.01f) return@Canvas

        val previewSize = ImageSize(size.width.toDouble(), size.height.toDouble())
        fun map(maskQuad: Quad, rotation: Int) =
            mapAnalysisQuadToPreview(maskQuad, maskSize, frameSize, previewSize, rotation)

        val finalQuad = map(quad, rotationDegrees)

        val path = Path().apply {
            moveTo(finalQuad.topLeft.x.toFloat(), finalQuad.topLeft.y.toFloat())
            lineTo(finalQuad.topRight.x.toFloat(), finalQuad.topRight.y.toFloat())
            lineTo(finalQuad.bottomRight.x.toFloat(), finalQuad.bottomRight.y.toFloat())
            lineTo(finalQuad.bottomLeft.x.toFloat(), finalQuad.bottomLeft.y.toFloat())
            close()
        }

        if (debugMode) {
            // Blue: raw quad mapped without rotation; orange: mapped target
            // (pre-spring). Green (above) is the spring-smoothed quad.
            val raw = liveAnalysisState.stableQuad
            if (raw != null) {
                drawQuadOutline(this, map(raw, 0), Color(0xFF2196F3).copy(alpha = 0.9f), strokeWidth)
                drawQuadOutline(this, map(raw, rotationDegrees), Color(0xFFFF9800).copy(alpha = 0.9f), strokeWidth)
            }
        }

        // Translucent green fill (~18% opacity).
        drawPath(path, green.copy(alpha = 0.18f * fade))

        // Bright green border with rounded corners and joins.
        drawPath(
            path,
            green.copy(alpha = 0.95f * fade),
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.cornerPathEffect(16f),
            )
        )

        // Small anchor dots at the four corners, CamScanner-style.
        val dotRadius = strokeWidth * 0.8f
        listOf(
            finalQuad.topLeft,
            finalQuad.topRight,
            finalQuad.bottomRight,
            finalQuad.bottomLeft
        ).forEach { corner ->
            drawCircle(
                color = Color.White.copy(alpha = 0.95f * fade),
                radius = dotRadius,
                center = corner.toOffset()
            )
            drawCircle(
                color = green.copy(alpha = 0.95f * fade),
                radius = dotRadius + strokeWidth * 0.5f,
                center = corner.toOffset(),
                style = Stroke(width = strokeWidth * 0.45f)
            )
        }

        // --- Progressive partial shape (yellow) --------------------------------
        // While fewer than four corners are recognized, draw whatever part of
        // the document is already visible: an isolated L-corner with its arms,
        // the edge between two corners, or the open lines of three corners.
        val partial = displayedPartial
        if (partial != null && partialFade > 0.01f) {
            val yellow = Color(0xFFFFC107)
            val outlineAlpha = 0.95f * partialFade
            val mappedCorners = partial.corners.map { corner ->
                mapAnalysisPointToPreview(
                    point = corner,
                    maskSize = maskSize,
                    analysisSize = frameSize,
                    previewSize = previewSize,
                    rotationDegrees = rotationDegrees,
                ).toOffset()
            }
            fun armOffset(p: nopalito.imageprocessing.Point) =
                mapAnalysisPointToPreview(
                    point = p,
                    maskSize = maskSize,
                    analysisSize = frameSize,
                    previewSize = previewSize,
                    rotationDegrees = rotationDegrees,
                ).toOffset()

            for ((i, j) in partial.linkedEdges) {
                val start = mappedCorners.getOrNull(i) ?: continue
                val end = mappedCorners.getOrNull(j) ?: continue
                drawLine(
                    color = yellow.copy(alpha = outlineAlpha),
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            for ((start, end) in partial.openArms) {
                drawLine(
                    color = yellow.copy(alpha = 0.8f * partialFade),
                    start = armOffset(start),
                    end = armOffset(end),
                    strokeWidth = strokeWidth * 0.85f,
                    cap = StrokeCap.Round,
                )
            }
            mappedCorners.forEach { corner ->
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f * partialFade),
                    radius = dotRadius,
                    center = corner,
                )
                drawCircle(
                    color = yellow.copy(alpha = 0.95f * partialFade),
                    radius = dotRadius + strokeWidth * 0.5f,
                    center = corner,
                    style = Stroke(width = strokeWidth * 0.45f)
                )
            }
        }
    }
}

private fun drawQuadOutline(drawScope: DrawScope, quad: Quad, color: Color, strokeWidth: Float) {
    val path = Path().apply {
        moveTo(quad.topLeft.x.toFloat(), quad.topLeft.y.toFloat())
        lineTo(quad.topRight.x.toFloat(), quad.topRight.y.toFloat())
        lineTo(quad.bottomRight.x.toFloat(), quad.bottomRight.y.toFloat())
        lineTo(quad.bottomLeft.x.toFloat(), quad.bottomLeft.y.toFloat())
        close()
    }
    drawScope.drawPath(path, color, style = Stroke(width = strokeWidth * 0.7f))
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
    // Snapshot state so composables observing it re-run (e.g. the torch
    // LaunchedEffect) when a (re)bind swaps the camera control.
    var cameraControl: CameraControl? by mutableStateOf(null)
    var imageCapture: ImageCapture? = null
    var cameraHasFlashUnit: Boolean = false
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
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

package nopalito.app.ui.screens.cloud.screens

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import nopalito.app.R
import nopalito.app.ui.components.rememberCameraPermissionState
import java.util.concurrent.Executors

private const val TAG = "PcLinkQrScanner"

/** Minimum gap between two delivered detections (debounce for shaky hands). */
private const val DETECTION_COOLDOWN_MS = 2000L

/**
 * Live camera preview that reports the raw content of the first QR code in
 * frame (QR format only). Frames are dropped while [paused] so an approval in
 * flight never triggers a second one.
 */
@Composable
fun PcLinkQrScanner(
    paused: Boolean,
    onQrDetected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermission = rememberCameraPermissionState()

    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    DisposableEffect(Unit) {
        onDispose { scanner.close() }
    }

    var lastDeliveredAt by remember { mutableLongStateOf(0L) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    // The analyzer lambda outlives recompositions: always read the latest
    // paused flag instead of the value captured at bind time.
    val pausedNow = rememberUpdatedState(paused)

    // Resume the OS permission state on every foreground (granted in Settings).
    LifecycleResumeEffect(Unit) {
        cameraPermission.refresh()
        onPauseOrDispose { }
    }

    if (!cameraPermission.isGranted) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.cloud_link_camera_required),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { cameraPermission.request() }) {
                Text(stringResource(R.string.cloud_link_retry))
            }
            Spacer(Modifier.weight(1f))
        }
        return
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // Bind Preview + QR analysis once the PreviewView exists. Rebinding on
    // retryKey recreates a failed bind; unbind on dispose releases the camera.
    DisposableEffect(lifecycleOwner, previewView, retryKey) {
        val view = previewView
        if (view == null) {
            return@DisposableEffect onDispose { }
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            try {
                val provider = providerFuture.get()
                provider.unbindAll()
                val preview = Preview.Builder().build()
                preview.surfaceProvider = view.surfaceProvider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    analyzeLinkQrFrame(
                        imageProxy, scanner, pausedNow.value, lastDeliveredAt,
                        onDelivered = {
                            lastDeliveredAt = System.currentTimeMillis()
                            onQrDetected(it)
                        }
                    )
                }
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                cameraError = null
            } catch (e: Exception) {
                Log.w(TAG, "camera bind failed", e)
                cameraError = e.message
            }
        }
        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            try {
                if (providerFuture.isDone) providerFuture.get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (cameraError != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.cloud_link_camera_required),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { retryKey++ }) {
                    Text(stringResource(R.string.cloud_link_retry))
                }
                Spacer(Modifier.weight(1f))
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView = this
                    }
                }
            )
        }
    }
}

private fun analyzeLinkQrFrame(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    paused: Boolean,
    lastDeliveredAt: Long,
    onDelivered: (String) -> Unit
) {
    if (paused || System.currentTimeMillis() - lastDeliveredAt < DETECTION_COOLDOWN_MS) {
        imageProxy.close()
        return
    }
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            val content = barcodes.firstOrNull()?.rawValue
            if (!content.isNullOrBlank()) onDelivered(content)
        }
        .addOnFailureListener { e ->
            Log.d(TAG, "qr frame ignored: ${e.message}")
        }
        .addOnCompleteListener { imageProxy.close() }
}

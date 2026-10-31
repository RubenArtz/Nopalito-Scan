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

package nopalito.app.ui.screens.qr

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import nopalito.app.ui.screens.qr.QrAnalyzer.Companion.MIN_BITMAP_INTERVAL_MS
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Normalized point in the upright analysis frame (0..1 on both axes). */
data class QrPoint(val x: Float, val y: Float)

/**
 * One detected barcode. Corners are already normalized to the upright analysis
 * frame, so the overlay only needs the preview size to map them.
 */
data class QrCandidate(
    val content: String,
    val format: String?,
    val type: QrDetected.Type,
    val corners: List<QrPoint>,
)

/**
 * Result of one analysis pass. It deliberately carries no bitmap: the frame
 * image is only materialized when a new scan must be persisted (see
 * [QrAnalyzer.shouldCaptureBitmap]).
 */
class QrFrame(
    val candidates: List<QrCandidate>,
    val frameWidth: Int,
    val frameHeight: Int,
    val rotationDegrees: Int,
    val timestampMs: Long,
    val isStill: Boolean = false,
)

/**
 * ML Kit barcode analyzer for the live camera and imported still images.
 *
 * Live frames are analyzed one at a time: while a task is in flight new frames
 * are dropped (CameraX already uses STRATEGY_KEEP_ONLY_LATEST). This analyzer
 * never closes the proxy: it works on a detached bitmap copy, so the caller
 * can feed the same frame to the document pipeline, which owns closing it.
 * Callbacks run on a private single-thread executor, so the JPEG/Bitmap
 * conversion needed to persist a scan never runs on the main thread. The
 * caller is responsible for moving UI state updates to the main thread.
 *
 * @param shouldCaptureBitmap called on the analysis executor with every
 * candidate; return true only when that content still needs to be saved.
 */
class QrAnalyzer(
    private val shouldCaptureBitmap: (QrCandidate) -> Boolean,
    private val onFrame: (QrFrame, Bitmap?) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
            )
            .build()
    )

    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    private val lastBitmapMs = AtomicLong(0)
    private val lastSubmitMs = AtomicLong(0)

    /**
     * Submits one live frame. Returns immediately; the proxy is never closed
     * here, so the caller must always close it (the document analyzer does).
     * ML Kit works on a detached bitmap copy taken up front, which also feeds
     * history persistence — at most one copy per [MIN_BITMAP_INTERVAL_MS].
     */
    fun analyze(imageProxy: ImageProxy) {
        if (!inFlight.compareAndSet(false, true)) return
        // Pace submissions: ML Kit with many codes takes a while per frame, so
        // back-to-back submits just peg a CPU core decoding stale frames.
        // ~6 analyses/s is plenty for a live scanner UI.
        val submitNow = android.os.SystemClock.elapsedRealtime()
        if (submitNow - lastSubmitMs.get() < MIN_ANALYZE_INTERVAL_MS) {
            inFlight.set(false)
            return
        }
        lastSubmitMs.set(submitNow)

        val rotation = imageProxy.imageInfo.rotationDegrees
        // ML Kit returns corner points in the upright (rotated) image space.
        val uprightWidth =
            if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val uprightHeight =
            if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

        // Detached copy in sensor orientation; ML Kit applies [rotation]
        // itself via fromBitmap, so corners still land in upright space.
        // The proxy stays usable for the document pipeline right away.
        val bitmap = runCatching { imageProxy.toBitmap() }.getOrNull()
        if (bitmap == null) {
            inFlight.set(false)
            return
        }

        val input = InputImage.fromBitmap(bitmap, rotation)
        val processStart = android.os.SystemClock.elapsedRealtime()
        scanner.process(input)
            .addOnSuccessListener(callbackExecutor) { barcodes ->
                // Slow-analysis signal: with many codes ML Kit can take ~1 s.
                // The tracker predicts motion between updates, so the overlay
                // keeps gliding; this log tells whether the device needs a
                // smaller analysis size or fewer barcode formats.
                val took = android.os.SystemClock.elapsedRealtime() - processStart
                if (took > SLOW_ANALYZE_LOG_MS) {
                    Log.w(TAG, "Slow barcode analysis: ${took}ms for ${barcodes.size} codes")
                }
                val candidates = barcodes.toCandidates(uprightWidth, uprightHeight)
                if (candidates.isEmpty()) {
                    bitmap.recycle()
                    return@addOnSuccessListener
                }
                // History persistence reuses the detached copy, throttled.
                val now = android.os.SystemClock.elapsedRealtime()
                val frameBitmap = if (candidates.any(shouldCaptureBitmap) &&
                    now - lastBitmapMs.get() >= MIN_BITMAP_INTERVAL_MS
                ) {
                    lastBitmapMs.set(now)
                    bitmap
                } else {
                    bitmap.recycle()
                    null
                }
                onFrame(
                    QrFrame(
                        candidates = candidates,
                        frameWidth = uprightWidth,
                        frameHeight = uprightHeight,
                        rotationDegrees = rotation,
                        timestampMs = android.os.SystemClock.elapsedRealtime(),
                    ),
                    frameBitmap,
                )
            }
            .addOnFailureListener(callbackExecutor) { error ->
                Log.w(TAG, "Barcode analysis failed", error)
                onError(error)
                bitmap.recycle()
            }
            .addOnCompleteListener(callbackExecutor) {
                inFlight.set(false)
            }
    }

    /** Analyzes a still image (QR mode import) without touching the camera pipeline. */
    fun analyzeStill(bitmap: Bitmap, onResult: (List<QrCandidate>) -> Unit) {
        scanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener(callbackExecutor) { barcodes ->
                onResult(barcodes.toCandidates(bitmap.width, bitmap.height))
            }
            .addOnFailureListener(callbackExecutor) { error ->
                Log.w(TAG, "Barcode analysis of imported image failed", error)
                onError(error)
            }
    }

    fun close() {
        scanner.close()
        callbackExecutor.shutdown()
    }

    private fun List<Barcode>.toCandidates(width: Int, height: Int): List<QrCandidate> =
        take(MAX_CANDIDATES).mapNotNull { barcode ->
            val content = barcode.rawValue ?: barcode.displayValue ?: return@mapNotNull null
            QrCandidate(
                content = content,
                format = formatName(barcode.format),
                type = barcode.toQrType(content),
                corners = barcode.normalizedCorners(width, height),
            )
        }

    private fun Barcode.normalizedCorners(width: Int, height: Int): List<QrPoint> {
        if (width <= 0 || height <= 0) return emptyList()
        val points = cornerPoints
        if (points != null && points.size == 4) {
            return points.map {
                QrPoint(
                    x = (it.x.toFloat() / width).coerceIn(0f, 1f),
                    y = (it.y.toFloat() / height).coerceIn(0f, 1f),
                )
            }
        }
        val box = boundingBox ?: return emptyList()
        return listOf(
            QrPoint(
                (box.left.toFloat() / width).coerceIn(0f, 1f),
                (box.top.toFloat() / height).coerceIn(0f, 1f)
            ),
            QrPoint(
                (box.right.toFloat() / width).coerceIn(0f, 1f),
                (box.top.toFloat() / height).coerceIn(0f, 1f)
            ),
            QrPoint(
                (box.right.toFloat() / width).coerceIn(0f, 1f),
                (box.bottom.toFloat() / height).coerceIn(0f, 1f)
            ),
            QrPoint(
                (box.left.toFloat() / width).coerceIn(0f, 1f),
                (box.bottom.toFloat() / height).coerceIn(0f, 1f)
            ),
        )
    }

    companion object {
        private const val TAG = "QrAnalyzer"

        /** At most one YUV->RGB conversion per interval; tracking never waits. */
        private const val MIN_BITMAP_INTERVAL_MS = 500L

        /** Minimum gap between ML Kit submissions; bounds CPU with many codes. */
        private const val MIN_ANALYZE_INTERVAL_MS = 150L

        /** Hard bound on barcodes mapped per frame; overlay is capped anyway. */
        private const val MAX_CANDIDATES = 20

        /** Analyses slower than this are logged to size the pipeline. */
        private const val SLOW_ANALYZE_LOG_MS = 300L
    }
}

/** Parses every barcode value type supported by the app, including vCard and calendar. */
internal fun Barcode.toQrType(content: String): QrDetected.Type {
    val wifi = wifi
    val url = url
    val email = email
    val phone = phone
    val sms = sms
    val geo = geoPoint
    val contact = contactInfo
    val event = calendarEvent

    return when {
        wifi != null -> QrDetected.Type.Wifi(
            ssid = wifi.ssid,
            password = wifi.password,
            security = when (wifi.encryptionType) {
                Barcode.WiFi.TYPE_WPA -> "WPA"
                Barcode.WiFi.TYPE_WEP -> "WEP"
                else -> "Abierta"
            },
        )

        url != null -> QrDetected.Type.Url(url.url ?: content)
        email != null -> QrDetected.Type.Email(
            address = email.address ?: content,
            subject = email.subject,
            body = email.body,
        )

        phone != null -> QrDetected.Type.Phone(phone.number ?: content)
        sms != null -> QrDetected.Type.Sms(
            number = sms.phoneNumber ?: content,
            message = sms.message,
        )

        geo != null -> QrDetected.Type.Geo(geo.lat, geo.lng)
        contact != null -> QrDetected.Type.Contact(
            name = contact.name?.formattedName?.takeIf { it.isNotBlank() },
            phones = contact.phones.mapNotNull { it.number?.takeIf(String::isNotBlank) }.orEmpty(),
            emails = contact.emails.mapNotNull { it.address?.takeIf(String::isNotBlank) }
                .orEmpty(),
            organization = contact.organization?.takeIf { it.isNotBlank() },
            title = contact.title?.takeIf { it.isNotBlank() },
        )

        event != null -> QrDetected.Type.Event(
            summary = event.summary?.takeIf { it.isNotBlank() },
            start = event.start.formatCalendarDateTime(),
            end = event.end.formatCalendarDateTime(),
            location = event.location?.takeIf { it.isNotBlank() },
            description = event.description?.takeIf { it.isNotBlank() },
        )

        else -> QrDetected.Type.Text
    }
}

private fun Barcode.CalendarDateTime?.formatCalendarDateTime(): String? {
    if (this == null) return null
    return String.format(
        Locale.US,
        "%04d-%02d-%02d %02d:%02d",
        year,
        month,
        day,
        hours,
        minutes,
    )
}

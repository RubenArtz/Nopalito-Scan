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

/**
 * Per-capture quality record (instrumentation only, Phase 1).
 *
 * Every field is either measured or the explicit string "unavailable"/null:
 * nothing here is fabricated. String fields keep the raw device value so an
 * OEM returning HAL1-shim constants is visible instead of hidden.
 */
data class CaptureDiag(
    val captureId: Long,
    val captureTier: String,
    val cameraId: String?,
    val focalLengthMm: Float?,
    val zoomRatio: Float?,
    val requestedResolution: String?,
    val deliveredResolution: String?,
    val iso: Int?,
    val exposureNs: Long?,
    val focusDistanceDiopters: Float?,
    val afState: String,
    val aeState: String,
    val flashMode: String,
    val cropRegion: String?,
    val rotationDegrees: Int,
    val exifOrientation: Int,
    val effectiveOrientation: String,
    val jpegBytes: Long?,
    val sha12: String?,
    val captureMs: Long?,
    val processMs: Long?,
    val laplacianVar: Double?,
    val meanLuma: Double?,
    val saturatedPct: Double?,
) {
    fun toLogLine(): String = buildString {
        append("CaptureDiag ")
        append("id=$captureId tier=$captureTier cam=${cameraId ?: "unavailable"} ")
        append("focal=${focalLengthMm ?: "unavailable"} zoom=${zoomRatio ?: "unavailable"} ")
        append("req=${requestedResolution ?: "unavailable"} got=${deliveredResolution ?: "unavailable"} ")
        append("iso=${iso ?: "unavailable"} expNs=${exposureNs ?: "unavailable"} ")
        append("focusD=${focusDistanceDiopters ?: "unavailable"} af=$afState ae=$aeState ")
        append("flash=$flashMode crop=${cropRegion ?: "unavailable"} ")
        append("rot=$rotationDegrees exif=$exifOrientation eff=$effectiveOrientation ")
        append("bytes=${jpegBytes ?: "unavailable"} sha=${sha12 ?: "unavailable"} ")
        append("tCapMs=${captureMs ?: "unavailable"} tProcMs=${processMs ?: "unavailable"} ")
        append("lap=${laplacianVar?.let { "%.1f".format(it) } ?: "unavailable"} ")
        append("luma=${meanLuma?.let { "%.1f".format(it) } ?: "unavailable"} ")
        append("sat=${saturatedPct?.let { "%.2f".format(it) } ?: "unavailable"}")
    }

    fun toDebugLine(): String =
        "cam=${cameraId ?: "?"} ${deliveredResolution ?: "?"} " +
                "iso=${iso ?: "?"} af=$afState " +
                "lap=${laplacianVar?.let { "%.0f".format(it) } ?: "?"} " +
                "t=${processMs?.let { "${it}ms" } ?: "?"}"
}

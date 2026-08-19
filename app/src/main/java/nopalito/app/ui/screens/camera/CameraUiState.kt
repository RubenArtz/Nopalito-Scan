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
import androidx.compose.runtime.Immutable
import nopalito.imageprocessing.ImageSize
import nopalito.imageprocessing.Quad
import nopalito.imageprocessing.TrackMode

@Immutable
data class LiveAnalysisState(
    val inferenceTime: Long = 0L,
    val maskSize: ImageSize? = null,
    val binaryMaskProvider: () -> Bitmap? = { null },
    /** Raw tracked/detected quad in mask coordinates, sensor orientation. */
    val stableQuad: Quad? = null,
    /** Analysis frame dimensions in sensor orientation (e.g. 640x480). */
    val analysisFrameSize: ImageSize? = null,
    /** ImageProxy rotation (0, 90, 180 or 270). */
    val rotationDegrees: Int = 0,
    val analysisTimeMs: Long = 0L,
    val detectionMode: TrackMode? = null,
    val trackingError: Float = 0f,
    val analysisFps: Float = 0f,
)

sealed class ImportState {
    object Idle : ImportState()
    object Selecting : ImportState()
    data class Importing(val processed: Int, val total: Int) : ImportState()
}

data class CameraUiState(
    val pageCount: Int,
    val liveAnalysisState: LiveAnalysisState,
    val captureState: CaptureState,
    val importState: ImportState,
    val showCaptureError: Boolean,
    val isLandscape: Boolean,
    val isDebugMode: Boolean,
    val isTorchEnabled: Boolean,
    val boundCameraInfo: String? = null,
)

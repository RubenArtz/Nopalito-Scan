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

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class CaptureDiagTest {
    private fun full() = CaptureDiag(
        captureId = 7L,
        captureTier = "BALANCED",
        cameraId = "0",
        focalLengthMm = 6.3f,
        zoomRatio = 1.0f,
        requestedResolution = "3264x2448",
        deliveredResolution = "3264x2448",
        iso = 100,
        exposureNs = 10000000L,
        focusDistanceDiopters = 4.0f,
        afState = "PASSIVE_FOCUSED",
        aeState = "ae(2)",
        flashMode = "OFF",
        cropRegion = "[0,0,3264,2448]",
        rotationDegrees = 0,
        exifOrientation = 1,
        effectiveOrientation = "R0",
        jpegBytes = 1000L,
        sha12 = "abcdef123456",
        captureMs = 200L,
        processMs = 300L,
        laplacianVar = 123.4,
        meanLuma = 200.0,
        saturatedPct = 1.5,
    )

    @Test
    fun `log line carries every required field`() {
        val line = full().toLogLine()
        for (key in listOf(
            "id=7", "tier=BALANCED", "cam=0", "focal=6.3", "zoom=1.0",
            "req=3264x2448", "got=3264x2448", "iso=100", "expNs=10000000",
            "focusD=4.0", "af=PASSIVE_FOCUSED", "ae=ae(2)", "flash=OFF",
            "crop=[0,0,3264,2448]", "rot=0", "exif=1", "eff=R0",
            "bytes=1000", "sha=abcdef123456", "tCapMs=200", "tProcMs=300",
            "lap=", "luma=", "sat=",
        )) {
            assertThat(line).contains(key)
        }
    }

    @Test
    fun `missing values render unavailable never fabricated`() {
        val line = full().copy(
            cameraId = null, focalLengthMm = null, iso = null,
            afState = "unavailable", laplacianVar = null,
        ).toLogLine()
        assertThat(line).contains("cam=unavailable")
        assertThat(line).contains("af=unavailable")
        assertThat(line).contains("lap=unavailable")
        assertThat(line).doesNotContain("null")
    }
}

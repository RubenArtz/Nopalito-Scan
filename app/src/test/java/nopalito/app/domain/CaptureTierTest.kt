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

class CaptureTierTest {
    @Test
    fun `LOW stays at 2MP processed`() {
        assertThat(CaptureTier.LOW.captureMaxPixels).isEqualTo(2_000_000L)
        assertThat(CaptureTier.LOW.processedMaxPixels).isEqualTo(2_000_000L)
    }

    @Test
    fun `BALANCED captures more but processes 2MP`() {
        // Correction 4: never claim BALANCED output is 6 MP.
        assertThat(CaptureTier.BALANCED.captureMaxPixels).isEqualTo(6_000_000L)
        assertThat(CaptureTier.BALANCED.processedMaxPixels).isEqualTo(2_000_000L)
    }

    @Test
    fun `HIGH is high-res reprocess not lossless`() {
        assertThat(CaptureTier.HIGH.processedMaxPixels).isEqualTo(CaptureTier.HIGH_SAFE_MAX_PIXELS)
        assertThat(CaptureTier.HIGH_SAFE_MAX_PIXELS).isEqualTo(6_000_000L)
    }

    @Test
    fun `unknown tier falls back to BALANCED`() {
        assertThat(CaptureTier.fromName(null)).isEqualTo(CaptureTier.BALANCED)
        assertThat(CaptureTier.fromName("NOPE")).isEqualTo(CaptureTier.BALANCED)
    }

    @Test
    fun `export qualities keep visual pipeline unchanged`() {
        // Phase 1 must not change processed output values.
        assertThat(ExportQuality.BALANCED.maxPixels).isEqualTo(2_000_000L)
        assertThat(ExportQuality.BALANCED.jpegQuality).isEqualTo(75)
        assertThat(ExportQuality.HIGH.maxPixels).isEqualTo(6_000_000L)
        assertThat(ExportQuality.HIGH.jpegQuality).isEqualTo(85)
        assertThat(ExportQuality.COMPRESSED.maxPixels).isEqualTo(1_000_000L)
    }

    @Test
    fun `MAX is legacy alias of HIGH`() {
        assertThat(ExportQuality.MAX_COMPRESSION.maxPixels).isEqualTo(ExportQuality.HIGH.maxPixels)
        assertThat(ExportQuality.MAX_COMPRESSION.jpegQuality).isEqualTo(ExportQuality.HIGH.jpegQuality)
    }

    @Test
    fun `PREVIEW is the small UI variant`() {
        assertThat(ExportQuality.PREVIEW.maxPixels).isEqualTo(500_000L)
    }

    @Test
    fun `pipeline flags default to Phase 1 values`() {
        val flags = ScanPipelineFlags.Phase1Defaults
        assertThat(flags.keepOriginal).isTrue()
        assertThat(flags.highQualityCapture).isFalse()
        assertThat(flags.newPipeline).isFalse()
        assertThat(flags.dewarp).isFalse()
        assertThat(flags.fingerRemoval).isFalse()
        assertThat(flags.debugOverlay).isFalse()
    }
}

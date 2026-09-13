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
import org.assertj.core.api.Assertions.within
import org.junit.Test

class OcrScaleTest {
    @Test
    fun `small camera crop scales up capped at 2x`() {
        // 773x878 BALANCED crop: long 878 -> 1500/878 = 1.708.
        assertThat(ocrScaleFor(773, 878).toDouble()).isCloseTo(1.708, within(0.01))
    }

    @Test
    fun `large import scales down honoring the pixel cap`() {
        // 2200/3264 = 0.674 would give 3.6 MP, so the 2.6 MP cap applies.
        assertThat(ocrScaleFor(3264, 2448).toDouble()).isCloseTo(0.5704, within(0.001))
    }

    @Test
    fun `mid size is only capped by pixels`() {
        // Scale 1x would give 3.2 MP, so the 2.6 MP cap applies.
        assertThat(ocrScaleFor(1600, 2000).toDouble()).isCloseTo(0.9014, within(0.001))
    }

    @Test
    fun `pixel cap bounds huge upscales`() {
        val s = ocrScaleFor(100, 100).toDouble()
        assertThat(100.0 * 100.0 * s * s).isLessThanOrEqualTo(2_600_000.0)
    }
}

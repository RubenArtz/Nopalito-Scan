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

package nopalito.app.data

import nopalito.app.domain.CaptureTier
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ProcessingBudgetTest {
    @Test
    fun `LOW estimate is smaller than BALANCED`() {
        val low = ProcessingBudget.estimateCaptureBytes(CaptureTier.LOW)
        val balanced = ProcessingBudget.estimateCaptureBytes(CaptureTier.BALANCED)
        val high = ProcessingBudget.estimateCaptureBytes(CaptureTier.HIGH)
        assertThat(low).isLessThan(balanced)
        assertThat(balanced).isLessThan(high)
    }

    @Test
    fun `estimate includes safety margin`() {
        val raw = (6_000_000L * 0.35).toLong()
        val withMargin = ProcessingBudget.estimateCaptureBytes(CaptureTier.BALANCED, 6_000_000L)
        assertThat(withMargin).isGreaterThan(raw + CaptureTier.STORAGE_MIN_FREE_BYTES)
    }

    @Test
    fun `HIGH documents 6MP ceiling`() {
        assertThat(CaptureTier.HIGH_SAFE_MAX_PIXELS).isEqualTo(6_000_000L)
    }
}

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

package nopalito.imageprocessing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LensSelectionTest {

    @Test
    fun `picks the widest lens when a real ultra-wide exists`() {
        val lenses = listOf(
            LensSpec("2", 70.0),
            LensSpec("1", 24.0),
            LensSpec("0", 13.0),
        )
        assertEquals(LensSpec("0", 13.0), pickUltraWideLens(lenses))
    }

    @Test
    fun `returns null with a single back camera`() {
        assertNull(pickUltraWideLens(listOf(LensSpec("0", 24.0))))
    }

    @Test
    fun `returns null when all lenses have the same focal length`() {
        assertNull(pickUltraWideLens(listOf(LensSpec("0", 24.0), LensSpec("1", 24.0))))
    }

    @Test
    fun `returns null when the widest lens is not wide enough`() {
        // 18mm vs 24mm = 0.75 exactly: boundary is rejected (must be < 0.75).
        assertNull(pickUltraWideLens(listOf(LensSpec("0", 18.0), LensSpec("1", 24.0))))
        // 19mm vs 24mm = 0.79: rejected.
        assertNull(pickUltraWideLens(listOf(LensSpec("0", 19.0), LensSpec("1", 24.0))))
    }

    @Test
    fun `accepts a clearly wider lens`() {
        val lenses = listOf(LensSpec("0", 17.0), LensSpec("1", 24.0))
        assertEquals(LensSpec("0", 17.0), pickUltraWideLens(lenses))
    }

    @Test
    fun `ignores invalid focal lengths`() {
        assertNull(pickUltraWideLens(listOf(LensSpec("0", 0.0), LensSpec("1", 24.0))))
    }
}

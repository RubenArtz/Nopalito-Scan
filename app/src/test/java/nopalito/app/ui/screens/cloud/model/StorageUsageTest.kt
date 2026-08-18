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

package nopalito.app.ui.screens.cloud.model

import org.junit.Assert.*
import org.junit.Test

class StorageUsageTest {

    @Test
    fun `free account defaults to not premium even when isPremium is null`() {
        val usage = StorageUsage(
            plan = "FREE",
            usedBytes = 52_428_800,
            limitBytes = 104_857_600,
            freeBytes = 52_428_800,
            usedPercent = 50,
            isPremium = null
        )
        assertFalse(usage.isPremiumPlan)
        assertEquals(0.5f, usage.progressRatio, 0.001f)
    }

    @Test
    fun `premium is detected from the server plan regardless of the boolean flag`() {
        val usage = StorageUsage(
            plan = "PREMIUM",
            usedBytes = 2_580_000_000,
            limitBytes = 10_737_418_240,
            freeBytes = 10_737_418_240 - 2_580_000_000,
            usedPercent = 24,
            isPremium = null
        )
        assertTrue(usage.isPremiumPlan)
        assertEquals(0.24f, usage.progressRatio, 0.001f)
    }

    @Test
    fun `progress ratio is clamped even if the server reports inconsistent data`() {
        val over = StorageUsage(plan = "FREE", usedPercent = 120, usedBytes = 200, limitBytes = 100, freeBytes = 0)
        assertEquals(1f, over.progressRatio, 0.001f)

        val negative = StorageUsage(plan = "FREE", usedPercent = -5, usedBytes = 0, limitBytes = 100, freeBytes = 100)
        assertEquals(0f, negative.progressRatio, 0.001f)
    }

    @Test
    fun `over-quota usage (70 MB used of 50 MB) never shows more than a full bar`() {
        // The backend normalizes inconsistencies: freeBytes is clamped to 0 and
        // usedPercent to 100, keeping usedBytes real for diagnosis. The client
        // bar must therefore never exceed 100%.
        val over = StorageUsage(
            plan = "FREE",
            usedBytes = 73_400_320, // 70 MB
            limitBytes = 52_428_800, // 50 MB
            freeBytes = 0,
            usedPercent = 100,
            isPremium = null
        )
        assertEquals(0L, over.freeBytes)
        assertEquals(1f, over.progressRatio, 0.001f)
    }
}
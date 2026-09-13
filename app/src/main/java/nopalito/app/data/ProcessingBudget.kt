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

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import nopalito.app.domain.CaptureTier
import java.io.File

/**
 * Pre-capture guards for HIGH/max resolutions (Phase 1).
 *
 * Estimates are deliberately conservative: JPEG bytes are approximated from
 * pixels and a per-tier ratio, then [CaptureTier.STORAGE_SAFETY_MULTIPLIER]
 * is applied on top plus [CaptureTier.STORAGE_MIN_FREE_BYTES] headroom.
 * Denials carry numbers so the UI can explain, never silently downgrade
 * ORIGINAL (that decision belongs to the user).
 */
object ProcessingBudget {
    sealed interface Decision {
        data object Allowed : Decision
        data class DenyStorage(val requiredBytes: Long, val freeBytes: Long) : Decision
        data class DenyMemory(val requiredBytes: Long, val availBytes: Long) : Decision
    }

    fun estimateCaptureBytes(tier: CaptureTier, fallbackPixels: Long = 12_000_000L): Long {
        val pixels = when (tier) {
            CaptureTier.LOW -> 2_000_000L
            CaptureTier.BALANCED -> 6_000_000L
            CaptureTier.HIGH, CaptureTier.ORIGINAL -> fallbackPixels
        }
        // ~0.35 bytes/px at q90 plus safety multiplier.
        return (pixels * 0.35 * CaptureTier.STORAGE_SAFETY_MULTIPLIER).toLong() +
                CaptureTier.STORAGE_MIN_FREE_BYTES
    }

    fun check(
        context: Context,
        tier: CaptureTier,
        destDir: File,
        maxCapturePixels: Long = 12_000_000L,
    ): Decision {
        val required = estimateCaptureBytes(tier, maxCapturePixels)
        val free = runCatching {
            val stat = StatFs(destDir.absolutePath)
            stat.availableBytes
        }.getOrDefault(destDir.usableSpace)
        if (free < required) return Decision.DenyStorage(required, free)
        if (tier == CaptureTier.HIGH || tier == CaptureTier.ORIGINAL) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val avail = memInfo.availMem
            // Full-res decode + working Mats need roughly 6 bytes/px headroom.
            val needMem = maxCapturePixels * 6
            if (avail in 1..needMem && (am?.memoryClass ?: 0) < 192) {
                return Decision.DenyMemory(needMem, avail)
            }
        }
        return Decision.Allowed
    }
}

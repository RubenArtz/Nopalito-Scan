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

package nopalito.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Centralized, subtle haptic feedback for actions with a clear consequence.
 *
 * Rules followed:
 * - The system path (`performHapticFeedback`) is preferred and already respects
 *   the user's "Vibrate on touch" setting, so no extra check is needed there.
 * - API 30+ uses the system's tuned CONFIRM / REJECT haptics; older devices
 *   fall back to [VibrationEffect] with low amplitudes.
 * - The direct vibrator path is tagged as touch usage so the system applies
 *   the user's vibration settings automatically on API 33+ (the manual
 *   HAPTIC_FEEDBACK_ENABLED check is deprecated there and only used below 33).
 * - Every call is wrapped in runCatching: this must never throw or break a
 *   flow, even on low-end devices without a vibrator.
 *
 * To disable all haptics later, remove the calls to this class (one line each)
 * and delete this file.
 */
class HapticManager(
    private val hapticFeedback: HapticFeedback,
    private val context: Context,
) {
    private val vibrator: Vibrator? =
        context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }

    /**
     * Manual system check, only needed below API 33. On API 33+ the vibration
     * service applies the user's haptic settings automatically when the
     * vibration is tagged as [VibrationAttributes.USAGE_TOUCH].
     */
    @Suppress("DEPRECATION")
    private fun hapticEnabled(): Boolean =
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        ) != 0

    /** Very short tick for routine actions (capture, export, share, open). */
    fun click() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** Slightly longer confirmation for meaningful success (save, confirm). */
    fun success() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
        } else {
            vibrateFallback(VibrationEffect.createOneShot(20L, 60))
        }
    }

    /** Noticeable but not alarming pattern for failures (save/scan errors). */
    fun error() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.Reject)
        } else {
            vibrateFallback(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 35L, 60L, 35L),
                    intArrayOf(0, 120, 0, 120),
                    -1,
                )
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateFallback(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: tag as touch usage; the system service applies the
            // user's vibration settings automatically.
            val attributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
            runCatching { vibrator?.vibrate(effect, attributes) }
        } else {
            // API 26-32: apply the user's haptic setting manually.
            if (!hapticEnabled()) return
            runCatching { vibrator?.vibrate(effect) }
        }
    }
}

/** Returns a [HapticManager] bound to the current composition's haptics and context. */
@Composable
fun rememberHapticManager(): HapticManager {
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    return remember(hapticFeedback, context) { HapticManager(hapticFeedback, context) }
}
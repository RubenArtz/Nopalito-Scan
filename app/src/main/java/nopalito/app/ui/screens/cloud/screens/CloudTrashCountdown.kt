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

package nopalito.app.ui.screens.cloud.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import nopalito.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

private const val MS_PER_SECOND = 1_000L
private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_DAY = 86_400_000L

/** Below this remaining time the countdown ticks every second. */
private const val MS_TICK_SECOND = 2 * MS_PER_MINUTE

/** Below this remaining time the countdown ticks every 30 seconds. */
private const val MS_TICK_HALF_MINUTE = MS_PER_DAY

/** How long the trash info popup stays open before auto-dismissing. */
private const val AUTO_DISMISS_MILLIS = 15_000L

/**
 * Single adaptive countdown source for an entire trash screen (one timer for
 * all items — never one per file).
 *
 * - Returns the current wall-clock [State] so items compute
 *   `scheduledDeletionAt - now` cheaply on recomposition.
 * - Ticks every 60s when more than a day remains, every 30s under a day, and
 *   every second when only a few minutes are left.
 * - The loop is cancelled while the app is in the background (ON_PAUSE) and
 *   restarted on ON_RESUME; leaving the screen disposes the effect.
 * - When a deadline is crossed, [onDeadlineReached] fires ONCE per item; the
 *   caller should re-sync with the backend. Nothing is deleted client-side:
 *   permanent deletion is the API's job.
 */
@Composable
fun rememberTrashNow(
    deadlines: Map<String, Long>,
    onDeadlineReached: () -> Unit,
): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var paused by remember { mutableStateOf(false) }
    val deadlinesRef = rememberUpdatedState(deadlines)
    val onDeadlineReachedRef = rememberUpdatedState(onDeadlineReached)
    val firedRef = remember { mutableStateOf<Set<String>>(emptySet()) }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { paused = true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { paused = false }

    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        while (true) {
            val t = System.currentTimeMillis()
            now.longValue = t

            val nearest = deadlinesRef.value.values.minOrNull()
            val delayMs = when {
                nearest == null -> 60_000L
                nearest - t <= MS_TICK_SECOND -> 1_000L
                nearest - t <= MS_TICK_HALF_MINUTE -> 30_000L
                else -> 60_000L
            }

            var reached = false
            for ((id, deadline) in deadlinesRef.value) {
                if (deadline in 1..t && id !in firedRef.value) {
                    firedRef.value += id
                    reached = true
                }
            }
            if (reached) onDeadlineReachedRef.value()

            delay(delayMs.milliseconds)
        }
    }

    return now
}

/**
 * Parses a backend timestamp (ISO-8601 with Z, e.g. "2026-09-10T12:34:56.000Z")
 * into epoch millis. Returns null when the value is missing/unparseable.
 */
fun isoToEpochMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(iso)?.time
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Decomposition of a remaining-time interval for localized rendering.
 * [lessThanMinute] is true when the interval is not positive (clamped) or
 * shorter than a minute, matching the previous under-a-minute bucket.
 */
data class TrashRemainingParts(
    val days: Long,
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
    val lessThanMinute: Boolean,
)

/**
 * Pure decomposition of [remainingMillis] into the same buckets the old
 * fixed-text formatter used (days+hours, hours+minutes, minutes+seconds,
 * under-a-minute). Testable on the JVM; the localized string assembly happens
 * in [trashRemainingLabel].
 */
fun trashRemainingParts(remainingMillis: Long): TrashRemainingParts {
    if (remainingMillis <= 0L) return TrashRemainingParts(0, 0, 0, 0, lessThanMinute = true)
    val totalMinutes = remainingMillis / MS_PER_MINUTE
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    val seconds = if (totalMinutes > 0) (remainingMillis % MS_PER_MINUTE) / MS_PER_SECOND else 0
    return TrashRemainingParts(
        days = days,
        hours = hours,
        minutes = minutes,
        seconds = seconds,
        lessThanMinute = totalMinutes == 0L,
    )
}

/** Localized compact remaining-time label built from the pure decomposition. */
@Composable
fun trashRemainingLabel(parts: TrashRemainingParts): String = when {
    parts.lessThanMinute -> stringResource(R.string.cloud_trash_remaining_less_minute)
    parts.days > 0 -> stringResource(
        R.string.cloud_trash_remaining_days_hours,
        parts.days,
        parts.hours,
    )

    parts.hours > 0 -> stringResource(
        R.string.cloud_trash_remaining_hours_minutes,
        parts.hours,
        parts.minutes,
    )

    else -> stringResource(
        R.string.cloud_trash_remaining_minutes_seconds,
        parts.minutes,
        parts.seconds,
    )
}

/**
 * Formats a trash-related date for display (local timezone, locale-aware
 * short pattern — no fixed "dd/MM/yyyy" template).
 */
fun formatTrashDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(trashDateFormatter())

private fun trashDateFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

/**
 * Compact clock button that opens a small popup with the trash timing info:
 * live countdown, scheduled-deletion date, trashed date and origin. The popup
 * auto-dismisses after 6 seconds, on outside/back press, or when the clock is
 * pressed again. It reads the shared screen [now], so the countdown stays
 * live while the popup is open (one timer for the whole screen).
 */
@Composable
fun TrashInfoButton(
    now: Long,
    deadlineMillis: Long?,
    trashedAtMillis: Long?,
    trashSource: String?,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(open) {
        if (open) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = AUTO_DISMISS_MILLIS.toInt(), easing = LinearEasing),
            )
            open = false
        }
    }

    IconButton(
        onClick = { open = !open },
        modifier = modifier.size(28.dp),
    ) {
        Icon(
            Icons.Filled.Schedule,
            contentDescription = stringResource(R.string.cloud_trash_info),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (open) {
        Dialog(
            onDismissRequest = { open = false },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 16.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.cloud_trash_info_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = stringResource(R.string.cloud_trash_info_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    if (deadlineMillis != null) {
                        val remaining = deadlineMillis - now
                        val isExpired = remaining <= 0L
                        val isUrgent = !isExpired && remaining < 24 * 3600_000L
                        val countdownColor = when {
                            isExpired -> MaterialTheme.colorScheme.onSurfaceVariant
                            isUrgent -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                        InfoRow(
                            icon = Icons.Filled.Timer,
                            tint = countdownColor,
                            label = stringResource(R.string.cloud_trash_info_remaining),
                            value = if (isExpired) {
                                stringResource(R.string.cloud_trash_awaiting_deletion)
                            } else {
                                stringResource(
                                    R.string.cloud_trash_deletes_in,
                                    trashRemainingLabel(trashRemainingParts(remaining)),
                                )
                            },
                            valueColor = countdownColor,
                            valueWeight = FontWeight.Medium,
                        )
                        InfoRow(
                            icon = Icons.Filled.Event,
                            label = stringResource(R.string.cloud_trash_info_scheduled),
                            value = formatTrashDate(deadlineMillis),
                        )
                    }
                    if (trashedAtMillis != null) {
                        InfoRow(
                            icon = Icons.Filled.Delete,
                            label = stringResource(R.string.cloud_trash_info_trashed),
                            value = formatTrashDate(trashedAtMillis),
                        )
                    }
                    when (trashSource) {
                        "admin" -> InfoRow(
                            icon = Icons.Filled.Cloud,
                            label = stringResource(R.string.cloud_trash_info_origin),
                            value = stringResource(R.string.cloud_trash_source_admin),
                        )

                        "mobile" -> InfoRow(
                            icon = Icons.Filled.Smartphone,
                            label = stringResource(R.string.cloud_trash_info_origin),
                            value = stringResource(R.string.cloud_trash_source_mobile),
                        )
                    }

                    LinearProgressIndicator(
                        progress = { 1f - progress.value },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueWeight: FontWeight = FontWeight.Normal,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                fontWeight = valueWeight,
            )
        }
    }
}

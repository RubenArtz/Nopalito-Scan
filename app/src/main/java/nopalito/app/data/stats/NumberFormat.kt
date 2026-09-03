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

package nopalito.app.data.stats

import java.text.NumberFormat
import java.util.Locale

/**
 * Locale-aware grouping formatter (replaces manual addCommas).
 * Uses grouping separator for the given locale (e.g., 1,234 in en, 1.234 in es/de).
 * Storage (KB/MB/GB) is intentionally excluded — use formatMb/formatCloudFileSize.
 */
fun Int.formatWithGrouping(locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply { isGroupingUsed = true }.format(this)

fun Long.formatWithGrouping(locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply { isGroupingUsed = true }.format(this)

fun Double.formatWithGrouping(locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply { isGroupingUsed = true }.format(this)

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

data class StatsSummary(
    val scans: Int = 0,
    val pages: Int = 0,
    val sizeKb: Long = 0,
    val exportedCount: Int = 0,
    val shares: Int = 0,
    val deletes: Int = 0,
    val toolsTotal: Int = 0,
    val opens: Int = 0,
    val photos: Int = 0,
    val toolBreakdown: List<ToolCountRow> = emptyList(),
    val exportBreakdown: List<ExportCountRow> = emptyList()
)

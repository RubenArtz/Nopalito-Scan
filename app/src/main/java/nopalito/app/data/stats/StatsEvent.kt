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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Single source of truth for local usage statistics (MVP 100% local).
 * One row per user action. No sensitive content is stored.
 *
 * v2: can be synced in batch to POST /api/stats/events when a session exists.
 */
@Entity(
    tableName = "stats_events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["eventType"]),
        Index(value = ["timestamp", "eventType"])
    ]
)
data class StatsEvent(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    /** scan_created | scan_exported | scan_shared | scan_deleted | scan_opened | photo_captured */
    val eventType: String,
    /** epoch millis (System.currentTimeMillis()) */
    val timestamp: Long,
    /**
     * JSON with variable properties:
     * scan_created: {type, pages, size_kb, capture_duration_ms, has_ocr, filter}
     * scan_exported: {format:pdf|image|docx, destination:local, source:export|tool_*}
     * scan_shared / scan_deleted / scan_opened: {} or {permanent:bool, source:String}
     * photo_captured: {source:camera|import|retake}
     */
    val propertiesJson: String = "{}"
) {
    companion object {
        const val SCAN_CREATED = "scan_created"
        const val SCAN_EXPORTED = "scan_exported"
        const val SCAN_SHARED = "scan_shared"
        const val SCAN_DELETED = "scan_deleted"
        const val TOOL_USED = "tool_used"
        const val SCAN_OPENED = "scan_opened"
        const val PHOTO_CAPTURED = "photo_captured"
    }
}

/** Aggregated row per day for UI (not a table, only query result). */
data class DailyRow(
    val date: String, // YYYY-MM-DD localtime
    val scans: Int,
    val pages: Int,
    val sizeKb: Long,
    val exports: Int,
    val shares: Int,
    val deletes: Int,
    val tools: Int = 0,
    val opens: Int = 0,
    val photos: Int = 0
)

/** Count of tool usage grouped by tool name. */
data class ToolCountRow(
    val tool: String,
    val count: Int
)

/** Count of exports grouped by format. */
data class ExportCountRow(
    val format: String,
    val count: Int
)

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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: StatsEvent)

    /**
     * Daily aggregation for chart and summary (7/30/365 days).
     * Uses json_extract (SQLite >=3.38, Room 2.8.4 → API 30+). On older APIs
     * pages/sizeKb will be 0 and UI tolerates it.
     */
    @Query(
        """
        SELECT 
          DATE(timestamp/1000,'unixepoch','localtime') AS date,
          SUM(CASE WHEN eventType='scan_created' THEN 1 ELSE 0 END) AS scans,
          SUM(CASE WHEN eventType='scan_created' THEN CAST(json_extract(propertiesJson,'$.pages') AS INTEGER) ELSE 0 END) AS pages,
          SUM(CASE WHEN eventType='scan_created' THEN CAST(json_extract(propertiesJson,'$.size_kb') AS INTEGER) ELSE 0 END) AS sizeKb,
          SUM(CASE WHEN eventType='scan_exported' THEN 1 ELSE 0 END) AS exports,
          SUM(CASE WHEN eventType='scan_shared' THEN 1 ELSE 0 END) AS shares,
          SUM(CASE WHEN eventType='scan_deleted' THEN 1 ELSE 0 END) AS deletes,
          SUM(CASE WHEN eventType='tool_used' THEN 1 ELSE 0 END) AS tools,
          SUM(CASE WHEN eventType='scan_opened' THEN 1 ELSE 0 END) AS opens,
          SUM(CASE WHEN eventType='photo_captured' THEN 1 ELSE 0 END) AS photos
        FROM stats_events 
        WHERE timestamp >= :fromMillis
        GROUP BY date 
        ORDER BY date ASC
        """
    )
    fun dailySince(fromMillis: Long): Flow<List<DailyRow>>

    @Query(
        """
        SELECT json_extract(propertiesJson,'$.tool') AS tool, COUNT(*) AS count
        FROM stats_events
        WHERE eventType='tool_used' AND timestamp >= :fromMillis
        AND json_extract(propertiesJson,'$.tool') IS NOT NULL
        GROUP BY tool
        ORDER BY count DESC
        """
    )
    fun toolCountsSince(fromMillis: Long): Flow<List<ToolCountRow>>

    @Query(
        """
        SELECT json_extract(propertiesJson,'$.format') AS format, COUNT(*) AS count
        FROM stats_events
        WHERE eventType='scan_exported' AND timestamp >= :fromMillis
        AND json_extract(propertiesJson,'$.format') IS NOT NULL
        GROUP BY format
        ORDER BY count DESC
        """
    )
    fun exportCountsSince(fromMillis: Long): Flow<List<ExportCountRow>>
}

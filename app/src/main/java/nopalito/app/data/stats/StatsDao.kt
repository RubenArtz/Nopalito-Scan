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
     * Raw events for the requested window. Aggregation (per-day counts,
     * pages/size, tool and export breakdowns) happens in
     * [StatsAggregator] on the JVM side.
     *
     * The previous implementation used json_extract in SQL, but the
     * framework SQLite on some devices (e.g. OnePlus 8 Pro) is built
     * without the JSON1 extension, so compiling those queries throws
     * "no such function: json_extract" and crashes the stats screen.
     * This query uses only core SQL available on every API level.
     */
    @Query(
        """
        SELECT id, eventType, timestamp, propertiesJson
        FROM stats_events
        WHERE timestamp >= :fromMillis
        ORDER BY timestamp ASC
        """
    )
    fun eventsSince(fromMillis: Long): Flow<List<StatsEvent>>
}

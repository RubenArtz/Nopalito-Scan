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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.json.JSONObject

class StatsRepository(
    private val dao: StatsDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun logScanCreated(
        type: String,
        pages: Int,
        sizeKb: Long,
        captureDurationMs: Long,
        hasOcr: Boolean,
        filter: String
    ) = withContext(ioDispatcher) {
        val props = JSONObject().apply {
            put("type", type)
            put("pages", pages)
            put("size_kb", sizeKb)
            put("capture_duration_ms", captureDurationMs)
            put("has_ocr", hasOcr)
            put("filter", filter)
        }.toString()
        dao.insert(
            StatsEvent(
                eventType = StatsEvent.SCAN_CREATED,
                timestamp = System.currentTimeMillis(),
                propertiesJson = props
            )
        )
    }

    suspend fun logScanExported(
        format: String,
        destination: String
    ) = withContext(ioDispatcher) {
        val props = JSONObject().apply {
            put("format", format)
            put("destination", destination)
        }.toString()
        dao.insert(
            StatsEvent(
                eventType = StatsEvent.SCAN_EXPORTED,
                timestamp = System.currentTimeMillis(),
                propertiesJson = props
            )
        )
    }

    suspend fun logScanShared() = withContext(ioDispatcher) {
        dao.insert(
            StatsEvent(
                eventType = StatsEvent.SCAN_SHARED,
                timestamp = System.currentTimeMillis(),
                propertiesJson = "{}"
            )
        )
    }

    suspend fun logScanDeleted() = withContext(ioDispatcher) {
        dao.insert(
            StatsEvent(
                eventType = StatsEvent.SCAN_DELETED,
                timestamp = System.currentTimeMillis(),
                propertiesJson = "{}"
            )
        )
    }

    suspend fun logScanOpened(source: String = "unknown") = withContext(ioDispatcher) {
        val props = JSONObject().apply { put("source", source) }.toString()
        dao.insert(
            StatsEvent(
                eventType = StatsEvent.SCAN_OPENED,
                timestamp = System.currentTimeMillis(),
                propertiesJson = props
            )
        )
    }

    suspend fun logPhotoCaptured(
        source: String = "camera",
        pages: Int = 1
    ) = withContext(ioDispatcher) {
        val props = JSONObject().apply {
            put("source", source)
            put("pages", pages)
        }.toString()
        dao.insert(
            StatsEvent(
                eventType = StatsEvent.PHOTO_CAPTURED,
                timestamp = System.currentTimeMillis(),
                propertiesJson = props
            )
        )
    }

    suspend fun logToolUsed(tool: String) = withContext(ioDispatcher) {
        val props = JSONObject().apply { put("tool", tool) }.toString()
        dao.insert(
            StatsEvent(
                eventType = StatsEvent.TOOL_USED,
                timestamp = System.currentTimeMillis(),
                propertiesJson = props
            )
        )
    }

    fun getStatsFlow(days: Int): Flow<StatsSummary> {
        val fromMillis = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        return combine(
            dao.dailySince(fromMillis),
            dao.toolCountsSince(fromMillis),
            dao.exportCountsSince(fromMillis)
        ) { rows, tools, exports ->
            StatsSummary(
                scans = rows.sumOf { it.scans },
                pages = rows.sumOf { it.pages },
                sizeKb = rows.sumOf { it.sizeKb },
                exportedCount = rows.sumOf { it.exports },
                shares = rows.sumOf { it.shares },
                deletes = rows.sumOf { it.deletes },
                toolsTotal = rows.sumOf { it.tools },
                opens = rows.sumOf { it.opens },
                photos = rows.sumOf { it.photos },
                toolBreakdown = tools,
                exportBreakdown = exports
            )
        }
    }

    fun getStatsFlow(period: StatsPeriod): Flow<StatsSummary> = getStatsFlow(period.days)

    fun getDailyFlow(days: Int): Flow<List<DailyRow>> {
        val fromMillis = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        return dao.dailySince(fromMillis)
    }

    fun getDailyFlow(period: StatsPeriod): Flow<List<DailyRow>> = getDailyFlow(period.days)

    fun getToolBreakdown(period: StatsPeriod): Flow<List<ToolCountRow>> {
        val fromMillis = System.currentTimeMillis() - period.days * 24L * 60L * 60L * 1000L
        return dao.toolCountsSince(fromMillis)
    }

    fun getExportBreakdown(period: StatsPeriod): Flow<List<ExportCountRow>> {
        val fromMillis = System.currentTimeMillis() - period.days * 24L * 60L * 60L * 1000L
        return dao.exportCountsSince(fromMillis)
    }
}
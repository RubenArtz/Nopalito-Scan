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

import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * In-memory aggregation for local usage statistics.
 *
 * Lives outside Room/SQLite on purpose: the framework SQLite on some
 * devices ships without the JSON1 extension, so any query using
 * json_extract fails at compile time with
 * "no such function: json_extract". Parsing [StatsEvent.propertiesJson]
 * here with kotlinx.serialization (not org.json, so plain JVM unit tests
 * can run where android.jar is stubbed) works on every device and keeps
 * malformed rows from breaking the stats screen.
 */
object StatsAggregator {

    fun List<StatsEvent>.toDailyRows(
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<DailyRow> {
        if (isEmpty()) return emptyList()
        return groupBy { it.localDateKey(zoneId) }
            .map { (date, dayEvents) ->
                var scans = 0
                var pages = 0
                var sizeKb = 0L
                var exports = 0
                var shares = 0
                var deletes = 0
                var tools = 0
                var opens = 0
                var photos = 0
                dayEvents.forEach { event ->
                    when (event.eventType) {
                        StatsEvent.SCAN_CREATED -> {
                            scans++
                            pages += event.propertiesJson.intProperty("pages")
                            sizeKb += event.propertiesJson.longProperty("size_kb")
                        }
                        StatsEvent.SCAN_EXPORTED -> exports++
                        StatsEvent.SCAN_SHARED -> shares++
                        StatsEvent.SCAN_DELETED -> deletes++
                        StatsEvent.TOOL_USED -> tools++
                        StatsEvent.SCAN_OPENED -> opens++
                        StatsEvent.PHOTO_CAPTURED -> photos++
                    }
                }
                DailyRow(
                    date = date,
                    scans = scans,
                    pages = pages,
                    sizeKb = sizeKb,
                    exports = exports,
                    shares = shares,
                    deletes = deletes,
                    tools = tools,
                    opens = opens,
                    photos = photos
                )
            }
            .sortedBy { it.date }
    }

    fun List<StatsEvent>.toToolCounts(): List<ToolCountRow> =
        asSequence()
            .filter { it.eventType == StatsEvent.TOOL_USED }
            .mapNotNull { it.propertiesJson.stringProperty("tool") }
            .groupingBy { it }
            .eachCount()
            .map { (tool, count) -> ToolCountRow(tool, count) }
            .sortedWith(compareByDescending<ToolCountRow> { it.count }.thenBy { it.tool })

    fun List<StatsEvent>.toExportCounts(): List<ExportCountRow> =
        asSequence()
            .filter { it.eventType == StatsEvent.SCAN_EXPORTED }
            .mapNotNull { it.propertiesJson.stringProperty("format") }
            .groupingBy { it }
            .eachCount()
            .map { (format, count) -> ExportCountRow(format, count) }
            .sortedWith(compareByDescending<ExportCountRow> { it.count }.thenBy { it.format })

    fun List<StatsEvent>.toStatsSummary(
        zoneId: ZoneId = ZoneId.systemDefault()
    ): StatsSummary {
        val daily = toDailyRows(zoneId)
        return StatsSummary(
            scans = daily.sumOf { it.scans },
            pages = daily.sumOf { it.pages },
            sizeKb = daily.sumOf { it.sizeKb },
            exportedCount = daily.sumOf { it.exports },
            shares = daily.sumOf { it.shares },
            deletes = daily.sumOf { it.deletes },
            toolsTotal = daily.sumOf { it.tools },
            opens = daily.sumOf { it.opens },
            photos = daily.sumOf { it.photos },
            toolBreakdown = toToolCounts(),
            exportBreakdown = toExportCounts()
        )
    }

    private fun StatsEvent.localDateKey(zoneId: ZoneId): String =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().toString()

    private fun String.intProperty(key: String): Int =
        runCatching {
            val primitive = kotlinx.serialization.json.Json.parseToJsonElement(this)
                .jsonObject[key]?.jsonPrimitive ?: return@runCatching 0
            primitive.intOrNull ?: primitive.longOrNull?.toInt() ?: 0
        }.getOrDefault(0).coerceAtLeast(0)

    private fun String.longProperty(key: String): Long =
        runCatching {
            val primitive = kotlinx.serialization.json.Json.parseToJsonElement(this)
                .jsonObject[key]?.jsonPrimitive ?: return@runCatching 0L
            primitive.longOrNull ?: primitive.intOrNull?.toLong() ?: 0L
        }.getOrDefault(0L).coerceAtLeast(0L)

    private fun String.stringProperty(key: String): String? =
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(this)
                .jsonObject[key]?.jsonPrimitive
                ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
}

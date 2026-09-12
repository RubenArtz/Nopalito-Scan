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

import nopalito.app.data.stats.StatsAggregator.toDailyRows
import nopalito.app.data.stats.StatsAggregator.toExportCounts
import nopalito.app.data.stats.StatsAggregator.toStatsSummary
import nopalito.app.data.stats.StatsAggregator.toToolCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class StatsAggregatorTest {

    private val utc = ZoneId.of("UTC")
    private val dayOne = Instant.parse("2026-02-10T10:15:00Z").toEpochMilli()
    private val dayTwo = Instant.parse("2026-02-11T09:00:00Z").toEpochMilli()

    private fun event(
        type: String,
        timestamp: Long = dayOne,
        propertiesJson: String = "{}"
    ) = StatsEvent(eventType = type, timestamp = timestamp, propertiesJson = propertiesJson)

    @Test
    fun `empty list produces empty rows and zero summary`() {
        assertTrue(emptyList<StatsEvent>().toDailyRows(utc).isEmpty())
        assertTrue(emptyList<StatsEvent>().toToolCounts().isEmpty())
        assertTrue(emptyList<StatsEvent>().toExportCounts().isEmpty())
        assertEquals(StatsSummary(), emptyList<StatsEvent>().toStatsSummary(utc))
    }

    @Test
    fun `daily rows sum scans pages and size per local day`() {
        val events = listOf(
            event(StatsEvent.SCAN_CREATED, dayOne, """{"pages":3,"size_kb":120}"""),
            event(StatsEvent.SCAN_CREATED, dayOne, """{"pages":2,"size_kb":80}"""),
            event(StatsEvent.SCAN_EXPORTED, dayOne, """{"format":"pdf"}"""),
            event(StatsEvent.SCAN_SHARED, dayOne),
            event(StatsEvent.TOOL_USED, dayOne, """{"tool":"compress"}"""),
            event(StatsEvent.SCAN_OPENED, dayOne),
            event(StatsEvent.PHOTO_CAPTURED, dayOne),
            event(StatsEvent.SCAN_DELETED, dayOne),
            event(StatsEvent.SCAN_CREATED, dayTwo, """{"pages":1,"size_kb":10}""")
        )

        val rows = events.toDailyRows(utc)

        assertEquals(2, rows.size)
        val first = rows[0]
        assertEquals("2026-02-10", first.date)
        assertEquals(2, first.scans)
        assertEquals(5, first.pages)
        assertEquals(200L, first.sizeKb)
        assertEquals(1, first.exports)
        assertEquals(1, first.shares)
        assertEquals(1, first.deletes)
        assertEquals(1, first.tools)
        assertEquals(1, first.opens)
        assertEquals(1, first.photos)
        assertEquals("2026-02-11", rows[1].date)
        assertEquals(1, rows[1].scans)
    }

    @Test
    fun `malformed or incomplete properties do not break aggregation`() {
        val events = listOf(
            event(StatsEvent.SCAN_CREATED, dayOne, "not-json"),
            event(StatsEvent.SCAN_CREATED, dayOne, """{"pages":"three"}"""),
            event(StatsEvent.SCAN_CREATED, dayOne, """{"pages":-2,"size_kb":-5}"""),
            event(StatsEvent.SCAN_CREATED, dayOne, "{}")
        )

        val rows = events.toDailyRows(utc)

        assertEquals(1, rows.size)
        assertEquals(4, rows[0].scans)
        assertEquals(0, rows[0].pages)
        assertEquals(0L, rows[0].sizeKb)
    }

    @Test
    fun `tool counts ignore blank missing and non-string tools`() {
        val events = listOf(
            event(StatsEvent.TOOL_USED, dayOne, """{"tool":"compress"}"""),
            event(StatsEvent.TOOL_USED, dayOne, """{"tool":"compress"}"""),
            event(StatsEvent.TOOL_USED, dayOne, """{"tool":"ocr"}"""),
            event(StatsEvent.TOOL_USED, dayOne, """{"tool":"  "}"""),
            event(StatsEvent.TOOL_USED, dayOne, "{}"),
            event(StatsEvent.TOOL_USED, dayOne, """{"tool":42}"""),
            event(StatsEvent.TOOL_USED, dayOne, "broken"),
            event(StatsEvent.SCAN_CREATED, dayOne, """{"tool":"compress"}""")
        )

        assertEquals(
            listOf(ToolCountRow("compress", 2), ToolCountRow("ocr", 1)),
            events.toToolCounts()
        )
    }

    @Test
    fun `export counts group by format ordered by count desc`() {
        val events = listOf(
            event(StatsEvent.SCAN_EXPORTED, dayOne, """{"format":"pdf"}"""),
            event(StatsEvent.SCAN_EXPORTED, dayOne, """{"format":"image"}"""),
            event(StatsEvent.SCAN_EXPORTED, dayOne, """{"format":"pdf"}"""),
            event(StatsEvent.SCAN_EXPORTED, dayOne, "{}"),
            event(StatsEvent.TOOL_USED, dayOne, """{"format":"pdf"}""")
        )

        assertEquals(
            listOf(ExportCountRow("pdf", 2), ExportCountRow("image", 1)),
            events.toExportCounts()
        )
    }

    @Test
    fun `summary totals daily rows and keeps breakdowns`() {
        val events = listOf(
            event(StatsEvent.SCAN_CREATED, dayOne, """{"pages":2,"size_kb":50}"""),
            event(StatsEvent.SCAN_EXPORTED, dayTwo, """{"format":"pdf"}"""),
            event(StatsEvent.TOOL_USED, dayTwo, """{"tool":"ocr"}""")
        )

        val summary = events.toStatsSummary(utc)

        assertEquals(1, summary.scans)
        assertEquals(2, summary.pages)
        assertEquals(50L, summary.sizeKb)
        assertEquals(1, summary.exportedCount)
        assertEquals(1, summary.toolsTotal)
        assertEquals(listOf(ToolCountRow("ocr", 1)), summary.toolBreakdown)
        assertEquals(listOf(ExportCountRow("pdf", 1)), summary.exportBreakdown)
    }
}

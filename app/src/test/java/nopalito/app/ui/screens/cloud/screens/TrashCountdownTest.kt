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

import org.junit.Assert.*
import org.junit.Test

/**
 * Block: 8F — trash countdown decomposition.
 *
 * [trashRemainingParts] is the pure, JVM-testable extraction of the previous
 * fixed-text formatter's buckets ("2d 4h", "3h 12m", "5m 20s", "<1m"). The
 * localized string assembly lives in the composable [trashRemainingLabel].
 * Also covers the ISO timestamp parser used by the countdown screens.
 */
class TrashCountdownTest {

    private fun parts(millis: Long) = trashRemainingParts(millis)

    @Test
    fun nonPositiveMillisClampsToLessThanMinute() {
        for (millis in listOf(0L, -1L, -86_400_000L)) {
            val p = parts(millis)
            assertTrue("$millis must be less-than-minute", p.lessThanMinute)
            assertEquals(0L, p.days)
            assertEquals(0L, p.hours)
            assertEquals(0L, p.minutes)
            assertEquals(0L, p.seconds)
        }
    }

    @Test
    fun underOneMinuteIsLessThanMinute() {
        val p = parts(59_000L)
        assertTrue(p.lessThanMinute)
        assertEquals(0L, p.minutes)
    }

    @Test
    fun minutesAndSecondsBucket() {
        // 5m 20s
        val p = parts(5 * 60_000L + 20_000L)
        assertFalse(p.lessThanMinute)
        assertEquals(0L, p.days)
        assertEquals(0L, p.hours)
        assertEquals(5L, p.minutes)
        assertEquals(20L, p.seconds)
    }

    @Test
    fun hoursAndMinutesBucket() {
        // 3h 12m
        val p = parts(3 * 3_600_000L + 12 * 60_000L)
        assertFalse(p.lessThanMinute)
        assertEquals(0L, p.days)
        assertEquals(3L, p.hours)
        assertEquals(12L, p.minutes)
        assertEquals(0L, p.seconds)
    }

    @Test
    fun daysAndHoursBucket() {
        // 2d 4h
        val p = parts(2 * 86_400_000L + 4 * 3_600_000L)
        assertFalse(p.lessThanMinute)
        assertEquals(2L, p.days)
        assertEquals(4L, p.hours)
        assertEquals(0L, p.minutes)
        assertEquals(0L, p.seconds)
    }

    @Test
    fun exactHourRollsMinutesToZero() {
        // 1h 0m 0s
        val p = parts(3_600_000L)
        assertEquals(0L, p.days)
        assertEquals(1L, p.hours)
        assertEquals(0L, p.minutes)
        assertFalse(p.lessThanMinute)
    }

    @Test
    fun secondsRollIntoMinutesAtSixty() {
        // 59m 59s stays in the minutes bucket (less than an hour).
        val p = parts(59 * 60_000L + 59_000L)
        assertFalse(p.lessThanMinute)
        assertEquals(0L, p.days)
        assertEquals(0L, p.hours)
        assertEquals(59L, p.minutes)
        assertEquals(59L, p.seconds)
    }

    @Test
    fun largeValuesStayInDays() {
        val p = parts(10 * 86_400_000L + 1_000L)
        assertEquals(10L, p.days)
        assertEquals(0L, p.hours)
        assertEquals(0L, p.minutes)
        assertEquals(1L, p.seconds)
    }

    @Test
    fun isoToEpochMillisParsesBackendFormat() {
        assertEquals(0L, isoToEpochMillis("1970-01-01T00:00:00.000Z"))
        assertEquals(0L, isoToEpochMillis("1970-01-01T00:00:00Z"))
        assertEquals(null, isoToEpochMillis(null))
        assertEquals(null, isoToEpochMillis("not-a-date"))
        assertEquals(null, isoToEpochMillis(""))
    }

    @Test
    fun isoToEpochMillisParsesLegacySpaceSeparatedFormat() {
        val expected = 1_577_836_800_000L
        assertEquals(expected, isoToEpochMillis("2020-01-01 00:00:00"))
    }
}
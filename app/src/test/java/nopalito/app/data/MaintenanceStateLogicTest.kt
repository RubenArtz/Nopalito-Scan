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

package nopalito.app.data

import nopalito.app.ui.screens.cloud.model.MaintenanceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 — pure maintenance-state contract (no Android dependencies).
 *
 * Covers the staleness rules ([MaintenanceStateLogic.isStale]), the FCM
 * version guard ([MaintenanceStateLogic.applyFcm]) and the HTTP reconciliation
 * rules ([MaintenanceStateLogic.applyHttp]). Persistence itself is a thin
 * DataStore mapping exercised by instrumentation, not here.
 */
class MaintenanceStateLogicTest {

    private val nowMs: Long = 1_770_000_000_000L

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun activeState(
        version: Long = 5,
        fetchedAgoMs: Long = 0,
        startsInMs: Long? = null,
    ) = LocalMaintenanceState(
        maintenanceActive = true,
        maintenanceScheduled = false,
        maintenanceId = "m-1",
        version = version,
        title = "Window",
        startsAt = startsInMs?.let { iso(nowMs + it) },
        endsAt = iso(nowMs + 3600_000),
        retryAfter = 30,
        lastFetchedAt = if (fetchedAgoMs < 0) 0L else nowMs - fetchedAgoMs,
        source = LocalMaintenanceState.SOURCE_FCM,
    )

    private fun idleState(fetchedAgoMs: Long = 0, version: Long = 9) =
        activeState(version = version, fetchedAgoMs = fetchedAgoMs)
            .copy(maintenanceActive = false, retryAfter = 300)

    private fun scheduledState(startsInMs: Long, fetchedAgoMs: Long = 0) =
        activeState(fetchedAgoMs = fetchedAgoMs)
            .copy(
                maintenanceActive = false,
                maintenanceScheduled = true,
                startsAt = iso(nowMs + startsInMs),
            )

    private fun iso(epochMs: Long) =
        java.time.Instant.ofEpochMilli(epochMs).toString()

    private fun httpStatus(
        active: Boolean = false,
        scheduled: Boolean = false,
        version: Long = 7,
    ) = MaintenanceStatus(
        maintenanceActive = active,
        maintenanceScheduled = scheduled,
        id = "m-2",
        title = "Server title",
        message = "Server message",
        reason = null,
        type = "scheduled",
        code = "maintenance.scheduled",
        titleKey = "maintenance.scheduled_title",
        messageKey = "maintenance.scheduled_message",
        reasonKey = "maintenance.scheduled_reason",
        startsAt = iso(nowMs + 60_000),
        endsAt = iso(nowMs + 7200_000),
        timezone = "UTC",
        retryAfter = 30,
        version = version,
        updatedAt = iso(nowMs),
    )

    private fun fcmPayload(event: String, version: Long) = FcmMaintenancePayload(
        event = event,
        version = version,
        maintenanceId = "m-1",
        mKey = "emergency",
        updatedAt = iso(nowMs),
        startsAt = iso(nowMs),
        endsAt = iso(nowMs + 1800_000),
        timezone = "America/Mexico_City",
        title = "Push title",
        message = "Push message",
        reason = null,
    )

    // ─── parseIsoToEpochMs ──────────────────────────────────────────────────

    @Test
    fun `parses ISO instant and raw MySQL datetime shapes`() {
        val expected = java.time.Instant.parse("2026-08-23T14:30:00Z").toEpochMilli()
        assertEquals(expected, MaintenanceStateLogic.parseIsoToEpochMs("2026-08-23T14:30:00Z"))
        assertEquals(expected, MaintenanceStateLogic.parseIsoToEpochMs("2026-08-23 14:30:00"))
    }

    @Test
    fun `unparseable or blank timestamps return null`() {
        assertNull(MaintenanceStateLogic.parseIsoToEpochMs(null))
        assertNull(MaintenanceStateLogic.parseIsoToEpochMs(""))
        assertNull(MaintenanceStateLogic.parseIsoToEpochMs("not-a-date"))
    }

    // ─── maxAge / isStale ───────────────────────────────────────────────────

    @Test
    fun `active max age follows retry_after clamped to 30-60s`() {
        val low = activeState().copy(retryAfter = 30)
        val high = activeState().copy(retryAfter = 120)
        assertEquals(30_000L, MaintenanceStateLogic.maxAgeMs(low, nowMs))
        assertEquals(60_000L, MaintenanceStateLogic.maxAgeMs(high, nowMs))
    }

    @Test
    fun `scheduled max age is min of 5 minutes and one tenth of time until start`() {
        // Starts in 1000 s -> 100 s window.
        assertEquals(
            100_000L,
            MaintenanceStateLogic.maxAgeMs(scheduledState(startsInMs = 1000_000L), nowMs)
        )
        // Starts far away -> capped at 300 s.
        assertEquals(
            300_000L,
            MaintenanceStateLogic.maxAgeMs(scheduledState(startsInMs = 10_000_000L), nowMs)
        )
        // Imminent start -> floored at 10 s so we never hammer the endpoint.
        assertEquals(
            10_000L,
            MaintenanceStateLogic.maxAgeMs(scheduledState(startsInMs = 60_000L), nowMs)
        )
    }

    @Test
    fun `idle max age is 24 hours`() {
        assertEquals(86_400_000L, MaintenanceStateLogic.maxAgeMs(idleState(), nowMs))
    }

    @Test
    fun `never-fetched state is always stale`() {
        val never = LocalMaintenanceState.EMPTY.copy(maintenanceActive = true)
        assertTrue(MaintenanceStateLogic.isStale(never, nowMs))
    }

    @Test
    fun `fresh state within its max age is not stale, expired is stale`() {
        assertTrue(!MaintenanceStateLogic.isStale(activeState(fetchedAgoMs = 20_000L), nowMs))
        assertTrue(MaintenanceStateLogic.isStale(activeState(fetchedAgoMs = 31_000L), nowMs))
        // Idle snapshot taken an hour ago is still perfectly fine.
        assertFalse(MaintenanceStateLogic.isStale(idleState(fetchedAgoMs = 3600_000L), nowMs))
    }

    // ─── parseFcmPayload ────────────────────────────────────────────────────

    @Test
    fun `parses a well-formed maintenance data payload`() {
        val payload = MaintenanceStateLogic.parseFcmPayload(
            mapOf(
                "type" to "maintenance",
                "event" to "activated",
                "version" to "7",
                "maintenance_id" to "m-1",
                "m_key" to "scheduled",
                "title" to "t",
            )
        )
        assertNotNull(payload)
        assertEquals("activated", payload!!.event)
        assertEquals(7L, payload.version)
        assertEquals("m-1", payload.maintenanceId)
    }

    @Test
    fun `malformed payloads are rejected`() {
        val base = mapOf("type" to "maintenance", "event" to "activated", "version" to "3")
        assertNull(MaintenanceStateLogic.parseFcmPayload(base - "type")) // not maintenance
        assertNull(
            MaintenanceStateLogic.parseFcmPayload(
                mapOf(
                    "type" to "maintenance",
                    "version" to "3"
                )
            )
        )
        assertNull(MaintenanceStateLogic.parseFcmPayload(base + ("version" to "NaN")))
    }

    @Test
    fun `correlation_id and unknown extra keys never break parsing`() {
        // Phase 3: the payload may carry correlation_id plus future unknown
        // keys; odd strings must survive untouched (metadata only).
        val payload = MaintenanceStateLogic.parseFcmPayload(
            mapOf(
                "type" to "maintenance",
                "event" to "completed",
                "version" to "8",
                "correlation_id" to "weird id with spaces/ünicode",
                "some_future_key" to "<script>alert(1)</script>",
            )
        )
        assertNotNull(payload)
        assertEquals("completed", payload!!.event)
        assertEquals(8L, payload.version)

        // And applying it is a no-crash no-op decision like any other push.
        val current = activeState(version = 8, fetchedAgoMs = 1000)
        assertNull(MaintenanceStateLogic.applyFcm(current, payload, nowMs)) // dup version
    }

    // ─── applyFcm (version guard + flag semantics) ─────────────────────────

    @Test
    fun `newer activated push activates and carries push metadata`() {
        val next = MaintenanceStateLogic.applyFcm(
            activeState(version = 5),
            fcmPayload("activated", 6),
            nowMs
        )
        assertNotNull(next)
        next!!.let {
            assertTrue(it.maintenanceActive)
            assertFalse(it.maintenanceScheduled)
            assertEquals(6L, it.version)
            assertEquals("fcm", it.source)
            assertEquals(nowMs, it.lastFetchedAt)
            assertEquals("Push title", it.title)
            assertEquals("emergency", it.type)
            assertEquals(LocalMaintenanceState.RETRY_AFTER_ACTIVE_SECONDS, it.retryAfter)
        }
    }

    @Test
    fun `completed and cancelled clear flags but keep record metadata`() {
        val before = activeState(version = 5)
        val done = MaintenanceStateLogic.applyFcm(before, fcmPayload("completed", 6), nowMs)!!
        assertFalse(done.maintenanceActive)
        assertFalse(done.maintenanceScheduled)
        assertEquals("m-1", done.maintenanceId)
        assertEquals("Push title", done.title)
        assertEquals(LocalMaintenanceState.RETRY_AFTER_IDLE_SECONDS, done.retryAfter)

        val cancelled = MaintenanceStateLogic.applyFcm(before, fcmPayload("cancelled", 6), nowMs)!!
        assertFalse(cancelled.maintenanceActive)
        assertFalse(cancelled.maintenanceScheduled)
    }

    @Test
    fun `created and pre_notification mark scheduled without activating`() {
        val next =
            MaintenanceStateLogic.applyFcm(idleState(), fcmPayload("pre_notification", 10), nowMs)!!
        assertFalse(next.maintenanceActive)
        assertTrue(next.maintenanceScheduled)
    }

    @Test
    fun `old or duplicate versions are discarded once a state exists`() {
        val current = activeState(version = 7, fetchedAgoMs = 1000)
        assertNull(MaintenanceStateLogic.applyFcm(current, fcmPayload("activated", 6), nowMs))
        assertNull(MaintenanceStateLogic.applyFcm(current, fcmPayload("activated", 7), nowMs))
    }

    @Test
    fun `fresh install accepts any version including zero`() {
        val next = MaintenanceStateLogic.applyFcm(
            LocalMaintenanceState.EMPTY,
            fcmPayload("created", 0),
            nowMs,
        )
        assertNotNull(next)
        assertTrue(next!!.maintenanceScheduled)
        assertEquals(0L, next.version)
    }

    @Test
    fun `unknown events refresh metadata without touching flags`() {
        val current = activeState(version = 5)
        val next = MaintenanceStateLogic.applyFcm(current, fcmPayload("mystery", 6), nowMs)!!
        assertTrue(next.maintenanceActive) // unchanged
        assertEquals(6L, next.version)
        assertEquals("Push message", next.message)
    }

    // ─── applyHttp (reconciliation) ─────────────────────────────────────────

    @Test
    fun `http snapshot always reconciles state and fields`() {
        val startedAt = nowMs - 500
        val next = MaintenanceStateLogic.applyHttp(
            idleState(fetchedAgoMs = 4000_000L, version = 3),
            httpStatus(active = true, version = 7),
            requestStartedAtMs = startedAt,
            nowMs = nowMs,
            source = LocalMaintenanceState.SOURCE_HTTP_RECONCILE,
        )!!
        assertTrue(next.maintenanceActive)
        assertEquals(7L, next.version)
        assertEquals("reconcile", next.source)
        assertEquals("Server title", next.title)
        assertEquals("maintenance.scheduled_title", next.titleKey)
        assertEquals(30, next.retryAfter)
    }

    @Test
    fun `snapshot that started before a newer update landed is discarded`() {
        val current = activeState(version = 9, fetchedAgoMs = 100) // written after the fetch began
        val result = MaintenanceStateLogic.applyHttp(
            current,
            httpStatus(active = false, version = 3),
            requestStartedAtMs = nowMs - 5000,
            nowMs = nowMs,
            source = LocalMaintenanceState.SOURCE_HTTP_RECONCILE,
        )
        assertNull(result)
    }

    @Test
    fun `empty http response clears flags, keeps metadata and stored version`() {
        // Snapshot must predate the in-flight request (fetchedAgo > request age).
        val before = activeState(version = 7, fetchedAgoMs = 5000)
        val next = MaintenanceStateLogic.applyHttp(
            before,
            null,
            requestStartedAtMs = nowMs - 1000,
            nowMs = nowMs,
            source = LocalMaintenanceState.SOURCE_HTTP_BOOTSTRAP,
        )!!
        assertFalse(next.maintenanceActive)
        assertFalse(next.maintenanceScheduled)
        assertEquals(7L, next.version) // never lowered by a versionless response
        assertEquals("m-1", next.maintenanceId)
        assertEquals(LocalMaintenanceState.RETRY_AFTER_IDLE_SECONDS, next.retryAfter)
        assertEquals("bootstrap", next.source)
    }

    @Test
    fun `old backend without version field never lowers the stored version`() {
        val next = MaintenanceStateLogic.applyHttp(
            idleState(version = 12, fetchedAgoMs = 5000),
            httpStatus(active = true, version = 0),
            requestStartedAtMs = nowMs - 1000,
            nowMs = nowMs,
            source = LocalMaintenanceState.SOURCE_HTTP_RECONCILE,
        )!!
        assertEquals(12L, next.version)
    }

    @Test
    fun `http retry_after is clamped into safe bounds`() {
        val tiny = MaintenanceStateLogic.applyHttp(
            LocalMaintenanceState.EMPTY, httpStatus(retryAfterOverride = 1),
            nowMs - 100, nowMs, LocalMaintenanceState.SOURCE_HTTP_BOOTSTRAP,
        )!!
        val huge = MaintenanceStateLogic.applyHttp(
            LocalMaintenanceState.EMPTY, httpStatus(retryAfterOverride = 99999),
            nowMs - 100, nowMs, LocalMaintenanceState.SOURCE_HTTP_BOOTSTRAP,
        )!!
        assertEquals(10, tiny.retryAfter)
        assertEquals(600, huge.retryAfter)
    }

    private fun httpStatus(retryAfterOverride: Int): MaintenanceStatus =
        httpStatus().copy(retryAfter = retryAfterOverride)

    // ─── scheduling helpers ─────────────────────────────────────────────────

    @Test
    fun `next check delay backs off on consecutive failures`() {
        // Active state (retry_after 30 s): 60 s, then 120 s...
        val delay1 = MaintenanceStateLogic.nextCheckDelayMs(activeState(), nowMs, 1)
        val delay2 = MaintenanceStateLogic.nextCheckDelayMs(activeState(), nowMs, 2)
        assertTrue(delay2 > delay1)
        assertEquals(300_000L, MaintenanceStateLogic.nextCheckDelayMs(activeState(), nowMs, 5))
    }

    @Test
    fun `never-fetched state checks immediately, fresh idle sleeps long`() {
        assertEquals(
            0L,
            MaintenanceStateLogic.nextCheckDelayMs(LocalMaintenanceState.EMPTY, nowMs, 0),
        )
        val fresh = idleState(fetchedAgoMs = 60_000)
        val delay = MaintenanceStateLogic.nextCheckDelayMs(fresh, nowMs, 0)
        assertTrue(delay > 86_000_000L) // ~23.9h left of the 24h TTL
    }

    @Test
    fun `backoff doubles per failure up to the cap`() {
        assertEquals(30L, MaintenanceStateLogic.backoffIntervalSeconds(30, 0))
        assertEquals(60L, MaintenanceStateLogic.backoffIntervalSeconds(30, 1))
        assertEquals(240L, MaintenanceStateLogic.backoffIntervalSeconds(30, 3))
        assertEquals(300L, MaintenanceStateLogic.backoffIntervalSeconds(30, 4))
        assertEquals(300L, MaintenanceStateLogic.backoffIntervalSeconds(600, 0)) // base above cap
    }
}

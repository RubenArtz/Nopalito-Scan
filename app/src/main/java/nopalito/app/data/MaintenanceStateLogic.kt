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

/**
 * Client-side operational state of cloud maintenance (Phase 2 architecture).
 *
 * The FCM data message (`type: maintenance`) is the PRIMARY change channel;
 * this persisted snapshot is the operational source the UI reads. The public
 * HTTP endpoint is only a bootstrap/reconciliation fallback.
 *
 * Versioning contract (mirrors backend `maintenance.version`, bumped on every
 * lifecycle transition):
 *  - [MaintenanceStateLogic.applyFcm] applies a push ONLY when its version is
 *    newer than the stored one, so duplicate or out-of-order deliveries can
 *    never roll state backwards.
 *  - [MaintenanceStateLogic.applyHttp] always applies an HTTP reconciliation
 *    snapshot EXCEPT when a newer update landed while the request was in
 *    flight (detected via the request start timestamp).
 *  - Terminal events (`completed` / `cancelled`) clear the blocking flags but
 *    KEEP the record metadata, so a late duplicate of the old push is still
 *    rejected by version comparison.
 */
data class LocalMaintenanceState(
    val maintenanceActive: Boolean = false,
    val maintenanceScheduled: Boolean = false,
    val maintenanceId: String? = null,
    /** Server-side monotonic state version (0 = unknown / never fetched). */
    val version: Long = 0L,
    /** Server timestamp of the last change (ISO-8601, e.g. 2026-08-23T14:30:00Z). */
    val updatedAt: String? = null,
    val title: String? = null,
    val message: String? = null,
    val reason: String? = null,
    val type: String? = null,
    val code: String? = null,
    val titleKey: String? = null,
    val messageKey: String? = null,
    val reasonKey: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val timezone: String? = null,
    /** Seconds the server asks the client to wait before its next poll. */
    val retryAfter: Int = RETRY_AFTER_IDLE_SECONDS,
    /** Local epoch-ms when this state was written (0 = never fetched). */
    val lastFetchedAt: Long = 0L,
    /** Which channel wrote this state (see [MaintenanceStateLogic] SOURCE_*). */
    val source: String = SOURCE_NONE,
) {
    companion object {
        val EMPTY = LocalMaintenanceState()

        // Polling guidance constants shared by the store, the ViewModel and
        // the pure helpers below (all values in seconds).
        const val RETRY_AFTER_ACTIVE_SECONDS = 30
        const val RETRY_AFTER_IDLE_SECONDS = 300
        const val MIN_POLL_INTERVAL_SECONDS = 10L
        const val MAX_POLL_INTERVAL_SECONDS = 600L
        const val MAX_BACKOFF_SECONDS = 300L

        // Provenance of a state write.
        const val SOURCE_NONE = "none"
        const val SOURCE_FCM = "fcm"
        const val SOURCE_HTTP_BOOTSTRAP = "bootstrap"
        const val SOURCE_HTTP_OFFLINE = "bootstrap_offline"
        const val SOURCE_HTTP_RECONCILE = "reconcile"
        const val SOURCE_HTTP_MANUAL = "manual"

        // FCM lifecycle events.
        const val EVENT_CREATED = "created"
        const val EVENT_PRE_NOTIFICATION = "pre_notification"
        const val EVENT_ACTIVATED = "activated"
        const val EVENT_COMPLETED = "completed"
        const val EVENT_CANCELLED = "cancelled"
    }
}

/**
 * Parsed, validated view of one FCM `type: maintenance` data payload
 * (contract built by the backend's `buildMaintenancePushData`). Every FCM
 * data value arrives as a string.
 */
data class FcmMaintenancePayload(
    val event: String,
    val version: Long,
    val maintenanceId: String?,
    val mKey: String?,
    val updatedAt: String?,
    val startsAt: String?,
    val endsAt: String?,
    val timezone: String?,
    val title: String?,
    val message: String?,
    val reason: String?,
)

/**
 * Pure, deterministic state-transition math for [LocalMaintenanceState].
 *
 * No Android dependencies: everything here runs on the JVM so it can be unit
 * tested directly (see MaintenanceStateLogicTest). Persistence lives in
 * [MaintenanceStateStore]; rendering stays in the existing UI layer
 * (MaintenanceLocalizer et al.), which consumes the mapped model untouched.
 */
object MaintenanceStateLogic {

    private const val MS_PER_SECOND = 1000L
    private const val STALE_MAX_AGE_IDLE_MS = 24L * 60 * 60 * MS_PER_SECOND
    private const val STALE_MAX_AGE_SCHEDULED_MS = 300L * MS_PER_SECOND
    private const val STALE_MIN_MS = 10_000L

    /**
     * Parses an ISO-8601 instant ("2026-08-23T14:30:00Z") or the raw MySQL
     * datetime shape the FCM starts_at/ends_at fields still carry
     * ("2026-08-23 14:30:00", stored UTC). Returns null when unparseable.
     */
    fun parseIsoToEpochMs(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            var s = value.trim().replace(' ', 'T')
            if (!s.endsWith("Z")) {
                if (s.length == 16) s += ":00" // "yyyy-MM-ddTHH:mm"
                s += "Z"
            }
            java.time.Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * How long a state may be trusted before it counts as stale:
     *  - active       -> retry_after clamped to [30s, 60s]
     *  - scheduled    -> min(300s, timeUntilStarts/10) floored at 10s
     *  - neither      -> 24h (FCM is the primary channel; idle barely polls)
     */
    fun maxAgeMs(state: LocalMaintenanceState, nowMs: Long): Long {
        return when {
            state.maintenanceActive ->
                state.retryAfter.coerceIn(30, 60).toLong() * MS_PER_SECOND

            state.maintenanceScheduled -> {
                val startMs = parseIsoToEpochMs(state.startsAt)
                    ?: return STALE_MAX_AGE_SCHEDULED_MS
                val untilStartSeconds = (startMs - nowMs) / MS_PER_SECOND
                minOf(
                    STALE_MAX_AGE_SCHEDULED_MS / MS_PER_SECOND,
                    untilStartSeconds / 10,
                ).coerceAtLeast(STALE_MIN_MS / MS_PER_SECOND) * MS_PER_SECOND
            }

            else -> STALE_MAX_AGE_IDLE_MS
        }
    }

    /** True when there is no usable state ([lastFetchedAt]==0) or it expired. */
    fun isStale(state: LocalMaintenanceState, nowMs: Long): Boolean {
        if (state.lastFetchedAt <= 0L) return true
        return nowMs - state.lastFetchedAt > maxAgeMs(state, nowMs)
    }

    /**
     * Parses the raw FCM data map into a validated payload, or null when it
     * is not a well-formed maintenance message (missing type/event/version).
     */
    fun parseFcmPayload(data: Map<String, String>): FcmMaintenancePayload? {
        if (data["type"] != "maintenance") return null
        val event = data["event"]?.trim().orEmpty()
        if (event.isEmpty()) return null
        val version = data["version"]?.trim()?.toLongOrNull() ?: return null
        fun field(key: String): String? = data[key]?.trim()?.takeIf { it.isNotEmpty() }
        return FcmMaintenancePayload(
            event = event,
            version = version,
            maintenanceId = field("maintenance_id"),
            mKey = field("m_key"),
            updatedAt = field("updated_at"),
            startsAt = field("starts_at"),
            endsAt = field("ends_at"),
            timezone = field("timezone"),
            title = field("title"),
            message = field("message"),
            reason = field("reason"),
        )
    }

    /**
     * Applies one maintenance push to [current]. Returns the new state, or
     * null when the push must be DISCARDED (malformed handled by the parser;
     * here: old/duplicate version on a device that already has any state).
     *
     * Fresh installs accept any version (even 0 from a `created` push):
     * with no local state there is nothing an equal version could overwrite.
     *
     * Flag semantics per lifecycle event:
     *  - activated                  -> active, not scheduled
     *  - completed / cancelled      -> neither (metadata kept for versioning)
     *  - created / pre_notification -> scheduled, not active
     *  - anything else              -> flags unchanged, metadata refreshed
     */
    fun applyFcm(
        current: LocalMaintenanceState,
        payload: FcmMaintenancePayload,
        nowMs: Long,
    ): LocalMaintenanceState? {
        if (current.lastFetchedAt != 0L && payload.version <= current.version) {
            return null
        }

        val active: Boolean
        val scheduled: Boolean
        when (payload.event) {
            LocalMaintenanceState.EVENT_ACTIVATED -> {
                active = true
                scheduled = false
            }

            LocalMaintenanceState.EVENT_COMPLETED,
            LocalMaintenanceState.EVENT_CANCELLED,
                -> {
                active = false
                scheduled = false
            }

            LocalMaintenanceState.EVENT_CREATED,
            LocalMaintenanceState.EVENT_PRE_NOTIFICATION,
                -> {
                active = false
                scheduled = true
            }

            else -> {
                active = current.maintenanceActive
                scheduled = current.maintenanceScheduled
            }
        }

        return current.copy(
            maintenanceActive = active,
            maintenanceScheduled = scheduled,
            maintenanceId = payload.maintenanceId ?: current.maintenanceId,
            version = payload.version,
            updatedAt = payload.updatedAt ?: current.updatedAt,
            title = payload.title ?: current.title,
            message = payload.message ?: current.message,
            reason = payload.reason ?: current.reason,
            type = payload.mKey ?: current.type,
            // The push contract carries no code/*_key fields: keep whatever an
            // earlier HTTP snapshot stored (null on fresh installs).
            code = current.code,
            titleKey = current.titleKey,
            messageKey = current.messageKey,
            reasonKey = current.reasonKey,
            startsAt = payload.startsAt ?: current.startsAt,
            endsAt = payload.endsAt ?: current.endsAt,
            timezone = payload.timezone ?: current.timezone,
            retryAfter = if (active || scheduled) {
                LocalMaintenanceState.RETRY_AFTER_ACTIVE_SECONDS
            } else {
                LocalMaintenanceState.RETRY_AFTER_IDLE_SECONDS
            },
            lastFetchedAt = nowMs,
            source = LocalMaintenanceState.SOURCE_FCM,
        )
    }

    /**
     * Applies an HTTP reconciliation snapshot ([status] as delivered by
     * GET /api/maintenance/status; null means "no maintenance"). Returns the
     * new state, or null when the snapshot must be discarded because a newer
     * update (typically an FCM push) landed while the request was in flight:
     * [requestStartedAtMs] predates [LocalMaintenanceState.lastFetchedAt].
     *
     * Unlike pushes, HTTP snapshots are authoritative reconciliations and are
     * applied regardless of version — except that an old backend (no version
     * field yet) must never LOWER the stored version, which would re-open the
     * door to stale pushes.
     */
    fun applyHttp(
        current: LocalMaintenanceState,
        status: MaintenanceStatus?,
        requestStartedAtMs: Long,
        nowMs: Long,
        source: String,
    ): LocalMaintenanceState? {
        // Any state written AFTER this request started (an FCM push or a
        // faster sibling fetch) is strictly newer than this in-flight
        // snapshot: discard it instead of letting it roll state backwards.
        if (current.lastFetchedAt > requestStartedAtMs) {
            return null
        }

        if (status == null) {
            // No maintenance anywhere: clear the blocking flags, KEEP record
            // metadata and the highest known version (same rule as terminal
            // events, so late duplicates stay rejected).
            return current.copy(
                maintenanceActive = false,
                maintenanceScheduled = false,
                retryAfter = LocalMaintenanceState.RETRY_AFTER_IDLE_SECONDS,
                lastFetchedAt = nowMs,
                source = source,
            )
        }

        return current.copy(
            maintenanceActive = status.maintenanceActive,
            maintenanceScheduled = status.maintenanceScheduled,
            maintenanceId = status.id ?: current.maintenanceId,
            version = maxOf(status.version, current.version),
            updatedAt = status.updatedAt ?: current.updatedAt,
            title = status.title ?: current.title,
            message = status.message ?: current.message,
            reason = status.reason ?: current.reason,
            type = status.type ?: current.type,
            code = status.code ?: current.code,
            titleKey = status.titleKey ?: current.titleKey,
            messageKey = status.messageKey ?: current.messageKey,
            reasonKey = status.reasonKey ?: current.reasonKey,
            startsAt = status.startsAt ?: current.startsAt,
            endsAt = status.endsAt ?: current.endsAt,
            timezone = status.timezone ?: current.timezone,
            retryAfter = status.retryAfter
                .coerceIn(
                    LocalMaintenanceState.MIN_POLL_INTERVAL_SECONDS.toInt(),
                    LocalMaintenanceState.MAX_POLL_INTERVAL_SECONDS.toInt(),
                ),
            lastFetchedAt = nowMs,
            source = source,
        )
    }

    /**
     * Delay until the next staleness-driven check while the maintenance VM is
     * alive. TTL-based, not blind: fresh idle state sleeps ~24h (effectively
     * no polling); failures back off exponentially instead of hammering.
     * Pure function (unit-testable).
     */
    fun nextCheckDelayMs(
        state: LocalMaintenanceState,
        nowMs: Long,
        consecutiveFailures: Int,
    ): Long {
        if (consecutiveFailures > 0) {
            return backoffIntervalSeconds(state.retryAfter, consecutiveFailures) * MS_PER_SECOND
        }
        if (state.lastFetchedAt <= 0L) return 0L // never fetched: check immediately
        val maxAge = maxAgeMs(state, nowMs)
        val elapsed = nowMs - state.lastFetchedAt
        val remaining = maxAge - elapsed
        return remaining.coerceIn(STALE_MIN_MS, STALE_MAX_AGE_IDLE_MS)
    }

    /** Clamps the server-provided retry_after into safe bounds (pure). */
    fun clampedInterval(retryAfter: Int?): Long =
        (retryAfter?.toLong() ?: LocalMaintenanceState.RETRY_AFTER_ACTIVE_SECONDS.toLong())
            .coerceIn(
                LocalMaintenanceState.MIN_POLL_INTERVAL_SECONDS,
                LocalMaintenanceState.MAX_POLL_INTERVAL_SECONDS,
            )

    /**
     * Exponential backoff for consecutive failures (x2 starting from the
     * first failure, capped). Pure function (unit-testable).
     */
    fun backoffIntervalSeconds(baseRetryAfterSeconds: Int, consecutiveFailures: Int): Long {
        val base = clampedInterval(baseRetryAfterSeconds)
        if (consecutiveFailures <= 0) return base.coerceAtMost(LocalMaintenanceState.MAX_BACKOFF_SECONDS)
        val shift = minOf(consecutiveFailures, 4)
        return ((base shl shift).coerceAtMost(LocalMaintenanceState.MAX_BACKOFF_SECONDS))
    }
}

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

package nopalito.app.ui.screens.cloud.model

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// ====== Cloud Link (Receive scans on your PC, Phase 2) ======

/** POST /api/cloud-link/intents/approve — approve by scanning the web QR. */
data class ApproveQrRequest(
    @SerializedName("intent_id") val intentId: String,
    @SerializedName("qr_nonce") val qrNonce: String
)

/** POST /api/cloud-link/intents/approve-by-pin — approve by typing the web PIN. */
data class ApproveByPinRequest(
    @SerializedName("pin") val pin: String,
    @SerializedName("intent_id") val intentId: String? = null
)

/** One linked browser returned by GET /api/cloud-link/sessions. */
data class CloudLinkSession(
    @SerializedName("id") val id: String,
    @SerializedName("webDeviceId") val webDeviceId: String? = null,
    @SerializedName("web_device_id") val webDeviceIdSnake: String? = null,
    @SerializedName("lastSeenAt") val lastSeenAt: String? = null,
    @SerializedName("last_seen_at") val lastSeenAtSnake: String? = null,
    @SerializedName("ipAddress") val ipAddress: String? = null,
    @SerializedName("ip_address") val ipAddressSnake: String? = null,
    @SerializedName("userAgent") val userAgent: String? = null,
    @SerializedName("user_agent") val userAgentSnake: String? = null,
    @SerializedName("idleExpiresAt") val idleExpiresAt: String? = null,
    @SerializedName("idle_expires_at") val idleExpiresAtSnake: String? = null,
    @SerializedName("absoluteExpiresAt") val absoluteExpiresAt: String? = null,
    @SerializedName("absolute_expires_at") val absoluteExpiresAtSnake: String? = null,
    @SerializedName("createdAt") val createdAt: String? = null,
    @SerializedName("created_at") val createdAtSnake: String? = null
) {
    /** Backend may answer camelCase or snake_case; prefer whichever is set. */
    val deviceLabel: String get() = webDeviceId ?: webDeviceIdSnake ?: id.take(8)
    val lastSeenLabel: String? get() = lastSeenAt ?: lastSeenAtSnake
    val ipLabel: String? get() = ipAddress ?: ipAddressSnake
    val idleExpiresLabel: String? get() = idleExpiresAt ?: idleExpiresAtSnake
    val absoluteExpiresLabel: String? get() = absoluteExpiresAt ?: absoluteExpiresAtSnake

    /** Effective expiry is the sooner of idle and absolute. */
    val effectiveExpiresAt: String?
        get() {
            val idle = idleExpiresLabel
            val abs = absoluteExpiresLabel
            if (idle == null) return abs
            if (abs == null) return idle
            return if (idle < abs) idle else abs
        }
}

data class CloudLinkSessionsData(
    @SerializedName("sessions") val sessions: List<CloudLinkSession> = emptyList(),
    @SerializedName("activeCount") val activeCount: Int = 0
)

/**
 * Server-derived PC-link state for the pairing screen.
 *
 * Every countdown on screen comes from the backend
 * ([CloudLinkSession.effectiveExpiresAt] = min of the sliding 24h idle
 * window and the absolute ceiling). The app never invents a local 24h
 * countdown: a guessed clock diverged from the panel before. When no expiry
 * is known the UI shows "active" instead of a fabricated timer.
 */
sealed interface PcLinkState {
    /** No approval in flight and no sessions server-side. */
    data object NotLinked : PcLinkState

    /**
     * Approval sent from the app, but the web session row appears only
     * after the PC polls (2s) and exchanges the intent — the first
     * listSessions after approve is therefore legitimately empty.
     */
    data object Pending : PcLinkState

    /** At least one live session returned by the backend. */
    data object Linked : PcLinkState

    /**
     * A previously visible session vanished server-side (24h idle timeout,
     * unlink from the panel, or concurrent-session eviction). listSessions
     * only returns live rows, so vanish-vs-revoke cannot be told apart here;
     * both mean "pair again".
     */
    data object Expired : PcLinkState

    /** Last refresh failed on transport; shown devices may be stale. */
    data object Offline : PcLinkState
}

/**
 * Parsed web QR payload: {"v":1,"intent":"uuid","nonce":"uuid","exp":sec,"sig":"..."}.
 * The signature is verified server-side on approve; the app only checks the
 * shape, the version and the local expiry before spending an API call.
 */
data class QrLinkPayload(
    val intentId: String,
    val nonce: String,
    val expiresAtSec: Long
) {
    companion object {
        const val VERSION = 1

        /** Returns null when the payload is not a Cloud Link QR code. */
        fun parse(raw: String?): QrLinkPayload? {
            if (raw.isNullOrBlank()) return null
            return try {
                // kotlinx.serialization (not org.json): the parser also runs
                // on plain JVM unit tests where android.jar is stubbed.
                val json = kotlinx.serialization.json.Json
                    .parseToJsonElement(raw.trim()).jsonObject
                if (json["v"]?.jsonPrimitive?.intOrNull != VERSION) return null
                val intent = json["intent"]?.jsonPrimitive
                    ?.takeIf { it.isString }?.content?.trim().orEmpty()
                val nonce = json["nonce"]?.jsonPrimitive
                    ?.takeIf { it.isString }?.content?.trim().orEmpty()
                val exp = json["exp"]?.jsonPrimitive?.longOrNull ?: -1L
                if (intent.isEmpty() || nonce.isEmpty() || exp <= 0) return null
                QrLinkPayload(intent, nonce, exp)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** True when the QR window already passed on this device clock. */
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtSec * 1000L <= nowMs
}

/** Sync state persisted for the status card. */
enum class PcLinkSyncStatus { IDLE, UPLOADING, SYNCED, ERROR }

/**
 * Normalized Cloud Link failures. HTTP mapping lives in
 * [nopalito.app.ui.screens.cloud.data.PcLinkRepository]; UI text resolution
 * lives in [nopalito.app.ui.screens.cloud.data.ErrorCodeMapper].
 *
 * Extends [Exception] so calls can surface failures as [Result.failure].
 *
 * Keep the parameterless entries as data object: the IDE "Convert to data
 * class" refactoring breaks compilation and the singleton identity matched
 * by exhaustive when in PcLinkRepository and the UI.
 */
sealed class PcLinkError : Exception() {
    data object IntentNotFound : PcLinkError()
    data object IntentExpired : PcLinkError()
    data object IntentBlocked : PcLinkError()
    data object IntentInvalidState : PcLinkError()
    data object UserMismatch : PcLinkError()
    data object RateLimited : PcLinkError()
    data object NetworkError : PcLinkError()
    data object Unauthorized : PcLinkError()
    data class Unknown(val debugCode: String? = null) : PcLinkError()
}

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

package nopalito.app.ui.screens.cloud.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nopalito.app.ui.screens.cloud.data.PcLinkRepository
import nopalito.app.ui.screens.cloud.model.CloudLinkSession
import nopalito.app.ui.screens.cloud.model.PcLinkError
import nopalito.app.ui.screens.cloud.model.PcLinkState
import nopalito.app.ui.screens.cloud.model.PcLinkSyncStatus
import nopalito.app.ui.screens.cloud.model.QrLinkPayload
import kotlin.time.Duration.Companion.milliseconds

/** Header badge: gray (none), green (paired), amber (server says stale). */
enum class PcLinkBadge { NOT_LINKED, LINKED, STALE }

enum class PcLinkTab { SCAN_QR, ENTER_PIN }

data class PcLinkUiState(
    val tab: PcLinkTab = PcLinkTab.SCAN_QR,
    val badge: PcLinkBadge = PcLinkBadge.NOT_LINKED,
    /** Server-derived pairing state driving the status note (see PcLinkState). */
    val pcState: PcLinkState = PcLinkState.NotLinked,
    /** True once any refresh observed a live session (detects vanish → Expired). */
    val hadDevices: Boolean = false,
    val pin: String = "",
    val isApproving: Boolean = false,
    val approveError: PcLinkError? = null,
    /** Set once an approval succeeds (drives the success card). */
    val approvedIntentId: String? = null,
    /** Consecutive failed PIN approvals (cooldown after [PIN_MAX_FAILURES]). */
    val pinFailures: Int = 0,
    /** Epoch ms until which the PIN form is locked. */
    val pinLockedUntilMs: Long = 0L,
    val devices: List<CloudLinkSession> = emptyList(),
    val devicesLoading: Boolean = false,
    val devicesError: PcLinkError? = null,
    val revokingId: String? = null,
    val lastUploadAtMs: Long? = null,
    val lastSyncStatus: PcLinkSyncStatus = PcLinkSyncStatus.IDLE
)

/**
 * Cloud Link pairing (Receive scans on your PC).
 *
 * Owns the QR/PIN approval flow, the linked-browser list and the header
 * badge. The pairing receipt (intent id, never a secret) persists in
 * PcLinkDataStore; the visible state ([PcLinkState]) is always derived from
 * the latest server answer, never from a local clock:
 * - approve + empty list = Pending (the PC has not exchanged yet);
 * - sessions vanished after being seen = Expired (idle timeout, panel
 *   unlink, or eviction — the API only returns live rows);
 * - transport failure = Offline (keeps last devices);
 * - 401 here concerns the MOBILE auth token, not the PC session, so the
 *   local receipt is kept and the badge flips to STALE (amber) until the
 *   mobile session (or a fresh pairing) resolves it.
 */
class PcLinkViewModel(
    private val application: Application,
    private val repository: PcLinkRepository
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PcLinkUiState())
    val state: StateFlow<PcLinkUiState> = _state.asStateFlow()

    private var approveJob: Job? = null

    init {
        viewModelScope.launch {
            repository.linkState().collect { persisted ->
                _state.value = _state.value.copy(
                    badge = if (persisted.linkSessionId != null) {
                        if (_state.value.badge == PcLinkBadge.STALE) {
                            PcLinkBadge.STALE
                        } else {
                            PcLinkBadge.LINKED
                        }
                    } else {
                        PcLinkBadge.NOT_LINKED
                    },
                    lastUploadAtMs = persisted.lastUploadAt,
                    lastSyncStatus = persisted.lastSyncStatus
                )
            }
        }
        refreshDevices()
    }

    fun selectTab(tab: PcLinkTab) {
        _state.value = _state.value.copy(tab = tab, approveError = null)
    }

    fun onPinChanged(pin: String) {
        val digits = pin.filter { it.isDigit() }.take(PIN_LENGTH)
        _state.value = _state.value.copy(pin = digits, approveError = null)
    }

    /** QR frame decoded by the scanner — at most one approval in flight. */
    fun onQrDetected(raw: String) {
        if (_state.value.isApproving || _state.value.approvedIntentId != null) return
        val payload = QrLinkPayload.parse(raw)
        if (payload == null) {
            _state.value = _state.value.copy(approveError = PcLinkError.IntentNotFound)
            return
        }
        if (payload.isExpired()) {
            _state.value = _state.value.copy(approveError = PcLinkError.IntentExpired)
            return
        }
        approveJob?.cancel()
        approveJob = viewModelScope.launch {
            _state.value = _state.value.copy(isApproving = true, approveError = null)
            val result = repository.approveQr(payload.intentId, payload.nonce)
            onApprovalResult(result, receiptId = payload.intentId, receiptDevice = payload.nonce)
        }
    }

    fun approvePin() {
        val pin = _state.value.pin
        if (pin.length != PIN_LENGTH || _state.value.isApproving) return
        if (System.currentTimeMillis() < _state.value.pinLockedUntilMs) return
        approveJob?.cancel()
        approveJob = viewModelScope.launch {
            _state.value = _state.value.copy(isApproving = true, approveError = null)
            val result = repository.approveByPin(pin)
            val failures = if (result.isSuccess) 0 else _state.value.pinFailures + 1
            _state.value = _state.value.copy(
                pinFailures = failures,
                pinLockedUntilMs = if (failures >= PIN_MAX_FAILURES) {
                    System.currentTimeMillis() + PIN_LOCKOUT_MS
                } else {
                    _state.value.pinLockedUntilMs
                }
            )
            onApprovalResult(result, receiptId = "pin", receiptDevice = "pin")
        }
    }

    fun dismissApproved() {
        _state.value = _state.value.copy(approvedIntentId = null, pin = "")
    }

    fun clearApproveError() {
        _state.value = _state.value.copy(approveError = null)
    }

    fun refreshDevices() {
        viewModelScope.launch {
            // Keep the last devices on screen while reloading (no flicker to
            // "no PCs" on every pull-to-refresh); hadDevices is the memory
            // that lets an empty answer mean Expired instead of NotLinked.
            _state.value =
                _state.value.copy(devicesLoading = true, devicesError = null)
            val result = repository.listSessions()
            result.fold(
                onSuccess = { devices ->
                    // Keep badge in sync with server, but do NOT auto-hide the success animation
                    // (approvedIntentId). The green card is dismissed only by user (Listo/Desvincular)
                    // or by explicit revoke/unlink, not by the first empty poll right after approval.
                    val newBadge = when {
                        devices.isEmpty() && _state.value.approvedIntentId == null -> PcLinkBadge.NOT_LINKED
                        devices.isEmpty() -> _state.value.badge // keep current badge during post-approval window
                        _state.value.badge == PcLinkBadge.STALE -> PcLinkBadge.STALE
                        else -> PcLinkBadge.LINKED
                    }
                    val newPcState: PcLinkState = when {
                        devices.isNotEmpty() -> PcLinkState.Linked
                        _state.value.approvedIntentId != null -> PcLinkState.Pending
                        _state.value.hadDevices -> PcLinkState.Expired
                        else -> PcLinkState.NotLinked
                    }
                    _state.value = _state.value.copy(
                        devicesLoading = false,
                        devices = devices,
                        devicesError = null,
                        badge = newBadge,
                        pcState = newPcState,
                        hadDevices = _state.value.hadDevices || devices.isNotEmpty()
                    )
                },
                onFailure = { e ->
                    val error = (e as? PcLinkError) ?: PcLinkError.Unknown(null)
                    _state.value = _state.value.copy(
                        devicesLoading = false,
                        devicesError = error,
                        // Transport failure is transient: keep the last devices
                        // and flag them possibly stale. Any other failure keeps
                        // the previous state; Unauthorized additionally flips
                        // the badge via markStale (mobile auth issue, receipt kept).
                        pcState = if (error is PcLinkError.NetworkError) PcLinkState.Offline else _state.value.pcState
                    )
                    if (error is PcLinkError.Unauthorized) markStale()
                }
            )
        }
    }

    fun revokeDevice(sessionId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(revokingId = sessionId)
            val result = repository.revokeSession(sessionId)
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(revokingId = null)
                    // Revoking a PC must also invalidate the local PC receipt so the green card and badge disappear
                    repository.clearLinkSession()
                    _state.value = _state.value.copy(
                        approvedIntentId = null,
                        badge = PcLinkBadge.NOT_LINKED,
                        // Deliberate local end: a following empty list means
                        // NotLinked, never Expired.
                        pcState = PcLinkState.NotLinked,
                        hadDevices = false
                    )
                    refreshDevices()
                },
                onFailure = { e ->
                    val error = (e as? PcLinkError) ?: PcLinkError.Unknown(null)
                    _state.value = _state.value.copy(revokingId = null, devicesError = error)
                }
            )
        }
    }

    /** Forget the local pairing receipt (used by Unlink + FCM pc_unlinked). Also revokes any server PCs. */
    fun unlinkLocal() {
        viewModelScope.launch {
            // Best-effort: revoke all server PCs before clearing local, so a FREE user can re-link without "límite excedido"
            try {
                val current = repository.listSessions().getOrNull() ?: emptyList()
                for (d in current) {
                    repository.revokeSession(d.id)
                }
            } catch (_: Exception) {
            }
            repository.clearLinkSession()
            _state.value = _state.value.copy(
                approvedIntentId = null,
                pin = "",
                pinFailures = 0,
                pinLockedUntilMs = 0L,
                badge = PcLinkBadge.NOT_LINKED,
                // Deliberate local end (see revokeDevice).
                pcState = PcLinkState.NotLinked,
                hadDevices = false,
                devices = emptyList()
            )
            // Refresh to confirm server is now empty
            refreshDevices()
        }
    }

    private suspend fun onApprovalResult(
        result: Result<Unit>,
        receiptId: String,
        receiptDevice: String
    ) {
        result.fold(
            onSuccess = {
                repository.saveLinkSession(receiptId, receiptDevice)
                _state.value = _state.value.copy(
                    isApproving = false,
                    approveError = null,
                    approvedIntentId = receiptId
                )
                refreshDevices()
                // The web creates the cloud_link_sessions row only after it
                // polls the intent (every 2s) and exchanges it. The first
                // listSessions right after approve is therefore still empty
                // and the modal would show "no hay pc vinculada" until the
                // user presses reload. Schedule a few automatic refreshes
                // that stop as soon as a device appears.
                schedulePostApprovalRefresh()
            },
            onFailure = { e ->
                val error = (e as? PcLinkError) ?: PcLinkError.Unknown(null)
                _state.value = _state.value.copy(isApproving = false, approveError = error)
            }
        )
    }

    private fun schedulePostApprovalRefresh() {
        viewModelScope.launch {
            // 1.5s covers the web's 2s poll + exchange; 3s and 6s cover slow networks
            val delays = longArrayOf(1500L, 2500L, 3000L)
            for (d in delays) {
                delay(d.milliseconds)
                // Stop early if a device already appeared (e.g. fast exchange)
                if (_state.value.devices.isNotEmpty()) break
                // Direct repository call to avoid flickering devicesLoading for the polling ticks;
                // the card already shows the list or the empty text, a silent refresh is enough.
                val result = repository.listSessions()
                result.onSuccess { devices ->
                    val newBadge =
                        if (devices.isNotEmpty()) PcLinkBadge.LINKED else _state.value.badge
                    _state.value =
                        _state.value.copy(
                            devices = devices,
                            devicesError = null,
                            badge = newBadge,
                            pcState = when {
                                devices.isNotEmpty() -> PcLinkState.Linked
                                _state.value.approvedIntentId != null -> PcLinkState.Pending
                                else -> _state.value.pcState
                            },
                            hadDevices = _state.value.hadDevices || devices.isNotEmpty()
                        )
                    if (devices.isNotEmpty()) return@launch
                }.onFailure { e ->
                    val error = (e as? PcLinkError) ?: PcLinkError.Unknown(null)
                    if (error is PcLinkError.Unauthorized) markStale()
                }
            }
            // Final visible refresh to clear loading/error states and sync badge/local
            if (_state.value.devices.isEmpty()) refreshDevices()
        }
    }

    private fun markStale() {
        if (_state.value.badge == PcLinkBadge.LINKED) {
            _state.value = _state.value.copy(badge = PcLinkBadge.STALE)
        }
    }

    companion object {
        const val PIN_LENGTH = 6
        const val PIN_MAX_FAILURES = 5
        const val PIN_LOCKOUT_MS = 15 * 60 * 1000L
    }
}
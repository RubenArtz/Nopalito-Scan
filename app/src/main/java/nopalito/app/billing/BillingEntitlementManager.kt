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

package nopalito.app.billing

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nopalito.app.ui.screens.cloud.data.ApiException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

class BillingEntitlementManager private constructor(
    private val context: Context,
    private val repository: BillingRepository,
    private val scope: CoroutineScope,
    private val tokenProvider: nopalito.app.ui.screens.cloud.network.TokenProvider,
) {
    private val _entitlementFlow = MutableStateFlow(BillingEntitlementUiState())
    val entitlementFlow: StateFlow<BillingEntitlementUiState> = _entitlementFlow.asStateFlow()

    private val mutex = Mutex()
    private var authEpoch: Long = 0L
    private val requestSequence = AtomicLong(0L)

    @Volatile
    private var latestAppliedSequence: Long = 0L
    private var currentUserId: String? = null
    private val lastForegroundRefreshMillis = mutableMapOf<String, Long>()
    private var refreshJob: Job? = null
    private var retryJob: Job? = null
    private var pendingReason: BillingRefreshReason? = null
    private var lastAuthenticatedUserId: String? = null
    private var lastAuthenticatedEpoch: Long? = null

    private fun hasSession(): Boolean = try {
        tokenProvider.hasSession()
    } catch (_: Exception) {
        false
    }

    private fun resolveUserId(): String? {
        // Stable userId from EncryptedSharedPreferences (persisted via saveUser)
        // Fallback: null (no session) -> manager will not refresh
        return try {
            tokenProvider.getUserId()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun incrementAuthEpoch() {
        authEpoch += 1
        // Cancel retries tied to previous epoch
        retryJob?.cancel()
        retryJob = null
        Log.d(TAG, "authEpoch incremented -> $authEpoch")
    }

    fun onSessionRestored() {
        val uid = resolveUserId()
        if (uid == null) {
            Log.d(
                TAG,
                "onSessionRestored: no userId, skip (existing installs will populate via getMe)"
            )
            return
        }
        // Deduplicate repeated Authenticated emissions for same user/epoch
        if (lastAuthenticatedUserId == uid && lastAuthenticatedEpoch == authEpoch) {
            Log.d(TAG, "onSessionRestored duplicate for $uid epoch $authEpoch, skip")
            return
        }
        lastAuthenticatedUserId = uid
        lastAuthenticatedEpoch = authEpoch
        val isSwitch = currentUserId != null && currentUserId != uid
        if (isSwitch) {
            incrementAuthEpoch()
            // Update dedup epoch after increment
            lastAuthenticatedEpoch = authEpoch
            resetStateForNewUser(uid)
        } else {
            currentUserId = uid
        }
        launchRefresh(force = true, reason = BillingRefreshReason.SESSION_RESTORED)
    }

    fun onLogin() {
        incrementAuthEpoch()
        val uid = resolveUserId()
        lastAuthenticatedUserId = uid
        lastAuthenticatedEpoch = authEpoch
        resetStateForNewUser(uid)
        launchRefresh(force = true, reason = BillingRefreshReason.LOGIN)
    }

    fun onLogout() {
        incrementAuthEpoch()
        refreshJob?.cancel()
        retryJob?.cancel()
        retryJob = null
        refreshJob = null
        pendingReason = null
        lastAuthenticatedUserId = null
        lastAuthenticatedEpoch = null
        currentUserId = null
        lastForegroundRefreshMillis.clear()
        _entitlementFlow.value = BillingEntitlementUiState(
            ownerUserId = null,
            plan = "FREE",
            storageLimitBytes = FREE_STORAGE_LIMIT_BYTES,
            isActiveEntitlement = false,
            isRefreshing = false,
            lastConfirmedAtMillis = null,
            refreshReason = "LOGOUT"
        )
        Log.d(TAG, "onLogout: state cleared to FREE")
    }

    fun onAccountSwitch(newUserId: String?) {
        incrementAuthEpoch()
        refreshJob?.cancel()
        retryJob?.cancel()
        retryJob = null
        refreshJob = null
        pendingReason = null
        lastAuthenticatedUserId = newUserId
        lastAuthenticatedEpoch = authEpoch
        resetStateForNewUser(newUserId)
        launchRefresh(force = true, reason = BillingRefreshReason.ACCOUNT_SWITCH)
    }

    private fun resetStateForNewUser(newUserId: String?) {
        currentUserId = newUserId
        _entitlementFlow.value = BillingEntitlementUiState(
            ownerUserId = newUserId,
            plan = "FREE",
            storageLimitBytes = FREE_STORAGE_LIMIT_BYTES,
            isActiveEntitlement = false,
            isRefreshing = false,
            lastConfirmedAtMillis = null,
            refreshReason = null
        )
        // Do not clear TTL for other users, but this user will force refresh anyway
    }

    fun onAppForeground() {
        val uid = resolveUserId()
        if (uid == null || !hasSession()) {
            Log.d(TAG, "onAppForeground: no session, skip")
            return
        }
        if (currentUserId != uid) {
            Log.d(TAG, "onAppForeground: userId changed $currentUserId -> $uid, treat as switch")
            onAccountSwitch(uid)
            return
        }
        // If a force refresh is already in flight, don't queue a low-priority foreground
        if (refreshJob?.isActive == true) {
            Log.d(TAG, "onAppForeground: refresh already in flight, skip coalesce")
            return
        }
        val now = System.currentTimeMillis()
        val last = lastForegroundRefreshMillis[uid] ?: 0L
        val withinTtl = now - last < FOREGROUND_TTL_MILLIS
        if (withinTtl) {
            Log.d(TAG, "onAppForeground: within TTL (${now - last}ms), skip")
            return
        }
        launchRefresh(force = false, reason = BillingRefreshReason.FOREGROUND)
    }

    fun refresh(
        force: Boolean = false,
        reason: BillingRefreshReason = BillingRefreshReason.MANUAL
    ) {
        launchRefresh(force, reason)
    }

    private fun launchRefresh(force: Boolean, reason: BillingRefreshReason) {
        // Coalesce if already refreshing: keep highest priority pending
        if (mutex.isLocked) {
            val existing = pendingReason
            pendingReason = if (existing == null) reason else BillingRefreshReason.maxPriority(
                listOf(
                    existing,
                    reason
                )
            )
            Log.d(TAG, "refresh coalesced reason=$reason pending=${pendingReason} force=$force")
            // Force reason should bump sequence to invalidate normal in-flight
            if (force) {
                requestSequence.incrementAndGet()
            }
            return
        }
        refreshJob = scope.launch {
            doRefresh(force, reason)
            // Handle coalesced pending after single flight
            val pending = pendingReason
            if (pending != null) {
                pendingReason = null
                // Pending was coalesced: force according to its priority
                val shouldForce = pending.priority >= BillingRefreshReason.SESSION_RESTORED.priority
                launchRefresh(force = shouldForce, reason = pending)
            }
        }
    }

    private suspend fun doRefresh(force: Boolean, reason: BillingRefreshReason) {
        val uid = resolveUserId()
        if (uid == null || !hasSession()) {
            Log.d(
                TAG,
                "doRefresh skip no session uid=$uid hasSession=${hasSession()} reason=$reason"
            )
            return
        }
        // TTL per userId for non-force foreground
        if (!force && reason == BillingRefreshReason.FOREGROUND) {
            val now = System.currentTimeMillis()
            val last = lastForegroundRefreshMillis[uid] ?: 0L
            if (now - last < FOREGROUND_TTL_MILLIS) {
                Log.d(TAG, "doRefresh foreground within TTL, skip")
                return
            }
        }
        val snapUserId = uid
        val snapEpoch = authEpoch
        val seq = requestSequence.incrementAndGet()

        mutex.withLock {
            // Double-check after acquiring lock: still same user/epoch?
            if (snapUserId != currentUserId && currentUserId != null) {
                // If currentUserId was updated but snap is old, discard
                // However if currentUserId == snapUserId at start, this path not triggered
            }
            // Mark refreshing
            _entitlementFlow.value = _entitlementFlow.value.copy(
                isRefreshing = true,
                refreshReason = reason.key,
                recoverableError = null
            )

            var billingResult: Result<nopalito.app.ui.screens.cloud.model.BillingStatusData>? = null
            var usageResult: Result<nopalito.app.ui.screens.cloud.model.StorageUsage>? = null

            try {
                // Sequential to avoid double concurrency; could be parallel but spec says no double billing/storage at once
                billingResult = repository.fetchBillingStatus()
                usageResult = repository.fetchStorageUsage()
            } catch (e: Exception) {
                // Catch unexpected but treat as recoverable
                Log.w(TAG, "doRefresh exception: ${e.message}")
                billingResult = Result.failure(e)
                usageResult = Result.failure(e)
            }

            // Snapshot validation after network
            val currentUid = resolveUserId()
            val currentEpoch = authEpoch
            val isLatest = seq >= latestAppliedSequence
            if (currentUid != snapUserId || currentEpoch != snapEpoch) {
                Log.w(
                    TAG,
                    "doRefresh discard stale response snapUserId=$snapUserId current=$currentUid snapEpoch=$snapEpoch currentEpoch=$currentEpoch seq=$seq"
                )
                // Do not touch flow, just clear refreshing if still latest for this user
                if (currentUid == _entitlementFlow.value.ownerUserId || _entitlementFlow.value.ownerUserId == snapUserId) {
                    _entitlementFlow.value = _entitlementFlow.value.copy(isRefreshing = false)
                }
                return
            }
            if (!isLatest) {
                Log.w(
                    TAG,
                    "doRefresh discard not latest seq=$seq latestApplied=$latestAppliedSequence"
                )
                _entitlementFlow.value = _entitlementFlow.value.copy(isRefreshing = false)
                return
            }

            // Handle partial success per spec
            val billingOk = billingResult.isSuccess
            val usageOk = usageResult.isSuccess

            if (billingOk) {
                val data = billingResult.getOrNull()
                val plan = data?.plan?.takeIf { it.isNotBlank() } ?: "FREE"
                val limit = data?.storageLimitBytes ?: FREE_STORAGE_LIMIT_BYTES
                val isActive = data?.isActiveEntitlement
                    ?: (plan != "FREE" && data?.subscriptionStatus?.lowercase() !in setOf("expired"))
                val reasonStr = data?.entitlementReason
                val newState = _entitlementFlow.value.copy(
                    ownerUserId = snapUserId,
                    plan = plan.uppercase(),
                    subscriptionStatus = data?.subscriptionStatus,
                    storageLimitBytes = limit,
                    storageUsedBytes = if (usageOk) usageResult.getOrNull()?.usedBytes else _entitlementFlow.value.storageUsedBytes,
                    storageAvailableBytes = if (usageOk) usageResult.getOrNull()?.freeBytes else _entitlementFlow.value.storageAvailableBytes,
                    expiryTime = data?.subscriptionExpiresAt,
                    billingPeriod = data?.billingPeriod,
                    isActiveEntitlement = isActive,
                    entitlementReason = reasonStr,
                    isRefreshing = false,
                    lastConfirmedAtMillis = System.currentTimeMillis(),
                    refreshReason = reason.key,
                    recoverableError = null
                )
                _entitlementFlow.value = newState
                latestAppliedSequence = seq
                lastForegroundRefreshMillis[snapUserId] = System.currentTimeMillis()
                // Notify bus as signal only (no plan payload)
                try {
                    BillingSyncBus.notifyPlanChanged()
                } catch (_: Exception) {
                }
                retryJob?.cancel()
                retryJob = null
                Log.d(
                    TAG,
                    "doRefresh success plan=$plan active=$isActive reason=$reasonStr seq=$seq"
                )
            } else if (usageOk) {
                // Storage succeeded but billing failed: keep last entitlement, expose error
                val err = billingResult.exceptionOrNull()
                val isRecoverable = isRecoverableError(err)
                Log.w(
                    TAG,
                    "doRefresh billing failed but usage ok: ${err?.message} recoverable=$isRecoverable"
                )
                _entitlementFlow.value = _entitlementFlow.value.copy(
                    isRefreshing = false,
                    recoverableError = BillingRefreshError(
                        code = (err as? ApiException)?.code,
                        httpStatus = (err as? ApiException)?.httpStatus,
                        message = err?.message?.take(120),
                        retryScheduled = isRecoverable
                    )
                )
                if (isRecoverable) scheduleRetry(reason)
            } else {
                // Both failed or billing failed and usage failed/unknown
                val err = billingResult.exceptionOrNull() ?: usageResult.exceptionOrNull()
                // 401 must not trigger refresh; delegate to token refresh. Just keep state.
                val httpStatus = (err as? ApiException)?.httpStatus ?: extractHttpStatus(err)
                if (httpStatus == 401) {
                    Log.w(TAG, "doRefresh 401: keep lastConfirmed, no downgrade")
                    _entitlementFlow.value = _entitlementFlow.value.copy(isRefreshing = false)
                    return
                }
                val isRecoverable = isRecoverableError(err)
                Log.w(
                    TAG,
                    "doRefresh failed plan stays ${_entitlementFlow.value.plan} err=${err?.message} http=$httpStatus recoverable=$isRecoverable"
                )
                _entitlementFlow.value = _entitlementFlow.value.copy(
                    isRefreshing = false,
                    recoverableError = BillingRefreshError(
                        code = (err as? ApiException)?.code,
                        httpStatus = httpStatus,
                        message = err?.message?.take(120),
                        retryScheduled = isRecoverable
                    )
                )
                if (isRecoverable) scheduleRetry(reason)
                // Never downgrade to FREE on network/5xx/429
            }
        }
    }

    private fun isRecoverableError(e: Throwable?): Boolean {
        if (e == null) return false
        if (e is IOException) return true
        val msg = e.message?.lowercase() ?: ""
        if (msg.contains("timeout") || msg.contains("unavailable") || msg.contains("unable to resolve host")) return true
        val code = (e as? ApiException)?.httpStatus ?: extractHttpStatus(e)
        return code == 429 || (code in 500..599)
    }

    private fun extractHttpStatus(e: Throwable?): Int? {
        // ApiException already handled; fallback parse from message like "failed 502"
        val msg = e?.message ?: return null
        val m = Regex("""\b(429|5\d\d)\b""").find(msg)
        return m?.value?.toInt()
    }

    private fun scheduleRetry(originalReason: BillingRefreshReason) {
        // Only for relevant triggers; foreground manual retries allowed, but cancel on epoch change
        if (retryJob?.isActive == true) {
            Log.d(TAG, "scheduleRetry: already scheduled, skip")
            return
        }
        val snapUserId = currentUserId
        val snapEpoch = authEpoch
        retryJob = scope.launch {
            for ((idx, delayMs) in RETRY_DELAYS_MILLIS.withIndex()) {
                delay(delayMs.milliseconds)
                if (authEpoch != snapEpoch || currentUserId != snapUserId) {
                    Log.d(
                        TAG,
                        "retry cancelled epoch/user changed snapEpoch=$snapEpoch current=$authEpoch"
                    )
                    return@launch
                }
                if (!hasSession()) {
                    Log.d(TAG, "retry cancelled no session")
                    return@launch
                }
                Log.d(TAG, "retry attempt ${idx + 1} after ${delayMs}ms reason=$originalReason")
                doRefresh(force = true, reason = originalReason)
                // If next attempt still has recoverable error, loop continues; else break
                val stillRecoverable =
                    _entitlementFlow.value.recoverableError?.retryScheduled == true
                if (!stillRecoverable) break
            }
        }
    }

    @OptIn(FlowPreview::class)
    fun collectInvalidationEvents() {
        scope.launch {
            EntitlementInvalidationBus.events
                .debounce(1200.milliseconds)
                .collect { event ->
                    if (!hasSession()) return@collect
                    // Double-check epoch/user at collect time
                    // event is already filtered for allowed codes + not billing endpoints
                    Log.d(TAG, "invalidation bus code=${event.code} path=${event.path} -> refresh")
                    launchRefresh(force = true, reason = BillingRefreshReason.SEMANTIC_403)
                }
        }
    }

    companion object {
        private const val TAG = "BillingEntitlement"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: BillingEntitlementManager? = null

        fun getInstance(): BillingEntitlementManager? = instance
        fun getInstance(context: Context): BillingEntitlementManager? = instance

        fun initInstance(
            context: Context,
            repository: BillingRepository,
            scope: CoroutineScope,
            tokenProvider: nopalito.app.ui.screens.cloud.network.TokenProvider
        ): BillingEntitlementManager {
            return instance ?: synchronized(this) {
                instance ?: BillingEntitlementManager(
                    context,
                    repository,
                    scope,
                    tokenProvider
                ).also { instance = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun clearInstanceForTest() {
            instance = null
        }
    }
}
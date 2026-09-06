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
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nopalito.app.billing.BillingEntitlementManager
import nopalito.app.billing.BillingManager
import nopalito.app.billing.BillingRefreshReason
import nopalito.app.billing.BillingRepository
import nopalito.app.ui.screens.cloud.model.BillingPlan
import nopalito.app.ui.screens.cloud.model.BillingStatusData
import kotlin.time.Duration.Companion.milliseconds

/**
 * Explicit purchase flow phases - required to avoid fake local success before backend verification.
 * Backend is source of truth; UI only shows success after PURCHASED -> verify -> refresh.
 */
enum class PurchasePhase {
    Idle,
    Launching,
    ReceivedFromPlay,
    VerifyingBackend,
    SuccessApplied,
    Error,
    Pending
}

data class PurchaseFlowState(
    val phase: PurchasePhase = PurchasePhase.Idle,
    val appliedPlan: String? = null,
    val errorMessage: String? = null,
    val showRetry: Boolean = false,
    val blocking: Boolean = false
)

/**
 * SubscriptionPlansViewModel — loads billing plans from backend and ProductDetails from Play.
 *
 * Prices are never hardcoded; formattedPrice from ProductDetails is the only price source.
 * Supports legacy 4 productIds (D1). Handles restore and manage subscription logic.
 */
data class SubscriptionPlansUiState(
    val isLoading: Boolean = true,
    val plans: List<BillingPlan> = emptyList(),
    val productDetails: Map<String, ProductDetails> = emptyMap(),
    val errorKey: String? = null,
    val currentPlan: String? = null,
    val purchaseInProgress: Boolean = false,
    val billingConnected: Boolean = false,
    val debugInfo: String? = null,
    // New: billing status + restore state
    val billingStatus: BillingStatusData? = null,
    val isRestoring: Boolean = false,
    val restoreMessageRes: Int? = null,
    val restoreIsError: Boolean = false,
    val showRestoreRetry: Boolean = false,
    // Purchase flow explicit state (backend-verified only)
    val purchaseFlow: PurchaseFlowState = PurchaseFlowState(),
    // Idempotency: tokens currently being verified to avoid duplicate calls
    val verifyingTokens: Set<String> = emptySet()
)

class SubscriptionPlansViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SubscriptionPlansUiState())
    val uiState: StateFlow<SubscriptionPlansUiState> = _uiState

    internal var billingManager: BillingManager
        private set
    internal var repository: BillingRepository
        private set

    // User ID for obfuscatedAccountId (set from CloudStorage screen)
    private var currentUserId: String? = null

    @androidx.annotation.VisibleForTesting
    internal fun setBillingManagerForTest(manager: BillingManager) {
        billingManager = manager
    }

    @androidx.annotation.VisibleForTesting
    internal fun setRepositoryForTest(repo: BillingRepository) {
        repository = repo
    }

    private var hasAutoRestored = false
    private var lastRestoreMs: Long = 0L
    private val restoreCooldownMs = 60_000L

    // Idempotency: keep short hash of tokens in flight to block duplicate verifications
    private fun tokenShort(token: String): String = try {
        java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)
    } catch (_: Exception) {
        token.takeLast(8)
    }

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        Log.d(
            "BillingDiag",
            "PurchasesUpdatedListener responseCode=${result.responseCode} debugMessage=${result.debugMessage} count=${purchases?.size}"
        )
        Log.d(
            "BillingVM",
            "onPurchasesUpdated code=${result.responseCode} count=${purchases?.size}"
        )
        if (result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK && purchases != null) {
            if (purchases.isEmpty()) {
                Log.w(
                    "BillingVM",
                    "onPurchasesUpdated OK but purchases empty - treating as cancel, resetting to Idle"
                )
                _uiState.value = _uiState.value.copy(
                    purchaseInProgress = false,
                    purchaseFlow = PurchaseFlowState(phase = PurchasePhase.Idle, blocking = false)
                )
                return@PurchasesUpdatedListener
            }
            var hasValidPurchased = false
            var hasPending = false
            for (purchase in purchases) {
                // Filter: process only PURCHASED — ignore PENDING, UNSPECIFIED, etc.
                // PENDING must not be verified against the backend (payment not yet confirmed by Play).
                if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
                    Log.d(
                        "BillingVM",
                        "purchase ignored product=${purchase.products.firstOrNull()} state=${purchase.purchaseState} (not PURCHASED)"
                    )
                    // If PENDING, surface it as pending phase so UI shows Pending, not blocking Processing
                    if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                        hasPending = true
                    }
                    continue
                }
                hasValidPurchased = true
                // Never log purchaseToken, userId, or obfuscated ID
                Log.d(
                    "BillingDiag",
                    "PurchasesUpdatedListener product=${purchase.products.firstOrNull()} state=${purchase.purchaseState} ack=${purchase.isAcknowledged} responseCode=${result.responseCode}"
                )
                Log.d(
                    "BillingVM",
                    "purchase received product=${purchase.products.firstOrNull()} state=${purchase.purchaseState} ack=${purchase.isAcknowledged}"
                )
                verifyAndSync(purchase)
            }
            // No valid PURCHASED found - handle PENDING or reset to Idle to avoid stuck Launching
            if (!hasValidPurchased) {
                if (hasPending) {
                    Log.d(
                        "BillingVM",
                        "no PURCHASED but has PENDING - showing Pending phase and clearing blocking"
                    )
                    _uiState.value = _uiState.value.copy(
                        purchaseInProgress = false,
                        purchaseFlow = PurchaseFlowState(
                            phase = PurchasePhase.Pending,
                            blocking = false
                        )
                    )
                } else {
                    Log.w(
                        "BillingVM",
                        "no PURCHASED purchases (only ignored states), resetting to Idle to unblock UI"
                    )
                    _uiState.value = _uiState.value.copy(
                        purchaseInProgress = false,
                        purchaseFlow = PurchaseFlowState(
                            phase = PurchasePhase.Idle,
                            blocking = false
                        )
                    )
                }
            }
        } else {
            Log.w(
                "BillingDiag",
                "PurchasesUpdatedListener not OK responseCode=${result.responseCode} debugMessage=${result.debugMessage}"
            )
            Log.w(
                "BillingVM",
                "purchasesUpdated not OK: ${result.responseCode} ${result.debugMessage}"
            )
            // Any not-OK while in Launching/Verifying must unblock - USER_CANCELED is idle, others also reset to avoid stuck modal
            val wasLaunching =
                _uiState.value.purchaseFlow.phase == PurchasePhase.Launching || _uiState.value.purchaseInProgress
            if (result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.USER_CANCELED || wasLaunching) {
                Log.w(
                    "BillingVM",
                    "resetting to Idle from not-OK code=${result.responseCode} wasLaunching=$wasLaunching"
                )
                _uiState.value = _uiState.value.copy(
                    purchaseInProgress = false,
                    purchaseFlow = PurchaseFlowState(phase = PurchasePhase.Idle, blocking = false)
                )
            }
        }
    }

    init {
        val ctx = getApplication<Application>().applicationContext
        billingManager = BillingManager.getInstance(ctx, purchasesListener)
        repository = BillingRepository(ctx)
        Log.d("BillingVM", "init: starting BillingClient connection")
        billingManager.startConnection { ready ->
            Log.d("BillingVM", "BillingClient connected: $ready")
            _uiState.value = _uiState.value.copy(
                billingConnected = ready,
                debugInfo = if (ready) null else "billing_not_connected"
            )
            if (ready) {
                refresh()
                autoRestoreIfNeeded()
            }
        }
        // Fallback if already ready
        refresh()
        observeGlobalEntitlement()
    }

    private fun observeGlobalEntitlement() {
        viewModelScope.launch {
            try {
                val mgr = BillingEntitlementManager.getInstance()
                    ?: return@launch
                mgr.entitlementFlow.collect { ent ->
                    // Skip initial unconfirmed FREE that would overwrite a directly fetched status in tests
                    if (ent.lastConfirmedAtMillis == null && ent.plan == "FREE" && _uiState.value.billingStatus?.plan != null) {
                        return@collect
                    }
                    // Map global entitlement to legacy UI state (derived, not authority)
                    val status = BillingStatusData(
                        plan = ent.plan,
                        storageLimitBytes = ent.storageLimitBytes,
                        subscriptionStatus = ent.subscriptionStatus,
                        subscriptionExpiresAt = ent.expiryTime,
                        billingPeriod = ent.billingPeriod,
                        isActiveEntitlement = ent.isActiveEntitlement,
                        entitlementReason = ent.entitlementReason
                    )
                    _uiState.value = _uiState.value.copy(
                        billingStatus = status,
                        currentPlan = ent.plan
                    )
                    Log.d(
                        "BillingVM",
                        "entitlementFlow -> plan=${ent.plan} active=${ent.isActiveEntitlement} reason=${ent.entitlementReason}"
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun notifyEntitlementRefresh(reason: BillingRefreshReason) {
        try {
            val mgr = BillingEntitlementManager.getInstance()
            mgr?.refresh(force = true, reason = reason)
        } catch (_: Exception) {
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorKey = null)
            try {
                val rawPlans = repository.fetchBackendPlans()
                val legacyPlans = rawPlans.filter {
                    it.googlePlay?.productId?.startsWith("nopalito_") == true || it.googlePlay?.basePlanIdMonthly?.startsWith(
                        "nopalito_"
                    ) == true || it.googlePlay?.basePlanIdAnnual?.startsWith("nopalito_") == true
                }
                if (legacyPlans.isNotEmpty()) {
                    Log.w(
                        "BillingDiag",
                        "LEGACY config rejected plans=${legacyPlans.map { it.id }} mapping=${legacyPlans.map { "${it.googlePlay?.productId}:${it.googlePlay?.basePlanIdMonthly}:${it.googlePlay?.basePlanIdAnnual}" }} source=network — backend still serves nopalito_* (expected personal/plus). Showing config error, not querying legacy."
                    )
                    Log.w(
                        "BillingDiag",
                        "Invalid backend billing config: legacy productId detected. Backend must serve personal/plus. Invalidate cache/restart backend."
                    )
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        plans = emptyList(),
                        errorKey = "billing.config_legacy_detected"
                    )
                    return@launch
                }
                val plans = rawPlans.filter { !it.legacy && it.enabled }
                Log.d(
                    "BillingDiag",
                    "fetched backend plans ids=${plans.map { it.id }} mapping=${plans.map { "${it.id}:${it.googlePlay?.productId}:${it.googlePlay?.basePlanIdMonthly}:${it.googlePlay?.basePlanIdAnnual}" }} source=network validated"
                )
                Log.d("BillingVM", "fetched backend plans: ${plans.map { it.id }}")
                val ids = plans.mapNotNull { it.googlePlay?.productId }
                    .distinct()
                    .filter { it.isNotBlank() && !it.startsWith("nopalito_") }
                Log.d(
                    "BillingDiag",
                    "querying ProductDetails for ids=$ids expected=[personal, plus] count=${ids.size} filteredLegacy=true"
                )
                Log.d("BillingVM", "querying ProductDetails for: $ids")
                if (ids.isEmpty()) {
                    Log.w(
                        "BillingDiag",
                        "no productIds from backend after legacy filter, cannot query Play backendPlans=${plans.map { it.id }} rawIds=${rawPlans.mapNotNull { it.googlePlay?.productId }}"
                    )
                    Log.w("BillingVM", "no productIds from backend, cannot query Play")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        plans = plans,
                        errorKey = "billing.config_legacy_detected"
                    )
                    return@launch
                }
                val details = billingManager.queryProductDetails(ids)
                Log.d(
                    "BillingDiag",
                    "ProductDetails received count=${details.size} keys=${details.keys} sizes=${details.mapValues { it.value.subscriptionOfferDetails?.size }}"
                )
                Log.d(
                    "BillingVM",
                    "ProductDetails received: ${details.keys} sizes: ${details.mapValues { it.value.subscriptionOfferDetails?.size }}"
                )
                for ((pid, pd) in details) {
                    val offers = pd.subscriptionOfferDetails
                    if (offers == null) {
                        Log.w("BillingDiag", "ProductDetails $pid has no subscriptionOfferDetails")
                    } else {
                        for (offer in offers) {
                            val hasToken = true
                            val price =
                                offer.pricingPhases.pricingPhaseList.firstOrNull()?.formattedPrice
                                    ?: "missing"
                            val period =
                                offer.pricingPhases.pricingPhaseList.firstOrNull()?.billingPeriod
                                    ?: "unknown"
                            Log.d(
                                "BillingDiag",
                                "refresh offer productId=$pid basePlanId=${offer.basePlanId} offerId=${offer.offerId ?: "null"} hasOfferToken=$hasToken billingPeriod=$period formattedPrice=$price"
                            )
                        }
                    }
                }
                val missingIds = ids.filterNot { details.containsKey(it) }
                if (missingIds.isNotEmpty()) {
                    Log.w(
                        "BillingDiag",
                        "missing ProductDetails for ids=$missingIds — check Play Console Active/country/price and unfetchedProductList above"
                    )
                }
                _uiState.value =
                    _uiState.value.copy(isLoading = false, plans = plans, productDetails = details)
            } catch (e: Exception) {
                Log.e("BillingDiag", "refresh failed: ${e.message}")
                Log.e("BillingVM", "refresh failed: ${e.message}")
                _uiState.value =
                    _uiState.value.copy(isLoading = false, errorKey = "billing.plans_load_error")
            }
        }
        fetchBillingStatus()
    }

    /**
     * Legacy fetch retained for tests/compat; prefer global entitlementFlow.
     * Not called automatically anymore.
     */
    fun fetchBillingStatus() {
        // Delegate to global manager when available; fallback to direct for tests without manager
        val mgr = try {
            BillingEntitlementManager.getInstance()
        } catch (_: Exception) {
            null
        }
        if (mgr != null) {
            mgr.refresh(force = true, reason = BillingRefreshReason.MANUAL)
            return
        }
        viewModelScope.launch {
            try {
                val result = repository.fetchBillingStatus()
                if (result.isSuccess) {
                    val status = result.getOrNull()
                    _uiState.value = _uiState.value.copy(billingStatus = status)
                    status?.plan?.let { p ->
                        _uiState.value = _uiState.value.copy(currentPlan = p)
                    }
                    Log.d(
                        "BillingVM",
                        "billing status fetched plan=${status?.plan} status=${status?.subscriptionStatus} product=${status?.googleProductId}"
                    )
                } else {
                    Log.w(
                        "BillingVM",
                        "fetchBillingStatus failed: ${result.exceptionOrNull()?.message}"
                    )
                }
            } catch (e: Exception) {
                Log.w("BillingVM", "fetchBillingStatus exception: ${e.message}")
            }
        }
    }

    /**
     * Core post-purchase sync: PURCHASED from Play -> verify -> refresh status+usage -> SuccessApplied.
     * Idempotent: same purchaseToken never verified twice concurrently (verifyingTokens guard).
     * Backend is source of truth; UI only shows success after refresh succeeds.
     */
    private fun verifyAndSync(purchase: Purchase) {
        val short = tokenShort(purchase.purchaseToken)
        if (short in _uiState.value.verifyingTokens) {
            Log.d("BillingVM", "verifyAndSync blocked duplicate token $short")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                purchaseInProgress = true,
                purchaseFlow = PurchaseFlowState(
                    phase = PurchasePhase.VerifyingBackend,
                    blocking = true
                ),
                verifyingTokens = _uiState.value.verifyingTokens + short
            )
            Log.d(
                "BillingVM",
                "verifyAndSync start product=${purchase.products.firstOrNull()} hash8=$short"
            )
            val productId = purchase.products.firstOrNull()
            val result = repository.verifyPurchase(purchase.purchaseToken, productId)
            if (result.isSuccess) {
                // Central refresh via manager (single source) + keep legacy refresh for immediate UI in tests
                notifyEntitlementRefresh(BillingRefreshReason.PURCHASE_VERIFIED)
                val (statusRes, usageRes) = repository.refreshAfterVerify()
                if (statusRes.isSuccess) {
                    val status = statusRes.getOrNull()
                    _uiState.value = _uiState.value.copy(
                        billingStatus = status,
                        currentPlan = status?.plan ?: _uiState.value.currentPlan
                    )
                    Log.d(
                        "BillingVM",
                        "verifyAndSync refreshed plan=${status?.plan} usageOk=${usageRes.isSuccess}"
                    )
                    _uiState.value = _uiState.value.copy(
                        purchaseInProgress = false,
                        verifyingTokens = _uiState.value.verifyingTokens - short,
                        purchaseFlow = PurchaseFlowState(
                            phase = PurchasePhase.SuccessApplied,
                            appliedPlan = status?.plan,
                            blocking = false
                        )
                    )
                    // Notify global bus as signal only (no plan payload)
                    try {
                        nopalito.app.billing.BillingSyncBus.notifyPlanChanged()
                    } catch (_: Exception) {
                    }
                    if (!purchase.isAcknowledged) {
                        try {
                            billingManager.acknowledgePurchase(purchase)
                        } catch (e: Exception) {
                            Log.w("BillingVM", "acknowledge failed: ${e.message}")
                        }
                    }
                } else {
                    Log.w(
                        "BillingVM",
                        "verifyAndSync refresh status failed: ${statusRes.exceptionOrNull()?.message}"
                    )
                    _uiState.value = _uiState.value.copy(
                        purchaseInProgress = false,
                        verifyingTokens = _uiState.value.verifyingTokens - short,
                        purchaseFlow = PurchaseFlowState(
                            phase = PurchasePhase.Error,
                            errorMessage = statusRes.exceptionOrNull()?.message,
                            showRetry = true,
                            blocking = false
                        )
                    )
                }
            } else {
                val msg = result.exceptionOrNull()?.message?.lowercase() ?: ""
                val isAuth =
                    msg.contains("401") || msg.contains("403") || msg.contains("unauthorized") || msg.contains(
                        "forbidden"
                    )
                val isExpired =
                    msg.contains("422") || msg.contains("subscription_not_active") || msg.contains("not_active") || msg.contains(
                        "expired"
                    ) || msg.contains("revoked")
                if (isExpired) {
                    // Backend confirmed EXPIRED/REVOKED — refresh via global manager (authority)
                    Log.w(
                        "BillingVM",
                        "verifyAndSync expired hash8=$short — refreshing via manager"
                    )
                    notifyEntitlementRefresh(BillingRefreshReason.PURCHASE_VERIFIED)
                    try {
                        val statusRes = repository.fetchBillingStatus()
                        if (statusRes.isSuccess) {
                            val status = statusRes.getOrNull()
                            _uiState.value = _uiState.value.copy(
                                billingStatus = status,
                                currentPlan = status?.plan ?: "FREE"
                            )
                            try {
                                nopalito.app.billing.BillingSyncBus.notifyPlanChanged()
                            } catch (_: Exception) {
                            }
                            try {
                                repository.fetchStorageUsage()
                            } catch (_: Exception) {
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                Log.w(
                    "BillingVM",
                    "verifyAndSync verify failed hash8=$short error=${result.exceptionOrNull()?.message} isExpired=$isExpired"
                )
                _uiState.value = _uiState.value.copy(
                    purchaseInProgress = false,
                    verifyingTokens = _uiState.value.verifyingTokens - short,
                    purchaseFlow = PurchaseFlowState(
                        phase = PurchasePhase.Error,
                        errorMessage = result.exceptionOrNull()?.message,
                        showRetry = !isAuth && !isExpired,
                        blocking = false
                    )
                )
            }
        }
    }

    /** Called by UI to retry the last failed verification (re-uses last purchase if available). */
    fun retryLastVerification() {
        val flow = _uiState.value.purchaseFlow
        if (flow.phase != PurchasePhase.Error || !flow.showRetry) return
        // Clear error and allow PurchasesUpdatedListener or restore to re-trigger; for manual purchase we need token
        _uiState.value = _uiState.value.copy(
            purchaseFlow = PurchaseFlowState(
                phase = PurchasePhase.Idle,
                blocking = false
            )
        )
        // Best-effort: trigger a restore query to find the pending purchase and re-verify
        viewModelScope.launch {
            try {
                val purchases = billingManager.queryPurchases()
                val recognized =
                    _uiState.value.plans.mapNotNull { it.googlePlay?.productId }.toSet()
                val toRetry =
                    purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.any { p -> p in recognized } }
                if (toRetry != null) verifyAndSync(toRetry)
            } catch (_: Exception) {
            }
        }
    }

    fun clearPurchaseFlow() {
        _uiState.value = _uiState.value.copy(
            purchaseFlow = PurchaseFlowState(
                phase = PurchasePhase.Idle,
                blocking = false
            )
        )
    }

    fun isPurchaseBlocking(): Boolean =
        _uiState.value.purchaseFlow.blocking || _uiState.value.purchaseInProgress || _uiState.value.isRestoring

    /**
     * Manual restore — visible to every authenticated user including FREE.
     * Uses BillingClient.queryPurchasesAsync(SUBS) and verifies only PURCHASED purchases
     * with recognized productIds. Shared isRestoring prevents duplicate taps and competes with auto restore.
     */
    fun restorePurchasesManual() {
        val now = System.currentTimeMillis()
        if (_uiState.value.isRestoring) {
            Log.d("BillingVM", "restoreManual blocked already restoring")
            return
        }
        // Set restoring synchronously to prevent duplicate taps before coroutine starts
        _uiState.value = _uiState.value.copy(
            isRestoring = true,
            restoreMessageRes = nopalito.app.R.string.billing_restore_in_progress,
            restoreIsError = false,
            showRestoreRetry = false
        )
        viewModelScope.launch {
            try {
                val previousPlan = _uiState.value.billingStatus?.plan ?: _uiState.value.currentPlan
                val purchases = billingManager.queryPurchases()
                Log.d("BillingVM", "restoreManual queried purchases count=${purchases.size}")
                // Filter PURCHASED and recognized productIds (server-authoritative catalog only)
                val recognizedIds =
                    _uiState.value.plans.mapNotNull { it.googlePlay?.productId }.toSet()
                if (recognizedIds.isEmpty()) {
                    // Fix A2: never guess productIds — without the backend plan
                    // catalog there is nothing authoritative to verify against.
                    Log.w(
                        "BillingVM",
                        "restoreManual no plan catalog from backend — aborting restore (config missing)"
                    )
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        restoreMessageRes = nopalito.app.R.string.billing_config_missing,
                        restoreIsError = true,
                        showRestoreRetry = false
                    )
                    return@launch
                }
                val toVerify = purchases.filter { p ->
                    p.purchaseState == Purchase.PurchaseState.PURCHASED && p.products.any { it in recognizedIds }
                }
                if (toVerify.isEmpty()) {
                    Log.d("BillingVM", "restoreManual no active subscriptions found")
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        restoreMessageRes = nopalito.app.R.string.billing_restore_none_found,
                        restoreIsError = false,
                        showRestoreRetry = false
                    )
                    return@launch
                }
                var anySuccess = false
                var anyRecoverableError = false
                var authError = false
                var hasExpiredSubscription = false
                for (purchase in toVerify) {
                    val productId = purchase.products.firstOrNull { it in recognizedIds }
                        ?: purchase.products.firstOrNull()
                    // Never log purchaseToken
                    Log.d(
                        "BillingVM",
                        "restoreManual verifying product=${productId} state=${purchase.purchaseState}"
                    )
                    val result = repository.verifyPurchase(purchase.purchaseToken, productId)
                    if (result.isSuccess) {
                        anySuccess = true
                        // Best-effort acknowledge if needed (Play may still require)
                        if (!purchase.isAcknowledged) {
                            try {
                                billingManager.acknowledgePurchase(purchase)
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        val ex = result.exceptionOrNull()
                        val msg = ex?.message?.lowercase() ?: ""
                        // Check for 401/403 session errors — use existing auth handling (do not erase billing state)
                        if (msg.contains("401") || msg.contains("403") || msg.contains("unauthorized") || msg.contains(
                                "forbidden"
                            )
                        ) {
                            authError = true
                        } else if (msg.contains("422") || msg.contains("subscription_not_active") || msg.contains(
                                "not_active"
                            ) || msg.contains("expired") || msg.contains("revoked")
                        ) {
                            // 422 SUBSCRIPTION_NOT_ACTIVE — backend confirmed Play state is EXPIRED/REVOKED.
                            // Mark as inactive and force local sync (fixes 6h stale active bug).
                            hasExpiredSubscription = true
                            Log.w(
                                "BillingVM",
                                "restoreManual verify expired product=${productId} error=${ex?.message} — will refresh status to inactive"
                            )
                        } else {
                            anyRecoverableError = true
                        }
                        Log.w(
                            "BillingVM",
                            "restoreManual verify failed product=${productId} error=${ex?.message}"
                        )
                    }
                }
                // 422 expired handling — force sync via global manager + legacy fallback
                if (hasExpiredSubscription && !anySuccess && !authError) {
                    notifyEntitlementRefresh(BillingRefreshReason.RESTORE_COMPLETED)
                    try {
                        val statusResult = repository.fetchBillingStatus()
                        if (statusResult.isSuccess) {
                            val status = statusResult.getOrNull()
                            _uiState.value = _uiState.value.copy(
                                billingStatus = status,
                                currentPlan = status?.plan ?: "FREE"
                            )
                            Log.d(
                                "BillingVM",
                                "restoreManual expired sync plan=${status?.plan} status=${status?.subscriptionStatus}"
                            )
                            try {
                                nopalito.app.billing.BillingSyncBus.notifyPlanChanged()
                            } catch (_: Exception) {
                            }
                            try {
                                repository.fetchStorageUsage()
                            } catch (_: Exception) {
                            }
                        } else {
                            fetchBillingStatus()
                        }
                    } catch (_: Exception) {
                        fetchBillingStatus()
                    }
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        restoreMessageRes = nopalito.app.R.string.billing_restore_none_found,
                        restoreIsError = false,
                        showRestoreRetry = false
                    )
                    lastRestoreMs = now
                    return@launch
                }
                if (authError) {
                    // Use existing session handling — do not clear billing state
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        restoreMessageRes = nopalito.app.R.string.billing_restore_failed,
                        restoreIsError = true,
                        showRestoreRetry = true
                    )
                    return@launch
                }
                if (anySuccess) {
                    // Central refresh via manager (single source) + legacy fallback
                    notifyEntitlementRefresh(BillingRefreshReason.RESTORE_COMPLETED)
                    val beforePlan = previousPlan?.uppercase()
                    val statusResult = repository.fetchBillingStatus()
                    var newPlan: String? = null
                    if (statusResult.isSuccess) {
                        val status = statusResult.getOrNull()
                        _uiState.value = _uiState.value.copy(
                            billingStatus = status,
                            currentPlan = status?.plan ?: _uiState.value.currentPlan
                        )
                        newPlan = status?.plan?.uppercase()
                        try {
                            repository.fetchStorageUsage()
                        } catch (_: Exception) {
                        }
                    } else {
                        fetchBillingStatus()
                    }
                    // If plan changed from FREE to PERSONAL/PLUS, it's a new entitlement
                    val isNewEntitlement =
                        beforePlan != newPlan && (newPlan == "PERSONAL" || newPlan == "PLUS")
                    if (isNewEntitlement) {
                        _uiState.value = _uiState.value.copy(
                            isRestoring = false,
                            restoreMessageRes = nopalito.app.R.string.billing_restore_success,
                            restoreIsError = false,
                            showRestoreRetry = false
                        )
                    } else {
                        // Idempotent — already up to date
                        _uiState.value = _uiState.value.copy(
                            isRestoring = false,
                            restoreMessageRes = nopalito.app.R.string.billing_restore_already_current,
                            restoreIsError = false,
                            showRestoreRetry = false
                        )
                    }
                    lastRestoreMs = now
                } else if (anyRecoverableError) {
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        restoreMessageRes = nopalito.app.R.string.billing_restore_failed,
                        restoreIsError = true,
                        showRestoreRetry = true
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        restoreMessageRes = nopalito.app.R.string.billing_restore_none_found,
                        restoreIsError = false,
                        showRestoreRetry = false
                    )
                }
            } catch (e: Exception) {
                Log.w("BillingVM", "restoreManual exception: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isRestoring = false,
                    restoreMessageRes = nopalito.app.R.string.billing_restore_failed,
                    restoreIsError = true,
                    showRestoreRetry = true
                )
            }
        }
    }

    private fun autoRestoreIfNeeded() {
        if (_uiState.value.isRestoring) {
            Log.d("BillingVM", "autoRestore skipped already restoring")
            return
        }
        val now = System.currentTimeMillis()
        if (hasAutoRestored && now - lastRestoreMs < restoreCooldownMs) {
            Log.d("BillingVM", "autoRestore skipped cooldown")
            return
        }
        hasAutoRestored = true
        lastRestoreMs = now
        _uiState.value = _uiState.value.copy(isRestoring = true)
        viewModelScope.launch {
            try {
                val purchases = billingManager.queryPurchases()
                // Fix A2: server-authoritative catalog only — no hardcoded
                // productIds. Without the backend plan catalog the auto
                // restore fails silently (it must never surface config
                // errors to the user).
                val recognizedIds =
                    _uiState.value.plans.mapNotNull { it.googlePlay?.productId }.toSet()
                if (recognizedIds.isEmpty()) {
                    Log.w("BillingVM", "autoRestore no plan catalog from backend — skipping")
                    _uiState.value = _uiState.value.copy(isRestoring = false)
                    return@launch
                }
                val toVerify =
                    purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.any { prod -> prod in recognizedIds } }
                if (toVerify.isEmpty()) {
                    _uiState.value = _uiState.value.copy(isRestoring = false)
                    return@launch
                }
                for (purchase in toVerify) {
                    val productId = purchase.products.firstOrNull { it in recognizedIds }
                        ?: purchase.products.firstOrNull()
                    val result = repository.verifyPurchase(purchase.purchaseToken, productId)
                    if (result.isSuccess && !purchase.isAcknowledged) {
                        try {
                            billingManager.acknowledgePurchase(purchase)
                        } catch (_: Exception) {
                        }
                    }
                }
                fetchBillingStatus()
                _uiState.value = _uiState.value.copy(isRestoring = false)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isRestoring = false)
            }
        }
    }

    /**
     * Visibility for Manage subscription — only if user has or recently had Google Play subscription.
     */
    fun shouldShowManage(): Boolean {
        val status = _uiState.value.billingStatus
        val plan = status?.plan?.uppercase() ?: _uiState.value.currentPlan?.uppercase() ?: "FREE"
        val hasPaidPlan = plan == "PERSONAL" || plan == "PLUS"
        val hasProduct = !status?.googleProductId.isNullOrBlank()
        val subStatus = status?.subscriptionStatus?.lowercase()
        val hasActiveStatus =
            subStatus in setOf("active", "cancelled", "in_grace_period", "on_hold", "paused")
        // Visible if paid plan and has product OR has active status (covers cancelled until expiry)
        return (hasPaidPlan && hasProduct) || hasActiveStatus
    }

    fun getManageProductId(): String? {
        val status = _uiState.value.billingStatus
        status?.googleProductId?.takeIf { it.isNotBlank() }?.let { return it }
        val plan = status?.plan?.uppercase() ?: _uiState.value.currentPlan?.uppercase()
        // Fix A2: no hardcoded productId guessing. If the backend did not
        // provide google_product_id there is nothing authoritative to manage —
        // return null so the deep link uses the generic subscriptions URL
        // (SubscriptionPlansDialog falls back to package-only URLs).
        return when (plan) {
            "PERSONAL", "PLUS" -> status?.googleProductId?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /**
     * Launches billing flow for a given productId and exact offerToken for selected basePlanId.
     * BillingDiag logs response only — never token or obfuscated ID.
     * Now transitions to Launching phase so UI can block double taps before PurchasesUpdatedListener.
     */
    fun launchPurchase(
        activity: android.app.Activity,
        productId: String,
        offerToken: String,
        onResult: (Int) -> Unit
    ) {
        // FREE is never purchasable — extra guard
        if (productId.equals("free", ignoreCase = true)) {
            Log.w("BillingDiag", "launchPurchase blocked FREE not purchasable")
            onResult(com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)
            return
        }
        // Block double taps globally
        if (_uiState.value.purchaseInProgress || _uiState.value.purchaseFlow.blocking) {
            Log.w(
                "BillingDiag",
                "launchPurchase blocked already in progress phase=${_uiState.value.purchaseFlow.phase}"
            )
            onResult(com.android.billingclient.api.BillingClient.BillingResponseCode.DEVELOPER_ERROR)
            return
        }
        val details = _uiState.value.productDetails[productId]
        if (details == null) {
            Log.w("BillingDiag", "launchPurchase blocked ProductDetails null productId=$productId")
            onResult(com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)
            return
        }
        val hasToken = offerToken.isNotBlank()
        Log.d("BillingDiag", "launchPurchase request productId=$productId hasOfferToken=$hasToken")
        // Optimistically mark launching so UI blocks immediately (before Play sheet)
        _uiState.value = _uiState.value.copy(
            purchaseInProgress = true,
            purchaseFlow = PurchaseFlowState(phase = PurchasePhase.Launching, blocking = true)
        )
        val userId = currentUserId ?: "anonymous"
        val obfuscated = billingManager.obfuscatedAccountId(userId)
        val result = billingManager.launchBillingFlow(activity, details, offerToken, obfuscated)
        Log.d(
            "BillingDiag",
            "launchBillingFlow responseCode=${result.responseCode} debugMessage=${result.debugMessage} productId=$productId"
        )
        // If launch failed immediately, reset blocking so UI is not stuck
        if (result.responseCode != com.android.billingclient.api.BillingClient.BillingResponseCode.OK) {
            val isUserCancel =
                result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.USER_CANCELED
            _uiState.value = _uiState.value.copy(
                purchaseInProgress = false,
                purchaseFlow = PurchaseFlowState(
                    phase = if (isUserCancel) PurchasePhase.Idle else PurchasePhase.Idle,
                    blocking = false
                )
            )
        } else {
            // Keep blocking until PurchasesUpdatedListener transitions to Verifying/Success
            // Safety timeout 30s: if Play sheet was shown but no callback arrives (crash, no network), unblock
            viewModelScope.launch {
                kotlinx.coroutines.delay(30000.milliseconds)
                if (_uiState.value.purchaseFlow.phase == PurchasePhase.Launching && _uiState.value.purchaseInProgress) {
                    Log.w(
                        "BillingVM",
                        "launch timeout 30s still in Launching - resetting to Idle to unblock modal"
                    )
                    _uiState.value = _uiState.value.copy(
                        purchaseInProgress = false,
                        purchaseFlow = PurchaseFlowState(
                            phase = PurchasePhase.Idle,
                            blocking = false
                        )
                    )
                }
            }
        }
        onResult(result.responseCode)
    }

    /**
     * Returns formattedPrice for a given productId + basePlanId.
     * Requires exact basePlanId match — no fallback to sibling offer.
     * If basePlanId is Draft (e.g. plus monthly) or mismatched, returns null (price unavailable).
     * Per-variant: PERSONAL monthly/annual and PLUS annual remain independent of PLUS monthly Draft.
     */
    fun getFormattedPrice(productId: String?, basePlanId: String?): String? {
        if (productId == null || basePlanId == null) return null
        val details = _uiState.value.productDetails[productId] ?: return null
        val offers = details.subscriptionOfferDetails ?: return null
        val offer = offers.find { it.basePlanId == basePlanId }
        if (offer == null) {
            Log.d(
                "BillingDiag",
                "getFormattedPrice no exact offer productId=$productId basePlanId=$basePlanId available=${offers.map { it.basePlanId }}"
            )
            return null
        }
        val price = offer.pricingPhases.pricingPhaseList.firstOrNull()?.formattedPrice
        val present = price != null
        Log.d(
            "BillingDiag",
            "getFormattedPrice productId=$productId basePlanId=$basePlanId matched=true hasPrice=$present price=${price ?: "missing"}"
        )
        return price
    }

    fun getOfferToken(productId: String?, basePlanId: String?): String? {
        if (productId == null || basePlanId == null) return null
        val details = _uiState.value.productDetails[productId] ?: return null
        val offers = details.subscriptionOfferDetails ?: return null
        val offer = offers.find { it.basePlanId == basePlanId }
        if (offer == null) {
            Log.d(
                "BillingDiag",
                "getOfferToken no exact offer productId=$productId basePlanId=$basePlanId available=${offers.map { it.basePlanId }}"
            )
            return null
        }
        val hasToken = true
        Log.d(
            "BillingDiag",
            "getOfferToken productId=$productId basePlanId=$basePlanId matched=true hasOfferToken=$hasToken"
        )
        return offer.offerToken
    }

    // Backward compat overloads — deprecated, require exact basePlanId via two-arg version.
    @Deprecated("Use getFormattedPrice(productId, basePlanId) with exact basePlanId")
    fun getFormattedPrice(): String? = null

    @Deprecated("Use getOfferToken(productId, basePlanId) with exact basePlanId")
    fun getOfferToken(): String? = null
}
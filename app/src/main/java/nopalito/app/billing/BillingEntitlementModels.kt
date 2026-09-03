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

/**
 * BillingEntitlementUiState — single UI domain state for entitlement.
 * Backend is authority; this is in-memory last-confirmed state.
 */
data class BillingEntitlementUiState(
    val ownerUserId: String? = null,
    val plan: String = "FREE",
    val subscriptionStatus: String? = null,
    val storageLimitBytes: Long = 52_428_800L,
    val storageUsedBytes: Long? = null,
    val storageAvailableBytes: Long? = null,
    val expiryTime: String? = null,
    val billingPeriod: String? = null,
    val isActiveEntitlement: Boolean = false,
    val entitlementReason: String? = null,
    val isRefreshing: Boolean = false,
    val lastConfirmedAtMillis: Long? = null,
    val refreshReason: String? = null,
    val recoverableError: BillingRefreshError? = null
)

data class BillingRefreshError(
    val code: String? = null,
    val httpStatus: Int? = null,
    val message: String? = null,
    val retryScheduled: Boolean = false
)

enum class BillingRefreshReason(val key: String, val priority: Int) {
    SESSION_RESTORED("SESSION_RESTORED", 3),
    LOGIN("LOGIN", 4),
    ACCOUNT_SWITCH("ACCOUNT_SWITCH", 5),
    FOREGROUND("FOREGROUND", 1),
    PURCHASE_VERIFIED("PURCHASE_VERIFIED", 4),
    RESTORE_COMPLETED("RESTORE_COMPLETED", 4),
    SEMANTIC_403("SEMANTIC_403", 3),
    MANUAL("MANUAL", 2);

    companion object {
        fun maxPriority(reasons: List<BillingRefreshReason>): BillingRefreshReason =
            reasons.maxByOrNull { it.priority } ?: FOREGROUND
    }
}

const val FREE_STORAGE_LIMIT_BYTES: Long = 52_428_800L
const val FOREGROUND_TTL_MILLIS: Long = 10 * 60 * 1000L
val RETRY_DELAYS_MILLIS = longArrayOf(1000L, 5000L, 25000L)
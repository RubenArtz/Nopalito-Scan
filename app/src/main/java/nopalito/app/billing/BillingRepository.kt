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

import android.content.Context
import nopalito.app.ui.screens.cloud.network.CloudApiClient

/**
 * BillingRepository — combines backend plans and Play ProductDetails.
 *
 * - Fetches backend plans (no prices) via BillingApi.getPlans().
 * - Fetches Play ProductDetails for legacy 4 productIds.
 * - Verifies purchase on backend via BillingApi.verifyGooglePlay (only purchaseToken + productId).
 */
class BillingRepository(
    private val context: Context
) {
    private val api: nopalito.app.ui.screens.cloud.network.BillingApi
        get() = CloudApiClient.getInstance(context).billingApi

    suspend fun fetchBackendPlans(): List<nopalito.app.ui.screens.cloud.model.BillingPlan> {
        try {
            val resp = api.getPlans()
            if (resp.isSuccessful) {
                val body = resp.body()
                val plans = body?.data?.plans ?: emptyList()
                // Sanitized log — network response source, never prices (prices only from Play)
                val sanitized = plans.joinToString(", ") { p ->
                    "${p.id}:${p.googlePlay?.productId ?: "null"}:${p.googlePlay?.basePlanIdMonthly ?: "null"}:${p.googlePlay?.basePlanIdAnnual ?: "null"}:enabled=${p.enabled}:legacy=${p.legacy}"
                }
                android.util.Log.d(
                    "BillingDiag",
                    "BillingRepository fetchBackendPlans network success code=${resp.code()} plans=[$sanitized]"
                )
                // Defensive: detect legacy nopalito_* still served (should be personal/plus)
                val hasLegacy = plans.any {
                    it.googlePlay?.productId?.startsWith("nopalito_") == true || it.googlePlay?.basePlanIdMonthly?.startsWith(
                        "nopalito_"
                    ) == true || it.googlePlay?.basePlanIdAnnual?.startsWith("nopalito_") == true
                }
                if (hasLegacy) {
                    android.util.Log.w(
                        "BillingDiag",
                        "BillingRepository LEGACY mapping detected — backend still serves nopalito_* (expected personal/plus). Source=network response, not DataStore or fallback. Needs backend staging mapping fix."
                    )
                }
                return plans
            } else {
                android.util.Log.w(
                    "BillingDiag",
                    "BillingRepository fetchBackendPlans network failed code=${resp.code()} message=${resp.message()} source=network"
                )
                return emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "BillingDiag",
                "BillingRepository fetchBackendPlans exception source=network error=${e.message}"
            )
            return emptyList()
        }
    }

    suspend fun verifyPurchase(purchaseToken: String, productId: String?): Result<Unit> {
        return try {
            val req = nopalito.app.ui.screens.cloud.model.GooglePlayVerifyRequest(
                purchaseToken = purchaseToken,
                productId = productId
            )
            val resp = api.verifyGooglePlay(req)
            if (resp.isSuccessful) {
                Result.success(Unit)
            } else {
                val code = resp.code()
                val errBody = try {
                    resp.errorBody()?.string()
                } catch (_: Exception) {
                    null
                }
                android.util.Log.w(
                    "BillingDiag",
                    "BillingRepository verify failed code=$code err=${errBody?.take(300)}"
                )
                Result.failure(Exception("verify failed $code ${errBody ?: ""}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * After a successful verify, refresh billing status + storage usage together.
     * Both are server-authoritative; caller must await this before showing success animation.
     */
    suspend fun refreshAfterVerify(): Pair<Result<nopalito.app.ui.screens.cloud.model.BillingStatusData>, Result<nopalito.app.ui.screens.cloud.model.StorageUsage>> {
        val status = fetchBillingStatus()
        val usage = fetchStorageUsage()
        return status to usage
    }

    suspend fun fetchBillingStatus(): Result<nopalito.app.ui.screens.cloud.model.BillingStatusData> {
        return try {
            val resp = api.getStatus()
            if (resp.isSuccessful) {
                val data = resp.body()?.data
                if (data != null) Result.success(data)
                else Result.failure(Exception("empty status"))
            } else {
                Result.failure(Exception("status failed ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchStorageUsage(): Result<nopalito.app.ui.screens.cloud.model.StorageUsage> {
        return try {
            val storageApi = CloudApiClient.getInstance(context).storage
            val resp = storageApi.getUsage()
            if (resp.isSuccessful) {
                val data = resp.body()?.data
                if (data != null) Result.success(data)
                else Result.failure(Exception("empty usage"))
            } else {
                Result.failure(Exception("usage failed ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
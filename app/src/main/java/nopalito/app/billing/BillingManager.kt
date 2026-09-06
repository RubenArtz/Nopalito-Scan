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
import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BillingManager — wraps BillingClient with helper methods.
 *
 * Responsibilities:
 * - Start connection with enablePendingPurchases().
 * - Query ProductDetails for legacy 4 productIds (formattedPrice is only price source).
 * - Launch billing flow with obfuscatedAccountId = SHA-256(userId) (D2 binding).
 * - Query existing purchases for restore on new device.
 * - Acknowledge purchases (best-effort).
 *
 * All comments and identifiers are in English per project rule.
 */
class BillingManager private constructor(
    private val context: Context,
    private val purchasesListener: PurchasesUpdatedListener
) {
    private var billingClient: BillingClient? = null
    private var isReady = false

    fun startConnection(onReady: (Boolean) -> Unit) {
        if (billingClient == null) {
            val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
            billingClient = BillingClient.newBuilder(context)
                .setListener(purchasesListener)
                .enablePendingPurchases(pendingPurchasesParams)
                .build()
        }
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                isReady = result.responseCode == BillingClient.BillingResponseCode.OK
                Log.d(
                    "BillingDiag",
                    "BillingClient setup responseCode=${result.responseCode} debugMessage=${result.debugMessage} connected=$isReady"
                )
                Log.d("BillingVM", "BillingClient connected: $isReady code=${result.responseCode}")
                onReady(isReady)
            }

            override fun onBillingServiceDisconnected() {
                isReady = false
                Log.w("BillingDiag", "BillingClient disconnected connected=false")
            }
        })
    }

    /**
     * Queries ProductDetails for the given subscription productIds (personal, plus).
     * Uses BillingClient.ProductType.SUBS and Billing Library 9 QueryProductDetailsResult.
     * Never logs purchase tokens or user IDs. BillingDiag logs are non-sensitive only.
     */
    suspend fun queryProductDetails(productIds: List<String>): Map<String, ProductDetails> =
        suspendCancellableCoroutine { cont ->
            val client = billingClient
            if (client == null || !isReady) {
                Log.w(
                    "BillingDiag",
                    "queryProductDetails blocked clientNull=${client == null} isReady=$isReady requested=$productIds"
                )
                cont.resume(emptyMap())
                return@suspendCancellableCoroutine
            }
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    productIds.map {
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(it)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build()
                    }
                ).build()
            Log.d("BillingDiag", "queryProductDetailsAsync requested=$productIds type=SUBS")
            client.queryProductDetailsAsync(params) { result, productDetailsResult ->
                Log.d(
                    "BillingDiag",
                    "queryProductDetailsAsync responseCode=${result.responseCode} debugMessage=${result.debugMessage} requested=$productIds"
                )
                // Billing Library 9 unfetched products — expose why product unavailable
                try {
                    val unfetched = productDetailsResult.unfetchedProductList
                    if (unfetched.isNotEmpty()) {
                        for (u in unfetched) {
                            // UnfetchedProduct has productId and statusCode; debugMessage may not exist on all versions
                            val status = try {
                                u.statusCode.toString()
                            } catch (_: Exception) {
                                "unknown"
                            }
                            Log.w(
                                "BillingDiag",
                                "unfetched productId=${u.productId} statusCode=$status"
                            )
                        }
                    } else {
                        Log.d("BillingDiag", "unfetchedProductList empty")
                    }
                } catch (_: Exception) {
                    Log.d(
                        "BillingDiag",
                        "unfetchedProductList not available on this Billing Library version"
                    )
                }
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    val detailsList: List<ProductDetails> = try {
                        productDetailsResult.productDetailsList
                    } catch (_: Exception) {
                        emptyList()
                    }
                    Log.d(
                        "BillingDiag",
                        "ProductDetails count=${detailsList.size} returnedIds=${detailsList.map { it.productId }}"
                    )
                    for (d in detailsList) {
                        val offers = d.subscriptionOfferDetails
                        Log.d(
                            "BillingDiag",
                            "ProductDetails productId=${d.productId} offers=${offers?.size ?: 0}"
                        )
                        offers?.forEach { offer ->
                            val phases = offer.pricingPhases.pricingPhaseList
                            val billingPeriod = phases.firstOrNull()?.billingPeriod ?: "unknown"
                            val formattedPrice = phases.firstOrNull()?.formattedPrice ?: "missing"
                            val hasToken = true
                            Log.d(
                                "BillingDiag",
                                " offer basePlanId=${offer.basePlanId} offerId=${offer.offerId ?: "null"} hasOfferToken=$hasToken billingPeriod=$billingPeriod formattedPrice=$formattedPrice"
                            )
                        }
                    }
                    val map = mutableMapOf<String, ProductDetails>()
                    for (d in detailsList) {
                        map[d.productId] = d
                    }
                    cont.resume(map)
                } else {
                    Log.w(
                        "BillingDiag",
                        "queryProductDetailsAsync failed responseCode=${result.responseCode} requested=$productIds"
                    )
                    cont.resume(emptyMap())
                }
            }
        }

    /**
     * Launches billing flow with exact offerToken for selected basePlanId.
     * BillingDiag logs responseCode/debugMessage only — never token or account ID.
     */
    fun launchBillingFlow(
        activity: Activity,
        productDetails: ProductDetails,
        offerToken: String,
        obfuscatedAccountId: String
    ): BillingResult {
        val client = billingClient ?: run {
            Log.w("BillingDiag", "launchBillingFlow blocked SERVICE_UNAVAILABLE client null")
            return BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE).build()
        }
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .setObfuscatedAccountId(obfuscatedAccountId)
            .build()
        val result = client.launchBillingFlow(activity, params)
        Log.d(
            "BillingDiag",
            "launchBillingFlow responseCode=${result.responseCode} debugMessage=${result.debugMessage} productId=${productDetails.productId} hasOfferToken=${offerToken.isNotBlank()}"
        )
        return result
    }

    /**
     * Queries existing purchases (for restore).
     */
    suspend fun queryPurchases(): List<Purchase> = suspendCancellableCoroutine { cont ->
        val client = billingClient
        if (client == null || !isReady) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                cont.resume(purchases)
            } else {
                cont.resume(emptyList())
            }
        }
    }

    suspend fun acknowledgePurchase(purchase: Purchase): BillingResult =
        suspendCancellableCoroutine { cont ->
            val client = billingClient
            if (client == null || !isReady) {
                cont.resumeWithException(IllegalStateException("BillingClient not ready"))
                return@suspendCancellableCoroutine
            }
            if (purchase.isAcknowledged) {
                cont.resume(
                    BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK)
                        .build()
                )
                return@suspendCancellableCoroutine
            }
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(params) { result ->
                cont.resume(result)
            }
        }

    /**
     * Hashes userId with SHA-256 for obfuscatedAccountId (max 64 chars).
     */
    fun obfuscatedAccountId(userId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(64)
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: BillingManager? = null

        fun getInstance(context: Context, listener: PurchasesUpdatedListener): BillingManager {
            return instance ?: synchronized(this) {
                instance ?: BillingManager(context.applicationContext, listener).also {
                    instance = it
                }
            }
        }
    }
}
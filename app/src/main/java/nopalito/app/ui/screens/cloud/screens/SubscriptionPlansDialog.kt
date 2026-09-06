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

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nopalito.app.R
import nopalito.app.ui.screens.cloud.viewmodel.PurchasePhase
import nopalito.app.ui.screens.cloud.viewmodel.SubscriptionPlansViewModel
import kotlin.time.Duration.Companion.milliseconds

private enum class BillingPeriod { Monthly, Annual }

private data class UiPlan(
    val id: String,
    val accent: Color,
    val storageLabelRes: Int,
    val titleRes: Int,
    val subtitleRes: Int,
    val productId: String?,
    val basePlanIdMonthly: String?,
    val basePlanIdAnnual: String?,
    val features: List<Int>
)

/**
 * Opens Google Play subscription management for nopalito.app.
 * Uses official Play Store deep link, product-specific when available.
 * No tokens or internal data exposed.
 */
private fun openManageSubscription(context: android.content.Context, productId: String?) {
    val pkg = "nopalito.app"
    val urls = buildList {
        if (productId != null) add("https://play.google.com/store/account/subscriptions?package=$pkg&sku=$productId")
        add("https://play.google.com/store/account/subscriptions?package=$pkg")
        add("https://play.google.com/store/account/subscriptions")
    }
    // Try Play Store app first
    for (url in urls) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                setPackage("com.android.vending")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // try next url
        }
    }
    // Fallback to browser
    for (url in urls) {
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            return
        } catch (_: Exception) {
            // next
        }
    }
    // Use application context string to avoid LocalContext configuration staleness
    Toast.makeText(
        context.applicationContext,
        context.applicationContext.getString(R.string.billing_manage_unavailable),
        Toast.LENGTH_SHORT
    ).show()
}

/**
 * SubscriptionPlansDialog — premium design with app theme (MaterialTheme) + billing logic.
 * No hardcoded prices — all prices from Play. FREE always disabled.
 *
 * Post-purchase reactive flow:
 *  - PURCHASED from Play -> VerifyingBackend (blocking, "Processing purchase")
 *  - backend verify -> refresh status+usage (awaited)
 *  - SuccessApplied -> shows premium success animation (green check) and auto-dismisses only after sync
 *  - onPurchaseCompleted is invoked BEFORE closing so CloudStorageScreen already has updated usage
 */
@Composable
fun SubscriptionPlansDialog(
    onDismiss: () -> Unit,
    currentPlan: String? = null,
    onPurchaseCompleted: (() -> Unit)? = null,
    viewModel: SubscriptionPlansViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var period by remember { mutableStateOf(BillingPeriod.Monthly) }
    val context = LocalContext.current
    val activity = context as? Activity
    val liveBillingPlan = uiState.billingStatus?.plan ?: uiState.currentPlan
    val effectiveCurrentPlan = (liveBillingPlan ?: currentPlan)?.uppercase()?.trim() ?: "FREE"
    val scope = rememberCoroutineScope()

    val purchaseFlow = uiState.purchaseFlow
    val isVerifying =
        purchaseFlow.phase == PurchasePhase.VerifyingBackend || purchaseFlow.phase == PurchasePhase.Launching || purchaseFlow.phase == PurchasePhase.ReceivedFromPlay
    val isSuccess = purchaseFlow.phase == PurchasePhase.SuccessApplied
    val isError = purchaseFlow.phase == PurchasePhase.Error
    val isPending = purchaseFlow.phase == PurchasePhase.Pending
    val blocking = purchaseFlow.blocking || uiState.purchaseInProgress || isVerifying || isSuccess

    LaunchedEffect(isSuccess) {
        if (isSuccess) {
            delay(2000.milliseconds)
            try {
                onPurchaseCompleted?.invoke()
            } catch (_: Exception) {
            }
            delay(120.milliseconds)
            onDismiss()
            viewModel.clearPurchaseFlow()
        }
    }

    // Build UI plans from backend + Play mapping (no hardcoded prices)
    val backendPlans = uiState.plans.ifEmpty {
        emptyList()
    }
    val uiPlans = remember(backendPlans) {
        if (backendPlans.isNotEmpty()) {
            backendPlans.map { bp ->
                when (bp.id.uppercase()) {
                    "PERSONAL" -> UiPlan(
                        id = "personal",
                        accent = Color(0xFF0CAD55), // Primary from theme
                        storageLabelRes = R.string.premium_personal_storage,
                        titleRes = R.string.premium_personal,
                        subtitleRes = R.string.premium_personal_subtitle,
                        productId = bp.googlePlay?.productId,
                        basePlanIdMonthly = bp.googlePlay?.basePlanIdMonthly,
                        basePlanIdAnnual = bp.googlePlay?.basePlanIdAnnual,
                        features = listOf(
                            R.string.billing_feature_storage_personal,
                            R.string.billing_feature_scan,
                            R.string.billing_feature_sync,
                            R.string.billing_feature_devices_5,
                            R.string.billing_feature_no_ads
                        )
                    )

                    "PLUS" -> UiPlan(
                        id = "plus",
                        accent = Color(0xFF7963AA), // Tertiary from theme
                        storageLabelRes = R.string.premium_plus_storage,
                        titleRes = R.string.premium_plus,
                        subtitleRes = R.string.premium_plus_subtitle,
                        productId = bp.googlePlay?.productId,
                        basePlanIdMonthly = bp.googlePlay?.basePlanIdMonthly,
                        basePlanIdAnnual = bp.googlePlay?.basePlanIdAnnual,
                        features = listOf(
                            R.string.billing_feature_storage_plus,
                            R.string.billing_feature_scan,
                            R.string.billing_feature_sync,
                            R.string.billing_feature_devices_5,
                            R.string.billing_feature_no_ads
                        )
                    )

                    else -> UiPlan(
                        id = "free",
                        accent = Color(0xFF7D9989),
                        storageLabelRes = R.string.premium_free_storage,
                        titleRes = R.string.premium_free,
                        subtitleRes = R.string.premium_free_subtitle,
                        productId = null,
                        basePlanIdMonthly = null,
                        basePlanIdAnnual = null,
                        features = listOf(
                            R.string.billing_feature_storage_free,
                            R.string.billing_feature_scan,
                            R.string.billing_feature_sync,
                            R.string.billing_feature_devices_1,
                            R.string.billing_feature_no_ads
                        )
                    )
                }
            }.let { list ->
                val free = list.find { it.id == "free" }
                val others = list.filter { it.id != "free" }.sortedBy { it.id }
                if (free != null) listOf(free) + others else list
            }
        } else {
            listOf(
                UiPlan(
                    "free",
                    Color(0xFF7D9989),
                    R.string.premium_free_storage,
                    R.string.premium_free,
                    R.string.premium_free_subtitle,
                    null, null, null,
                    listOf(
                        R.string.billing_feature_storage_free,
                        R.string.billing_feature_scan,
                        R.string.billing_feature_sync,
                        R.string.billing_feature_devices_1,
                        R.string.billing_feature_no_ads
                    )
                ),
                UiPlan(
                    "personal",
                    Color(0xFF0CAD55),
                    R.string.premium_personal_storage,
                    R.string.premium_personal,
                    R.string.premium_personal_subtitle,
                    "personal", "personal", "personal-yearly",
                    listOf(
                        R.string.billing_feature_storage_personal,
                        R.string.billing_feature_scan,
                        R.string.billing_feature_sync,
                        R.string.billing_feature_devices_5,
                        R.string.billing_feature_no_ads
                    )
                ),
                UiPlan(
                    "plus",
                    Color(0xFF7963AA),
                    R.string.premium_plus_storage,
                    R.string.premium_plus,
                    R.string.premium_plus_subtitle,
                    "plus", "plus", "plus-yearly",
                    listOf(
                        R.string.billing_feature_storage_plus,
                        R.string.billing_feature_scan,
                        R.string.billing_feature_sync,
                        R.string.billing_feature_devices_5,
                        R.string.billing_feature_no_ads
                    )
                )
            )
        }
    }

    val initialPage = remember(effectiveCurrentPlan, uiPlans) {
        when (effectiveCurrentPlan) {
            "PERSONAL" -> uiPlans.indexOfFirst { it.id == "personal" }.takeIf { it >= 0 } ?: 1
            "PLUS" -> uiPlans.indexOfFirst { it.id == "plus" }.takeIf { it >= 0 } ?: 2
            else -> 0
        }
    }

    val currentDisplayName = when (effectiveCurrentPlan) {
        "PERSONAL" -> stringResource(R.string.premium_personal)
        "PLUS" -> stringResource(R.string.premium_plus)
        else -> stringResource(R.string.premium_free)
    }

    // Show manage button only if backend status indicates Google Play subscription
    val showManage = viewModel.shouldShowManage()
    val manageProductId = viewModel.getManageProductId()
    val isRestoring = uiState.isRestoring

    Dialog(
        onDismissRequest = { if (!blocking) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !blocking,
            dismissOnClickOutside = !blocking
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                ) {
                    // Top bar — theme aligned
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.premium_close),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.premium_nopalito_cloud),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                        val chipColor = when (effectiveCurrentPlan) {
                            "PERSONAL" -> MaterialTheme.colorScheme.primaryContainer
                            "PLUS" -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                        val chipTextColor = when (effectiveCurrentPlan) {
                            "PERSONAL" -> MaterialTheme.colorScheme.onPrimaryContainer
                            "PLUS" -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(chipColor)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = currentDisplayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = chipTextColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Title
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.premium_more_storage),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.premium_more_power),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.premium_swipe),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Current plan banner — theme
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when (effectiveCurrentPlan) {
                                    "PERSONAL" -> MaterialTheme.colorScheme.primaryContainer.copy(
                                        alpha = 0.7f
                                    )

                                    "PLUS" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(
                                R.string.premium_current_plan,
                                currentDisplayName
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = when (effectiveCurrentPlan) {
                                "PERSONAL" -> MaterialTheme.colorScheme.onPrimaryContainer
                                "PLUS" -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Support-the-app banner — highlighted call to action: the
                    // subscription keeps the app ad-free and funds more free features
                    // for everyone in the future. Lowered slightly per user feedback.
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Favorite,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.billing_support_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.billing_support_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Billing toggle — theme
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (period == BillingPeriod.Monthly) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { period = BillingPeriod.Monthly }
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.premium_monthly),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (period == BillingPeriod.Monthly) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (period == BillingPeriod.Annual) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { period = BillingPeriod.Annual }
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.premium_annual),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (period == BillingPeriod.Annual) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Loading / error states
                    if (uiState.isLoading) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 24.dp, vertical = 24.dp)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.billing_loading),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else if (uiState.errorKey != null) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(16.dp)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.billing_plans_load_error),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.billing_retry_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else if (!uiState.billingConnected) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(16.dp)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.billing_not_connected),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))

                        // Pager — premium cards
                        val pagerState =
                            rememberPagerState(
                                pageCount = { uiPlans.size },
                                initialPage = initialPage
                            )

                        HorizontalPager(
                            state = pagerState,
                            contentPadding = PaddingValues(horizontal = 24.dp),
                            pageSpacing = 16.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) { page ->
                            val plan = uiPlans[page]
                            val isSelected = pagerState.currentPage == page
                            val isCurrent = plan.id.equals(effectiveCurrentPlan, ignoreCase = true)
                            val productId = plan.productId
                            val basePlanId =
                                if (period == BillingPeriod.Monthly) plan.basePlanIdMonthly else plan.basePlanIdAnnual
                            val price = if (plan.id == "free") {
                                null
                            } else {
                                viewModel.getFormattedPrice(productId, basePlanId)
                            }
                            val offerToken =
                                if (plan.id == "free") null else viewModel.getOfferToken(
                                    productId,
                                    basePlanId
                                )
                            val isAvailable =
                                plan.id == "free" || (productId != null && basePlanId != null && offerToken != null && price != null)
                            if (plan.id != "free") {
                                val exactMatched = (offerToken != null && price != null)
                                val periodName =
                                    if (period == BillingPeriod.Monthly) "Monthly" else "Annual"
                                android.util.Log.d(
                                    "BillingDiag",
                                    "UI selected plan=${plan.id} period=$periodName productId=$productId basePlanId=$basePlanId matched=$exactMatched hasOfferToken=${offerToken != null} hasPrice=${price != null} isAvailable=$isAvailable price=${price ?: "missing"}"
                                )
                                if (!exactMatched) {
                                    android.util.Log.w(
                                        "BillingDiag",
                                        "UI no exact offer plan=${plan.id} basePlanId=$basePlanId expected Active, check Play Active/country/price and queryProductDetails unfetchedProductList"
                                    )
                                }
                            }

                            PremiumPlanCard(
                                plan = plan,
                                price = price,
                                offerToken = offerToken,
                                isSelected = isSelected,
                                isCurrent = isCurrent,
                                isAvailable = isAvailable,
                                isAnnual = period == BillingPeriod.Annual,
                                viewModel = viewModel,
                                activity = activity,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(560.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Arrows + dots — theme
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val prev = (pagerState.currentPage - 1).coerceAtLeast(0)
                                        pagerState.animateScrollToPage(prev)
                                    }
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = stringResource(R.string.premium_previous),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(uiPlans.size) { index ->
                                    val isActive = pagerState.currentPage == index
                                    Box(
                                        modifier = Modifier
                                            .size(
                                                width = if (isActive) 20.dp else 8.dp,
                                                height = 8.dp
                                            )
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(
                                                if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                            )
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val next =
                                            (pagerState.currentPage + 1).coerceAtMost(uiPlans.size - 1)
                                        pagerState.animateScrollToPage(next)
                                    }
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = stringResource(R.string.premium_next),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Restore purchases — visible to every authenticated user including FREE
                        // Manage subscription — visible only if backend indicates Google Play subscription
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.restorePurchasesManual() },
                                enabled = !isRestoring,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { role = androidx.compose.ui.semantics.Role.Button },
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                if (isRestoring) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.billing_restore_in_progress),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.billing_restore_purchases),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.billing_restore_purchases),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            // Restore result message
                            uiState.restoreMessageRes?.let { resId ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (uiState.restoreIsError) MaterialTheme.colorScheme.errorContainer
                                            else MaterialTheme.colorScheme.surfaceContainerHigh
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(resId),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (uiState.restoreIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (uiState.showRestoreRetry) {
                                        androidx.compose.material3.TextButton(
                                            onClick = { viewModel.restorePurchasesManual() },
                                            enabled = !isRestoring
                                        ) {
                                            Text(text = stringResource(R.string.billing_restore_retry))
                                        }
                                    }
                                }
                            }
                            if (showManage) {
                                OutlinedButton(
                                    onClick = { openManageSubscription(context, manageProductId) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .semantics {
                                            role = androidx.compose.ui.semantics.Role.Button
                                        },
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = stringResource(R.string.billing_manage_subscription_desc),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.billing_manage_subscription),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.billing_manage_subscription_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                if (effectiveCurrentPlan == "FREE") {
                                    Text(
                                        text = stringResource(R.string.billing_free_manage_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Verifying / Pending / Error inline feedback (below pager, above restore)
                    if (isVerifying) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = stringResource(R.string.billing_purchase_processing),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.billing_purchase_processing_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (isPending) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.billing_purchase_pending),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    if (isError) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = purchaseFlow.errorMessage?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.billing_error_generic),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center
                                )
                                if (purchaseFlow.showRetry) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.retryLastVerification() },
                                        shape = RoundedCornerShape(999.dp)
                                    ) {
                                        Text(text = stringResource(R.string.billing_restore_retry))
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.billing_restore_retry_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Full-screen blocking scrim + premium success animation (overlay on top of Surface)
            if (isVerifying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.28f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    BillingVerifyingOverlay()
                }
            }
            if (isSuccess) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.42f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 24.dp, vertical = 28.dp)
                    ) {
                        BillingPurchaseSuccessAnimation(
                            planName = purchaseFlow.appliedPlan
                                ?: effectiveCurrentPlan
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumPlanCard(
    plan: UiPlan,
    price: String?,
    offerToken: String?,
    isSelected: Boolean,
    isCurrent: Boolean,
    isAvailable: Boolean,
    isAnnual: Boolean,
    viewModel: SubscriptionPlansViewModel,
    activity: Activity?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Hoist strings via stringResource for configuration-awareness (avoid LocalContext.getString stale)
    val strProductDetailsUnavailable = stringResource(R.string.billing_product_details_unavailable)
    val strOfferUnavailable = stringResource(R.string.billing_offer_unavailable)
    val strActivityUnavailable = stringResource(R.string.billing_activity_unavailable)
    val strUserCanceled = stringResource(R.string.billing_error_user_canceled)
    val strServiceUnavailable = stringResource(R.string.billing_error_service_unavailable)
    val strBillingUnavailable = stringResource(R.string.billing_error_billing_unavailable)
    val strItemUnavailable = stringResource(R.string.billing_error_item_unavailable)
    val strAlreadyOwned = stringResource(R.string.billing_error_item_already_owned)
    val strNotOwned = stringResource(R.string.billing_error_item_not_owned)
    val strGenericError = stringResource(R.string.billing_error_generic)
    val strProcessing = stringResource(R.string.billing_purchase_processing)
    val borderColor = when {
        !isSelected -> MaterialTheme.colorScheme.outlineVariant
        plan.id == "personal" -> MaterialTheme.colorScheme.primary
        plan.id == "plus" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    // Theme-aligned gradient — light/dark aware via MaterialTheme
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val cardBg = when (plan.id) {
        "personal" -> if (isDark) Brush.verticalGradient(
            listOf(
                Color(0xFF1A2A1A),
                Color(0xFF141414)
            )
        ) else Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.surface
            )
        )

        "plus" -> if (isDark) Brush.verticalGradient(
            listOf(
                Color(0xFF1E1A2E),
                Color(0xFF141414)
            )
        ) else Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.surface
            )
        )

        else -> Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceContainer,
                MaterialTheme.colorScheme.surfaceContainerHigh
            )
        )
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(width = if (isSelected) 1.5.dp else 1.dp, color = borderColor),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 8.dp else 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(cardBg)
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val badgeText: String? = when (plan.id) {
                            "personal" -> "✦ ${stringResource(R.string.premium_most_popular)}"
                            "plus" -> "★ ${stringResource(R.string.premium_pro)}"
                            else -> null
                        }
                        if (badgeText != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        when (plan.id) {
                                            "personal" -> MaterialTheme.colorScheme.primaryContainer
                                            "plus" -> MaterialTheme.colorScheme.tertiaryContainer
                                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (plan.id) {
                                        "personal" -> MaterialTheme.colorScheme.onPrimaryContainer
                                        "plus" -> MaterialTheme.colorScheme.onTertiaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        when {
                            isCurrent -> {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "✓ ${stringResource(R.string.premium_current)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            isSelected -> {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "● ${stringResource(R.string.premium_selected)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (plan.id) {
                                "personal" -> Icons.Filled.Favorite
                                "plus" -> Icons.Filled.Bolt
                                else -> Icons.Filled.Storage
                            },
                            contentDescription = null,
                            tint = when (plan.id) {
                                "personal" -> MaterialTheme.colorScheme.primary
                                "plus" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(plan.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when (plan.id) {
                        "personal" -> MaterialTheme.colorScheme.primary
                        "plus" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(plan.storageLabelRes),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = when (plan.id) {
                        "personal" -> MaterialTheme.colorScheme.primary
                        "plus" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 36.sp,
                    lineHeight = 36.sp
                )

                Text(
                    text = stringResource(plan.subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )

                // Feature list — theme aligned, no extra pricing
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    plan.features.forEach { featRes ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (plan.id) {
                                            "personal" -> MaterialTheme.colorScheme.primaryContainer
                                            "plus" -> MaterialTheme.colorScheme.tertiaryContainer
                                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = when (plan.id) {
                                        "personal" -> MaterialTheme.colorScheme.onPrimaryContainer
                                        "plus" -> MaterialTheme.colorScheme.onTertiaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Text(
                                text = stringResource(featRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (plan.id == "free") {
                            Text(
                                text = stringResource(R.string.premium_free_price),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 24.sp
                            )
                            Text(
                                text = stringResource(R.string.premium_free_subprice),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.billing_free_included),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            if (price != null) {
                                Row(
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = price,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 26.sp
                                    )
                                    val suffix =
                                        if (isAnnual) stringResource(R.string.premium_per_year) else stringResource(
                                            R.string.premium_per_month
                                        )
                                    Text(
                                        text = suffix,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.premium_cancela),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.billing_price_unavailable),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 14.sp
                                )
                                if (!isAvailable) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.billing_draft_unavailable),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Feature hint for free
                if (plan.id == "free") {
                    Text(
                        text = stringResource(R.string.billing_free_manage_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Purchase button — FREE always disabled + block while verifying/success (double-tap guard)
                val isPurchaseBlocking = viewModel.isPurchaseBlocking()
                val buttonEnabled =
                    if (plan.id == "free") false else (isAvailable && !isCurrent && !isPurchaseBlocking)
                if (plan.id != "free") {
                    val reason = when {
                        isCurrent -> "isCurrent"
                        !isAvailable -> "unavailable offerToken=${offerToken != null} price=${price != null}"
                        else -> "enabled"
                    }
                    val periodName = if (isAnnual) "Annual" else "Monthly"
                    android.util.Log.d(
                        "BillingDiag",
                        "UI button plan=${plan.id} period=$periodName enabled=$buttonEnabled reason=$reason hasOfferToken=${offerToken != null} hasPrice=${price != null}"
                    )
                } else {
                    android.util.Log.d(
                        "BillingDiag",
                        "UI button plan=free always disabled (not purchasable)"
                    )
                }

                Button(
                    onClick = {
                        if (plan.id == "free") {
                            return@Button
                        }
                        val productId = plan.productId
                        val basePlanId =
                            if (isAnnual) plan.basePlanIdAnnual else plan.basePlanIdMonthly
                        android.util.Log.d(
                            "BillingDiag",
                            "onClick plan=${plan.id} productId=$productId basePlanId=$basePlanId hasPrice=${price != null} hasOfferToken=${offerToken != null} isAvailable=$isAvailable"
                        )
                        android.util.Log.d(
                            "BillingDialog",
                            "onClick plan=${plan.id} productId=$productId basePlanId=$basePlanId price=$price offerToken=${if (offerToken != null) "present" else "null"}"
                        )
                        if (productId == null || basePlanId == null) {
                            android.util.Log.w(
                                "BillingDiag",
                                "launch blocked null productId/basePlanId plan=${plan.id} productId=$productId basePlanId=$basePlanId"
                            )
                            android.util.Log.w(
                                "BillingDialog",
                                "launch blocked: productId/basePlanId null for ${plan.id}"
                            )
                            Toast.makeText(
                                context,
                                strProductDetailsUnavailable,
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        if (offerToken == null) {
                            android.util.Log.w(
                                "BillingDiag",
                                "launch blocked offerToken null plan=${plan.id} basePlanId=$basePlanId expected Active=${plan.id != "plus" || basePlanId != "plus"} if PERSONAL missing check Play Console Active/country/price/eligibility and unfetchedProductList"
                            )
                            android.util.Log.w(
                                "BillingDialog",
                                "launch blocked: offerToken null for ${plan.id} $basePlanId (Draft?)"
                            )
                            Toast.makeText(
                                context,
                                strOfferUnavailable,
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        if (activity == null || activity.isFinishing || activity.isDestroyed) {
                            android.util.Log.w(
                                "BillingDiag",
                                "launch blocked activity invalid plan=${plan.id}"
                            )
                            android.util.Log.w("BillingDialog", "launch blocked: activity invalid")
                            Toast.makeText(
                                context,
                                strActivityUnavailable,
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        android.util.Log.d(
                            "BillingDiag",
                            "launchBillingFlow start plan=${plan.id} productId=$productId basePlanId=$basePlanId hasOfferToken=true"
                        )
                        android.util.Log.d(
                            "BillingDialog",
                            "launchBillingFlow start plan=${plan.id} productId=$productId basePlanId=$basePlanId"
                        )
                        // Double-tap guard: if already blocking, ignore
                        if (viewModel.isPurchaseBlocking()) {
                            android.util.Log.w(
                                "BillingDiag",
                                "onClick blocked isPurchaseBlocking phase=${viewModel.uiState.value.purchaseFlow.phase}"
                            )
                            return@Button
                        }
                        viewModel.launchPurchase(activity, productId, offerToken) { code ->
                            android.util.Log.d(
                                "BillingDiag",
                                "launchBillingFlow result code=$code debugMessage for ${plan.id} productId=$productId basePlanId=$basePlanId"
                            )
                            android.util.Log.d(
                                "BillingDialog",
                                "launchBillingFlow result code=$code for ${plan.id}"
                            )
                            if (code != 0) {
                                val msg = when (code) {
                                    1 -> strUserCanceled
                                    2 -> strServiceUnavailable
                                    3 -> strBillingUnavailable
                                    5 -> strItemUnavailable
                                    7 -> strAlreadyOwned
                                    8 -> strNotOwned
                                    else -> "$strGenericError ($code)"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, strProcessing, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = buttonEnabled,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = when {
                            isCurrent -> MaterialTheme.colorScheme.surfaceContainerHigh
                            plan.id == "free" -> MaterialTheme.colorScheme.surfaceContainerHigh
                            !isAvailable -> MaterialTheme.colorScheme.surfaceContainerHigh
                            plan.id == "personal" -> MaterialTheme.colorScheme.primary
                            plan.id == "plus" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        disabledContentColor = when {
                            isCurrent -> MaterialTheme.colorScheme.onSurfaceVariant
                            plan.id == "free" -> MaterialTheme.colorScheme.onSurfaceVariant
                            !isAvailable -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onPrimary
                        },
                        containerColor = when (plan.id) {
                            "personal" -> MaterialTheme.colorScheme.primary
                            "plus" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        contentColor = when (plan.id) {
                            "personal" -> MaterialTheme.colorScheme.onPrimary
                            "plus" -> MaterialTheme.colorScheme.onTertiary
                            else -> MaterialTheme.colorScheme.onPrimary
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = when {
                            isCurrent -> "✓ ${stringResource(R.string.premium_current)}"
                            !isAvailable -> stringResource(R.string.billing_unavailable)
                            plan.id == "free" -> stringResource(R.string.billing_free_plan)
                            plan.id == "personal" -> stringResource(R.string.premium_choose_personal)
                            plan.id == "plus" -> stringResource(R.string.premium_choose_plus)
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
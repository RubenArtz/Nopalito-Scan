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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.StorageUsage

data class HomeUiState(
    val userEmail: String? = null,
    val fileCount: Int = 0,
    val storageUsed: Long = 0L,
    /** Server-authoritative storage usage (plan + quota + used bytes). */
    val storageUsage: StorageUsage? = null,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val isLoggedOut: Boolean = false,
    val lastSyncTime: String? = null
)

class CloudHomeViewModel(
    private val repository: CloudRepository,
    private val application: Application? = null
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        loadHome()
        observeGlobalEntitlement()
        // Legacy bus kept as signal only — no network, just read flow (no loop)
        viewModelScope.launch {
            try {
                nopalito.app.billing.BillingSyncBus.events.collect {
                    // Flow already updates; no direct repository call to avoid cycle
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun observeGlobalEntitlement() {
        val app = application ?: return
        viewModelScope.launch {
            try {
                val ctx = try {
                    app.applicationContext ?: app
                } catch (_: Exception) {
                    app
                }
                val mgr = nopalito.app.billing.BillingEntitlementManager.getInstance(ctx)
                    ?: return@launch
                mgr.entitlementFlow.collect { ent ->
                    val usage = StorageUsage(
                        plan = ent.plan,
                        limitBytes = ent.storageLimitBytes,
                        usedBytes = ent.storageUsedBytes ?: 0L,
                        freeBytes = ent.storageAvailableBytes
                            ?: (ent.storageLimitBytes - (ent.storageUsedBytes ?: 0L)),
                        usedPercent = if (ent.storageLimitBytes > 0 && ent.storageUsedBytes != null) ((ent.storageUsedBytes * 100 / ent.storageLimitBytes).toInt()
                            .coerceIn(0, 100)) else 0,
                        isPremium = ent.plan != "FREE"
                    )
                    _state.value = _state.value.copy(storageUsage = usage, isLoading = false)
                }
            } catch (_: Exception) {
                // Fallback: direct refresh if manager not available (tests)
                refreshStorageUsage()
            }
        }
    }

    fun loadHome() {
        viewModelScope.launch {
            val email = try {
                repository.getCurrentUserEmail()
            } catch (_: Exception) {
                null
            }
            _state.value = _state.value.copy(
                isLoading = false,
                errorMessage = null,
                userEmail = email
            )
        }
    }

    /**
     * Refreshes the usage — delegates to global manager when available.
     */
    fun refreshStorageUsage() {
        val app = application
        val mgr = try {
            val ctx = try {
                app?.applicationContext ?: app
            } catch (_: Exception) {
                app
            }
            if (ctx != null) nopalito.app.billing.BillingEntitlementManager.getInstance(ctx) else null
        } catch (_: Exception) {
            null
        }
        if (mgr != null) {
            mgr.refresh(force = true, reason = nopalito.app.billing.BillingRefreshReason.MANUAL)
            return
        }
        viewModelScope.launch {
            repository.getStorageUsage().fold(
                onSuccess = { usage -> _state.value = _state.value.copy(storageUsage = usage) },
                onFailure = { _state.value = _state.value.copy(storageUsage = null) }
            )
        }
    }
}
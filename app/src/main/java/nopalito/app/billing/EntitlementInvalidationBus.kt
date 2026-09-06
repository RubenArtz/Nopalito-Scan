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

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class EntitlementInvalidationEvent(
    val code: String,
    val path: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Non-blocking event bus for semantic 403 codes.
 * AuthInterceptor emits without blocking OkHttp thread; manager collects with debounce.
 */
object EntitlementInvalidationBus {
    private val _events = MutableSharedFlow<EntitlementInvalidationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EntitlementInvalidationEvent> = _events

    fun tryEmit(event: EntitlementInvalidationEvent) {
        _events.tryEmit(event)
    }

    fun notifyIfRelevant(code: String?, path: String?) {
        if (code == null || path == null) return
        val normalized = path.lowercase()
        // Exclude billing/auth/admin endpoints to avoid recursion
        val excluded = listOf(
            "/api/billing/status",
            "/api/billing/google/verify",
            "/api/storage/usage",
            "/api/auth/refresh",
            "/api/admin"
        ).any { normalized.contains(it) }
        if (excluded) return
        val allowed = setOf(
            "PLAN_REQUIRED",
            "SUBSCRIPTION_REQUIRED",
            "STORAGE_LIMIT_REACHED",
            "ENTITLEMENT_OUTDATED"
        )
        if (code !in allowed) return
        tryEmit(EntitlementInvalidationEvent(code = code, path = path))
    }
}
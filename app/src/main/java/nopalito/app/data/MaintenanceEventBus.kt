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

package nopalito.app.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One applied maintenance state change, published by the FCM service after
 * [MaintenanceStateStore.updateFromFcm] accepted it.
 *
 * [type] is the lifecycle event string from the payload (created /
 * pre_notification / activated / completed / cancelled); [version] is the
 * server-side record version that was applied.
 */
data class MaintenanceEvent(
    val type: String,
    val version: Long,
    val source: String,
    val maintenanceId: String? = null,
    /** End-to-end trace id from the FCM payload, when the sender provided one. */
    val correlationId: String? = null,
)

/**
 * Decoupled notification channel for applied maintenance changes. The FCM
 * service publishes; any collector (ViewModels, tooling) subscribes without
 * a direct dependency on the messaging service.
 *
 * Replay is intentionally 0: collectors react to changes from now on, and
 * the authoritative current value always lives in
 * [MaintenanceStateStore.state]. Drops under pressure are acceptable by
 * design (the store remains the source of truth).
 */
object MaintenanceEventBus {

    private val _events = MutableSharedFlow<MaintenanceEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<MaintenanceEvent> = _events.asSharedFlow()

    /** Non-suspending publish; safe from any coroutine context. */
    fun tryEmit(event: MaintenanceEvent): Boolean = _events.tryEmit(event)
}

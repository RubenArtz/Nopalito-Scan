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

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 — event bus contract (pure JVM, coroutines-test). */
class MaintenanceEventBusTest {

    @Test
    fun `emitted events reach collectors with their correlation id`() = runTest {
        val received = mutableListOf<MaintenanceEvent>()
        // UNDISPATCHED: the collector subscribes BEFORE we emit (SharedFlow
        // has no replay; a lazily-started collector would miss the value).
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            MaintenanceEventBus.events.collect { received.add(it) }
        }

        val sent = MaintenanceEventBus.tryEmit(
            MaintenanceEvent(
                type = "activated",
                version = 7,
                source = LocalMaintenanceState.SOURCE_FCM,
                maintenanceId = "m-1",
                correlationId = "corr-42",
            )
        )
        // extraBufferCapacity=8 with one live subscriber: never dropped.
        assertTrue(sent)

        // Let the suspended collector resume and observe the value.
        testScheduler.advanceUntilIdle()
        assertEquals(1, received.size)
        val event = received.single()
        assertEquals("activated", event.type)
        assertEquals(7L, event.version)
        assertEquals("corr-42", event.correlationId)

        job.cancel()
    }
}

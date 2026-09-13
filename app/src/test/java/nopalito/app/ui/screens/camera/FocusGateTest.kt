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

package nopalito.app.ui.screens.camera

import android.hardware.camera2.CameraMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test

class FocusGateTest {
    private val controller = CameraCaptureController()

    @After
    fun tearDown() {
        controller.shutdown()
    }

    @Test
    fun `null AF state means unavailable and never blocks`() {
        controller.lastAfState = null
        val (confirmed, status) = controller.focusGate()
        assertThat(confirmed).isTrue()
        assertThat(status).isEqualTo("focus state unavailable")
    }

    @Test
    fun `focused states confirm`() {
        controller.lastAfState = CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED
        assertThat(controller.focusGate().first).isTrue()
        controller.lastAfState = CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED
        assertThat(controller.focusGate().first).isTrue()
    }

    @Test
    fun `scanning and unfocused states hold auto-capture`() {
        for (af in listOf(
            CameraMetadata.CONTROL_AF_STATE_INACTIVE,
            CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN,
            CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN,
            CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
            CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED,
        )) {
            controller.lastAfState = af
            assertThat(controller.focusGate().first).isFalse()
        }
    }

    @Test
    fun `state names never invent values`() {
        assertThat(controller.afStateName(99)).isEqualTo("UNKNOWN(99)")
    }
}

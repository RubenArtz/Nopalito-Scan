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
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import nopalito.app.R
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.security.BiometricUnlockOutcome
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CloudStorageViewModelTest {

    private val repository = mockk<CloudRepository>()
    private val application = mockk<Application>()

    @Before
    fun stubStrings() {
        every { application.getString(any()) } answers { "str:" + firstArg<Int>() }
        every { application.getString(any(), *anyVararg()) } answers { "str:" + firstArg<Int>() }
        val resources = mockk<Resources>()
        every { resources.getString(any()) } answers { "str:" + firstArg<Int>() }
        every { resources.getString(any(), *anyVararg()) } answers { "str:" + firstArg<Int>() }
        val localizedContext = mockk<Context>()
        every { localizedContext.resources } returns resources
        every { application.resources } returns mockk {
            every { configuration } returns Configuration()
        }
        every { application.createConfigurationContext(any()) } returns localizedContext
    }

    private fun newViewModel(biometricMode: Boolean = false): CloudStorageViewModel {
        every { repository.isBiometricMode() } returns biometricMode
        return CloudStorageViewModel(repository, application)
    }

    private fun captureToggleResult(): (BiometricUnlockOutcome) -> Unit {
        val callback = slot<(BiometricUnlockOutcome) -> Unit>()
        every { repository.setBiometricEnabled(any(), capture(callback)) } returns Unit
        return { outcome -> callback.captured.invoke(outcome) }
    }

    @Test
    fun `toggle reflects the repository mode at startup`() {
        val vm = newViewModel(biometricMode = true)
        assertTrue(vm.state.value.biometricEnabled)

        val vmOff = newViewModel(biometricMode = false)
        assertFalse(vmOff.state.value.biometricEnabled)
    }

    @Test
    fun `enabling runs the repository migration and flips the switch on success`() {
        val vm = newViewModel()
        val onResult = captureToggleResult()

        vm.toggleBiometric()
        verify { repository.setBiometricEnabled(true, any()) }
        assertTrue(vm.state.value.biometricBusy)

        onResult(BiometricUnlockOutcome.Enabled)

        assertTrue(vm.state.value.biometricEnabled)
        assertFalse(vm.state.value.biometricBusy)
        assertNull(vm.state.value.biometricMessage)
    }

    @Test
    fun `disabling flips the switch off on success`() {
        val vm = newViewModel(biometricMode = true)
        val onResult = captureToggleResult()

        vm.toggleBiometric()
        verify { repository.setBiometricEnabled(false, any()) }

        onResult(BiometricUnlockOutcome.Disabled)

        assertFalse(vm.state.value.biometricEnabled)
        assertFalse(vm.state.value.biometricBusy)
        assertNull(vm.state.value.biometricMessage)
    }

    @Test
    fun `cancel keeps the switch state and clears the busy flag`() {
        val vm = newViewModel()
        val onResult = captureToggleResult()

        vm.toggleBiometric()
        onResult(BiometricUnlockOutcome.Cancelled)

        assertFalse(vm.state.value.biometricEnabled)
        assertFalse(vm.state.value.biometricBusy)
        assertNull(vm.state.value.biometricMessage)
    }

    @Test
    fun `not available shows the localized unavailable message`() {
        val vm = newViewModel()
        val onResult = captureToggleResult()

        vm.toggleBiometric()
        onResult(BiometricUnlockOutcome.NotAvailable)

        assertFalse(vm.state.value.biometricEnabled)
        assertFalse(vm.state.value.biometricBusy)
        assertEquals("str:" + R.string.cloud_biometric_unlock_unavailable, vm.state.value.biometricMessage)
    }

    @Test
    fun `locked out shows the localized lockout message`() {
        val vm = newViewModel()
        val onResult = captureToggleResult()

        vm.toggleBiometric()
        onResult(BiometricUnlockOutcome.LockedOut)

        assertFalse(vm.state.value.biometricEnabled)
        assertFalse(vm.state.value.biometricBusy)
        assertEquals("str:" + R.string.cloud_biometric_unlock_locked_out, vm.state.value.biometricMessage)
    }

    @Test
    fun `failed shows the localized toggle failure message`() {
        val vm = newViewModel()
        val onResult = captureToggleResult()

        vm.toggleBiometric()
        onResult(BiometricUnlockOutcome.Failed)

        assertFalse(vm.state.value.biometricEnabled)
        assertFalse(vm.state.value.biometricBusy)
        assertEquals("str:" + R.string.cloud_biometric_toggle_failed, vm.state.value.biometricMessage)
    }

    @Test
    fun `a second toggle while busy is ignored`() {
        val vm = newViewModel()
        every { repository.setBiometricEnabled(any(), any()) } returns Unit

        vm.toggleBiometric()
        vm.toggleBiometric()

        verify(exactly = 1) { repository.setBiometricEnabled(true, any()) }
    }
}
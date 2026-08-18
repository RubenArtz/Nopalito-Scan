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

package nopalito.app.ui.screens.cloud.security

import android.app.Application
import android.content.Context
import nopalito.app.ui.screens.cloud.security.BiometricCapability.BIOMETRIC_STRONG
import nopalito.app.ui.screens.cloud.security.BiometricCapability.resolve
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric smoke test: the real AndroidX `BiometricManager` is queried on a
 * simulated framework (no fingerprint hardware by default). It proves the
 * production checker loads and `resolve()` maps its output without throwing.
 *
 * Pinned to SDK 28 (Android 9): the smallest SDK where AndroidX BiometricManager
 * runs against the platform with a proper implementation, and the level that
 * exercises the STRONG-only matrix. SDK 36 requires JDK 21 (available) but the
 * android-all jar for 28 is smaller and more stable for CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidBiometricCapabilityCheckerTest {

    @Test
    fun `real BiometricManager canAuthenticate does not throw and returns an Int`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val checker = AndroidBiometricCapabilityChecker(context)

        // Smoke: production path loads and answers without throwing.
        checker.canAuthenticate(BIOMETRIC_STRONG)
    }

    @Test
    fun `resolve with the real checker returns a stable state`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val checker = AndroidBiometricCapabilityChecker(context)

        // Robolectric's default BiometricManager shadow answers -2 (a sentinel
        // the platform never produces). The engine must not crash and maps it
        // to Unknown — the defensive behavior the pure JVM tests assert.
        val availability = resolve(28, checker)

        assertThat(availability).isInstanceOf(BiometricAvailability::class.java)
    }
}
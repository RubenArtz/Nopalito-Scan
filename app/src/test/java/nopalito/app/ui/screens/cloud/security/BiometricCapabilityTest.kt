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

import nopalito.app.ui.screens.cloud.security.BiometricAvailability.Available
import nopalito.app.ui.screens.cloud.security.BiometricAvailability.NoHardware
import nopalito.app.ui.screens.cloud.security.BiometricAvailability.NotEnrolled
import nopalito.app.ui.screens.cloud.security.BiometricAvailability.Unavailable
import nopalito.app.ui.screens.cloud.security.BiometricAvailability.Unknown
import nopalito.app.ui.screens.cloud.security.BiometricCapability.BIOMETRIC_STRONG
import nopalito.app.ui.screens.cloud.security.BiometricCapability.BIOMETRIC_WEAK
import nopalito.app.ui.screens.cloud.security.BiometricCapability.DEVICE_CREDENTIAL
import nopalito.app.ui.screens.cloud.security.BiometricCapability.RESULT_ERROR_HW_UNAVAILABLE
import nopalito.app.ui.screens.cloud.security.BiometricCapability.RESULT_ERROR_NONE_ENROLLED
import nopalito.app.ui.screens.cloud.security.BiometricCapability.RESULT_ERROR_NO_HARDWARE
import nopalito.app.ui.screens.cloud.security.BiometricCapability.RESULT_ERROR_SECURITY_UPDATE_REQUIRED
import nopalito.app.ui.screens.cloud.security.BiometricCapability.RESULT_ERROR_UNSUPPORTED
import nopalito.app.ui.screens.cloud.security.BiometricCapability.RESULT_STATUS_UNKNOWN
import nopalito.app.ui.screens.cloud.security.BiometricCapability.RESULT_SUCCESS
import nopalito.app.ui.screens.cloud.security.BiometricCapability.STRONG_OR_DEVICE_CREDENTIAL
import nopalito.app.ui.screens.cloud.security.BiometricCapability.WEAK_OR_DEVICE_CREDENTIAL
import nopalito.app.ui.screens.cloud.security.BiometricCapability.authenticatorSetsFor
import nopalito.app.ui.screens.cloud.security.BiometricCapability.authenticatorSetsForWithWeakFallback
import nopalito.app.ui.screens.cloud.security.BiometricCapability.isWeakAuthenticator
import nopalito.app.ui.screens.cloud.security.BiometricCapability.mapResult
import nopalito.app.ui.screens.cloud.security.BiometricCapability.resolve
import nopalito.app.ui.screens.cloud.security.BiometricCapability.resolveWithWeakFallback
import nopalito.app.ui.screens.cloud.security.BiometricCapability.shouldOfferWeakFallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * JVM tests for the pure capability decision engine. No Android framework is
 * touched: a fake [BiometricCapabilityChecker] supplies the platform result.
 */
class BiometricCapabilityTest {

    // ── Authenticator matrix per API level ──

    @Test
    fun `api 26-29 offer biometric strong only`() {
        for (api in listOf(26, 27, 28, 29)) {
            assertThat(authenticatorSetsFor(api))
                .describedAs("API $api")
                .containsExactly(BIOMETRIC_STRONG)
        }
    }

    @Test
    fun `api 30+ offer strong or device credential with strong fallback`() {
        for (api in listOf(30, 31, 32, 33, 34, 35, 36)) {
            assertThat(authenticatorSetsFor(api))
                .describedAs("API $api")
                .containsExactly(STRONG_OR_DEVICE_CREDENTIAL, BIOMETRIC_STRONG)
        }
    }

    @Test
    fun `device credential is never advertised below api 30`() {
        for (api in listOf(26, 27, 28, 29)) {
            for (set in authenticatorSetsFor(api)) {
                assertThat(set and DEVICE_CREDENTIAL).describedAs("API $api must not offer DEVICE_CREDENTIAL")
                    .isZero()
            }
        }
    }

    @Test
    fun `strong and device credential bits are distinct and combined set keeps both`() {
        assertThat(BIOMETRIC_STRONG).isNotEqualTo(DEVICE_CREDENTIAL)
        assertThat(STRONG_OR_DEVICE_CREDENTIAL and BIOMETRIC_STRONG).isNotZero()
        assertThat(STRONG_OR_DEVICE_CREDENTIAL and DEVICE_CREDENTIAL).isNotZero()
    }

    // ── resolve(): availability ──

    @Test
    fun `resolve succeeds with the permissive set on api 30 when both are supported`() {
        val availability = resolve(30, FakeChecker { RESULT_SUCCESS })
        assertThat(availability).isEqualTo(Available(STRONG_OR_DEVICE_CREDENTIAL))
    }

    @Test
    fun `resolve falls back to strong on api 30 when device credential is unsupported`() {
        val availability = resolve(30, FakeChecker { auth ->
            when (auth) {
                STRONG_OR_DEVICE_CREDENTIAL -> RESULT_ERROR_UNSUPPORTED
                else -> RESULT_SUCCESS
            }
        })
        assertThat(availability).isEqualTo(Available(BIOMETRIC_STRONG))
    }

    @Test
    fun `resolve succeeds with strong on api 28`() {
        val availability = resolve(28, FakeChecker { RESULT_SUCCESS })
        assertThat(availability).isEqualTo(Available(BIOMETRIC_STRONG))
    }

    @Test
    fun `resolve reports no hardware`() {
        assertThat(resolve(28, FakeChecker { RESULT_ERROR_NO_HARDWARE })).isEqualTo(NoHardware)
    }

    @Test
    fun `resolve reports not enrolled`() {
        assertThat(resolve(28, FakeChecker { RESULT_ERROR_NONE_ENROLLED })).isEqualTo(NotEnrolled)
    }

    @Test
    fun `resolve reports hardware unavailable`() {
        assertThat(resolve(28, FakeChecker { RESULT_ERROR_HW_UNAVAILABLE })).isEqualTo(Unavailable)
    }

    @Test
    fun `resolve reports security update required as unavailable`() {
        assertThat(resolve(28, FakeChecker { RESULT_ERROR_SECURITY_UPDATE_REQUIRED })).isEqualTo(
            Unavailable
        )
    }

    @Test
    fun `resolve reports unexpected status as unknown`() {
        assertThat(resolve(28, FakeChecker { 999 })).isEqualTo(Unknown)
    }

    @Test
    fun `resolve falls through an IllegalArgumentException on the permissive set`() {
        val availability = resolve(30, FakeChecker { auth ->
            if (auth == STRONG_OR_DEVICE_CREDENTIAL) throw IllegalArgumentException("not supported")
            RESULT_SUCCESS
        })
        assertThat(availability).isEqualTo(Available(BIOMETRIC_STRONG))
    }

    @Test
    fun `resolve maps an all-throwing checker to unavailable`() {
        assertThat(resolve(30, FakeChecker { throw IllegalArgumentException("nope") })).isEqualTo(
            Unavailable
        )
    }

    @Test
    fun `resolve on api 28 ignores the device-credential-only happy path`() {
        // A device credential alone must never be advertised below API 30.
        val availability = resolve(28, FakeChecker { RESULT_SUCCESS })
        assertThat(availability).isEqualTo(Available(BIOMETRIC_STRONG))
    }

    // ── mapResult(): single code mapping ──

    @Test
    fun `mapResult covers every supported result code`() {
        assertThat(mapResult(RESULT_SUCCESS)).isEqualTo(Available(BIOMETRIC_STRONG))
        assertThat(mapResult(RESULT_ERROR_NONE_ENROLLED)).isEqualTo(NotEnrolled)
        assertThat(mapResult(RESULT_ERROR_NO_HARDWARE)).isEqualTo(NoHardware)
        assertThat(mapResult(RESULT_ERROR_HW_UNAVAILABLE)).isEqualTo(Unavailable)
        assertThat(mapResult(RESULT_ERROR_SECURITY_UPDATE_REQUIRED)).isEqualTo(Unavailable)
        assertThat(mapResult(RESULT_ERROR_UNSUPPORTED)).isEqualTo(Unavailable)
        assertThat(mapResult(RESULT_STATUS_UNKNOWN)).isEqualTo(Unknown)
    }

    // ── WEAK fallback (new) ──

    @Test
    fun `api 26-29 with weak fallback offer strong then weak`() {
        for (api in listOf(26, 27, 28, 29)) {
            assertThat(authenticatorSetsForWithWeakFallback(api))
                .describedAs("API $api with weak")
                .containsExactly(BIOMETRIC_STRONG, BIOMETRIC_WEAK)
        }
    }

    @Test
    fun `api 30+ with weak fallback offer 4 sets strong first`() {
        for (api in listOf(30, 31, 32, 33, 34, 35, 36)) {
            assertThat(authenticatorSetsForWithWeakFallback(api))
                .describedAs("API $api with weak")
                .containsExactly(
                    STRONG_OR_DEVICE_CREDENTIAL, BIOMETRIC_STRONG,
                    WEAK_OR_DEVICE_CREDENTIAL, BIOMETRIC_WEAK
                )
        }
    }

    @Test
    fun `default sets never offer weak`() {
        for (api in listOf(26, 28, 30, 33)) {
            assertThat(authenticatorSetsFor(api)).doesNotContain(BIOMETRIC_WEAK)
            assertThat(authenticatorSetsFor(api)).doesNotContain(WEAK_OR_DEVICE_CREDENTIAL)
        }
    }

    @Test
    fun `shouldOfferWeakFallback true when strong not available but weak is`() {
        val checker = FakeChecker { auth ->
            when (auth) {
                BIOMETRIC_STRONG -> RESULT_ERROR_NONE_ENROLLED
                BIOMETRIC_WEAK -> RESULT_SUCCESS
                else -> RESULT_ERROR_NONE_ENROLLED
            }
        }
        assertThat(shouldOfferWeakFallback(checker)).isTrue()
    }

    @Test
    fun `shouldOfferWeakFallback false when strong available`() {
        val checker = FakeChecker { RESULT_SUCCESS } // both succeed via strong
        assertThat(shouldOfferWeakFallback(checker)).isFalse()
    }

    @Test
    fun `shouldOfferWeakFallback false when neither available`() {
        val checker = FakeChecker { RESULT_ERROR_NO_HARDWARE }
        assertThat(shouldOfferWeakFallback(checker)).isFalse()
    }

    @Test
    fun `resolveWithWeakFallback prefers strong when both available`() {
        val availability = resolveWithWeakFallback(30, FakeChecker { RESULT_SUCCESS })
        assertThat(availability).isEqualTo(Available(STRONG_OR_DEVICE_CREDENTIAL))
    }

    @Test
    fun `resolveWithWeakFallback falls back to weak when strong not enrolled`() {
        val availability = resolveWithWeakFallback(30, FakeChecker { auth ->
            when (auth) {
                STRONG_OR_DEVICE_CREDENTIAL -> RESULT_ERROR_NONE_ENROLLED
                BIOMETRIC_STRONG -> RESULT_ERROR_NONE_ENROLLED
                WEAK_OR_DEVICE_CREDENTIAL -> RESULT_SUCCESS
                BIOMETRIC_WEAK -> RESULT_SUCCESS
                else -> RESULT_ERROR_NONE_ENROLLED
            }
        })
        assertThat(availability).isEqualTo(Available(WEAK_OR_DEVICE_CREDENTIAL))
    }

    @Test
    fun `resolveWithWeakFallback falls back to weak alone on api 28`() {
        val availability = resolveWithWeakFallback(28, FakeChecker { auth ->
            when (auth) {
                BIOMETRIC_STRONG -> RESULT_ERROR_NONE_ENROLLED
                BIOMETRIC_WEAK -> RESULT_SUCCESS
                else -> RESULT_ERROR_NONE_ENROLLED
            }
        })
        assertThat(availability).isEqualTo(Available(BIOMETRIC_WEAK))
    }

    @Test
    fun `resolve default does not fall back to weak`() {
        val availability = resolve(30, FakeChecker { auth ->
            when (auth) {
                STRONG_OR_DEVICE_CREDENTIAL -> RESULT_ERROR_NONE_ENROLLED
                BIOMETRIC_STRONG -> RESULT_ERROR_NONE_ENROLLED
                else -> RESULT_SUCCESS // would be weak, but resolve should not try it
            }
        })
        assertThat(availability).isNotEqualTo(Available(BIOMETRIC_WEAK))
        assertThat(availability).isNotEqualTo(Available(WEAK_OR_DEVICE_CREDENTIAL))
    }

    @Test
    fun `isWeakAuthenticator correctly identifies weak sets`() {
        assertThat(isWeakAuthenticator(BIOMETRIC_WEAK)).isTrue()
        assertThat(isWeakAuthenticator(WEAK_OR_DEVICE_CREDENTIAL)).isTrue()
        assertThat(isWeakAuthenticator(BIOMETRIC_STRONG)).isFalse()
        assertThat(isWeakAuthenticator(STRONG_OR_DEVICE_CREDENTIAL)).isFalse()
    }

    private class FakeChecker(private val resultFor: (authenticators: Int) -> Int) :
        BiometricCapabilityChecker {
        override fun canAuthenticate(authenticators: Int): Int = resultFor(authenticators)
    }
}
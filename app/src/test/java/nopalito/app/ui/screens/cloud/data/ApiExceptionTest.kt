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

package nopalito.app.ui.screens.cloud.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-time and behavioral compatibility of ApiException.
 *
 * Existing call sites use the 2-argument constructor `(code, message)` and
 * read `code`, `message`, `isQuotaExceeded()` and `isRestoreQuotaExceeded()`.
 * The structured fields (details, httpStatus, backendMessage) are additive;
 * the exception `message` is internal and must not be treated as visible text.
 */
class ApiExceptionTest {

    @Test
    fun legacyTwoArgConstructorKeepsCodeAndMessage() {
        val e = ApiException("VALIDATION_ERROR", "legacy message")
        assertEquals("VALIDATION_ERROR", e.code)
        assertEquals("legacy message", e.message)
        assertNull(e.details)
        assertEquals(0, e.httpStatus)
        assertNull(e.backendMessage)
    }

    @Test
    fun legacyTwoArgConstructorAcceptsNullCode() {
        val e = ApiException(null, "raw server text")
        assertNull(e.code)
        assertEquals("raw server text", e.message)
    }

    @Test
    fun structuredConstructorPreservesEveryField() {
        val details = mapOf<String, Any>("max" to 100)
        val e = ApiException("FILE_TOO_LARGE", details, 413, "too big", "internal note")
        assertEquals("FILE_TOO_LARGE", e.code)
        assertEquals(details, e.details)
        assertEquals(413, e.httpStatus)
        assertEquals("too big", e.backendMessage)
        assertEquals("internal note", e.message)
    }

    @Test
    fun messageDefaultsToGenericTextWhenNull() {
        val e = ApiException("X", null, 500, null, null)
        assertEquals("API request failed", e.message)
    }

    @Test
    fun quotaHelpersMatchTheirCodesOnly() {
        assertTrue(ApiException(ApiException.QUOTA_EXCEEDED, "q").isQuotaExceeded())
        assertFalse(ApiException("OTHER", "x").isQuotaExceeded())
        assertTrue(
            ApiException(
                ApiException.QUOTA_EXCEEDED_ON_RESTORE,
                "q"
            ).isRestoreQuotaExceeded()
        )
        assertFalse(ApiException(ApiException.QUOTA_EXCEEDED, "q").isRestoreQuotaExceeded())
    }

    @Test
    fun quotaHelpersAreFalseForLegacyErrorsWithoutCode() {
        val legacy = ApiException(null, "no code")
        assertFalse(legacy.isQuotaExceeded())
        assertFalse(legacy.isRestoreQuotaExceeded())
    }

    @Test
    fun errorCodesConstantsRemainStable() {
        assertEquals("QUOTA_EXCEEDED", ApiException.QUOTA_EXCEEDED)
        assertEquals("QUOTA_EXCEEDED_ON_RESTORE", ApiException.QUOTA_EXCEEDED_ON_RESTORE)
        assertEquals("EMAIL_ALREADY_REGISTERED", ApiException.EMAIL_ALREADY_REGISTERED)
        assertEquals("ACCOUNT_NOT_FOUND", ApiException.ACCOUNT_NOT_FOUND)
        assertEquals("INVALID_APP_SECRET", ApiException.INVALID_APP_SECRET)
        assertEquals("AUTH_ACCOUNT_SUSPENDED", ApiException.AUTH_ACCOUNT_SUSPENDED)
        assertEquals("AUTH_ACCOUNT_STATUS_UNKNOWN", ApiException.AUTH_ACCOUNT_STATUS_UNKNOWN)
        assertEquals("AUTH_REGISTER_IP_LIMIT_REACHED", ApiException.AUTH_REGISTER_IP_LIMIT_REACHED)
        assertEquals("AUTH_REGISTER_VPN_NOT_ALLOWED", ApiException.AUTH_REGISTER_VPN_NOT_ALLOWED)
        assertEquals(
            "AUTH_PASSWORD_RESET_BLOCKED_SUSPENDED",
            ApiException.AUTH_PASSWORD_RESET_BLOCKED_SUSPENDED
        )
        assertEquals("ACCOUNT_ALREADY_DELETED", ApiException.ACCOUNT_ALREADY_DELETED)
        assertEquals("ANONYMOUS_USER_PROTECTED", ApiException.ANONYMOUS_USER_PROTECTED)
    }

    @Test
    fun accountSuspendedHelperMatchesSuspensionCodesOnly() {
        assertTrue(ApiException(ApiException.AUTH_ACCOUNT_SUSPENDED, "x").isAccountSuspended())
        assertTrue(
            ApiException(
                ApiException.AUTH_PASSWORD_RESET_BLOCKED_SUSPENDED,
                "x"
            ).isAccountSuspended()
        )
        assertFalse(
            ApiException(
                ApiException.AUTH_ACCOUNT_STATUS_UNKNOWN,
                "x"
            ).isAccountSuspended()
        )
        assertFalse(ApiException("OTHER", "x").isAccountSuspended())
        assertFalse(ApiException(null, "no code").isAccountSuspended())
    }

    @Test
    fun registrationBlockedHelperMatchesIpPolicyCodesOnly() {
        assertTrue(
            ApiException(ApiException.AUTH_REGISTER_IP_LIMIT_REACHED, "x").isRegistrationBlocked()
        )
        assertTrue(
            ApiException(
                ApiException.AUTH_REGISTER_VPN_NOT_ALLOWED,
                "x"
            ).isRegistrationBlocked()
        )
        assertFalse(ApiException(ApiException.AUTH_ACCOUNT_SUSPENDED, "x").isRegistrationBlocked())
        assertFalse(ApiException(null, "no code").isRegistrationBlocked())
    }

    @Test
    fun messageIsNotTheVisibleTextByContract() {
        // backendMessage must stay separate from the exception message so the
        // UI layer can decide (sanitized, last-resort) whether to show it.
        val e = ApiException("QUOTA_EXCEEDED", null, 400, "raw backend text", null)
        assertEquals("raw backend text", e.backendMessage)
        assertEquals("API request failed", e.message)
    }
}
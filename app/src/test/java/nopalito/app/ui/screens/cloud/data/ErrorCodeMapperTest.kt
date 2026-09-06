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

import nopalito.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Block: ErrorCodeMapper — resolution order (specific -> group -> status ->
 * error_unknown) and safe backend `{placeholder}` substitution.
 *
 * Pure (no Context), so it runs on the JVM: `@StringRes` ids are compared as
 * ints against the generated R class. `localizedMessage` (the only Context
 * dependent entry point) is covered elsewhere via the ViewModel/UI layer.
 */
class ErrorCodeMapperTest {

    // ---- Specific codes ----

    @Test
    fun specificCodeMapsToItsDedicatedResource() {
        assertEquals(
            ErrorCodeMapper.resolveResId("QUOTA_EXCEEDED", 400),
            R.string.cloud_error_quota_exceeded
        )
        assertEquals(
            ErrorCodeMapper.resolveResId("QUOTA_EXCEEDED_ON_RESTORE", 400),
            R.string.cloud_error_restore_quota
        )
    }

    // ---- Account security codes ----

    @Test
    fun accountSecurityCodesMapToTheirDedicatedResources() {
        assertEquals(
            ErrorCodeMapper.resolveResId("AUTH_ACCOUNT_SUSPENDED", 403),
            R.string.cloud_error_auth_account_suspended
        )
        assertEquals(
            ErrorCodeMapper.resolveResId("AUTH_PASSWORD_RESET_BLOCKED_SUSPENDED", 403),
            R.string.cloud_error_auth_password_reset_blocked
        )
        assertEquals(
            ErrorCodeMapper.resolveResId("AUTH_ACCOUNT_STATUS_UNKNOWN", 503),
            R.string.cloud_error_auth_account_status_unknown
        )
        assertEquals(
            ErrorCodeMapper.resolveResId("AUTH_REGISTER_IP_LIMIT_REACHED", 429),
            R.string.cloud_error_auth_register_ip_limit
        )
        assertEquals(
            ErrorCodeMapper.resolveResId("AUTH_REGISTER_VPN_NOT_ALLOWED", 403),
            R.string.cloud_error_auth_register_vpn
        )
        assertEquals(
            ErrorCodeMapper.resolveResId("ACCOUNT_ALREADY_DELETED", 409),
            R.string.cloud_error_account_already_deleted
        )
        assertEquals(
            ErrorCodeMapper.resolveResId("ANONYMOUS_USER_PROTECTED", 403),
            R.string.cloud_error_anonymous_protected
        )
    }

    @Test
    fun authGroupMapsUnknownAuthCodesToGenericSignInError() {
        assertEquals(
            ErrorCodeMapper.resolveResId("AUTH_UNEXPECTED_CODE", 500),
            R.string.cloud_error_auth
        )
        assertEquals(
            ErrorCodeMapper.resolveResId("AUTH_SOMETHING_ELSE", 503),
            R.string.cloud_error_auth
        )
    }

    @Test
    fun authSpecificCodeBeatsAuthGroup() {
        // AUTH_ACCOUNT_SUSPENDED is both a specific code and an AUTH_ group.
        assertEquals(
            ErrorCodeMapper.resolveResId("AUTH_ACCOUNT_SUSPENDED", 500),
            R.string.cloud_error_auth_account_suspended
        )
    }

    // ---- Groups ----

    @Test
    fun rateLimitGroupMapsToTooManyRequests() {
        assertEquals(
            ErrorCodeMapper.resolveResId("RATE_LIMIT_EXCEEDED", 500),
            R.string.cloud_error_rate_limit
        )
    }

    @Test
    fun migrationGroupMapsToBadRequest() {
        assertEquals(
            ErrorCodeMapper.resolveResId("INVALID_MIGRATION_ID", 404),
            R.string.cloud_error_migration
        )
        assertEquals(
            ErrorCodeMapper.resolveResId("MIGRATION_STALLED", 500),
            R.string.cloud_error_migration
        )
    }

    @Test
    fun maintenanceGroupMapsToServiceUnavailable() {
        assertEquals(
            ErrorCodeMapper.resolveResId("MAINTENANCE_ACTIVE", 400),
            R.string.cloud_error_maintenance_active
        )
    }

    @Test
    fun qrGroupMapsToBadRequest() {
        assertEquals(
            ErrorCodeMapper.resolveResId("QR_DATA_TOO_LARGE", 500),
            R.string.cloud_error_qr
        )
    }

    @Test
    fun jobGroupMapsToNotFound() {
        assertEquals(ErrorCodeMapper.resolveResId("JOB_NOT_FOUND", 500), R.string.cloud_error_job)
    }

    // ---- Unknown / empty code ----

    @Test
    fun unknownCodeFallsBackToStatus() {
        assertEquals(ErrorCodeMapper.resolveResId("FOO_BAR", 400), R.string.cloud_error_400)
        assertEquals(ErrorCodeMapper.resolveResId("SOMETHING_ELSE", 500), R.string.cloud_error_500)
    }

    @Test
    fun emptyCodeFallsBackToStatus() {
        assertEquals(ErrorCodeMapper.resolveResId("", 404), R.string.cloud_error_404)
        assertEquals(ErrorCodeMapper.resolveResId(null, 401), R.string.cloud_error_401)
        assertEquals(ErrorCodeMapper.resolveResId("   ", 429), R.string.cloud_error_429)
    }

    // ---- Status ----

    @Test
    fun knownStatusMapsToCloudErrorResource() {
        assertEquals(ErrorCodeMapper.resolveResId(null, 400), R.string.cloud_error_400)
        assertEquals(ErrorCodeMapper.resolveResId(null, 401), R.string.cloud_error_401)
        assertEquals(ErrorCodeMapper.resolveResId(null, 403), R.string.cloud_error_403)
        assertEquals(ErrorCodeMapper.resolveResId(null, 404), R.string.cloud_error_404)
        assertEquals(ErrorCodeMapper.resolveResId(null, 413), R.string.cloud_error_413)
        assertEquals(ErrorCodeMapper.resolveResId(null, 415), R.string.cloud_error_415)
        assertEquals(ErrorCodeMapper.resolveResId(null, 429), R.string.cloud_error_429)
        assertEquals(ErrorCodeMapper.resolveResId(null, 500), R.string.cloud_error_500)
        assertEquals(ErrorCodeMapper.resolveResId(null, 503), R.string.cloud_error_503)
    }

    @Test
    fun unknownStatusFallsBackToErrorUnknown() {
        assertEquals(ErrorCodeMapper.resolveResId(null, 418), R.string.error_unknown)
        assertEquals(ErrorCodeMapper.resolveResId("", -1), R.string.error_unknown)
        assertEquals(ErrorCodeMapper.resolveResId(null, 0), R.string.error_unknown)
    }

    // ---- Priority ----

    @Test
    fun specificCodeBeatsGroup() {
        // QUOTA_EXCEEDED_ON_RESTORE is both a specific code and a QUOTA_ group.
        assertEquals(
            ErrorCodeMapper.resolveResId("QUOTA_EXCEEDED_ON_RESTORE", 413),
            R.string.cloud_error_restore_quota
        )
    }

    @Test
    fun groupBeatsStatus() {
        // RATE_LIMIT_* resolves by group, ignoring the (wrong) 500 status.
        assertEquals(
            ErrorCodeMapper.resolveResId("RATE_LIMIT_EXCEEDED", 500),
            R.string.cloud_error_rate_limit
        )
        // JOB_* resolves by group, ignoring status 500.
        assertEquals(
            ErrorCodeMapper.resolveResId("JOB_FILE_NOT_FOUND", 500),
            R.string.cloud_error_job
        )
    }

    @Test
    fun groupBeatsStatusWhenNoSpecificMatch() {
        assertEquals(
            ErrorCodeMapper.resolveResId("QUOTA_SOMETHING", 503),
            R.string.cloud_error_quota
        )
    }

    // ---- Placeholders: {count} / {max} ----

    @Test
    fun countAndMaxPlaceholdersAreOrderedAndApplied() {
        val details = mapOf<String, Any>("count" to 120, "max" to 100)
        val formatted = ErrorCodeMapper.format("Limit {count} of {max}", details)
        assertEquals("Limit %1\$s of %2\$s", formatted.pattern)
        assertEquals(listOf("120", "100"), formatted.args.toList())
        assertEquals("Limit 120 of 100", ErrorCodeMapper.apply(formatted.pattern, formatted.args))
    }

    @Test
    fun numericValuesAreNotAltered() {
        val details = mapOf<String, Any>("count" to 120, "max" to 100)
        val applied = ErrorCodeMapper.apply(
            ErrorCodeMapper.format("{count}/{max}", details).pattern,
            ErrorCodeMapper.format("{count}/{max}", details).args
        )
        assertEquals("120/100", applied)
    }

    // ---- Placeholders: quota fields ----

    @Test
    fun quotaPlaceholdersAreSubstituted() {
        val details = mapOf<String, Any>(
            "usedBytes" to 80.0, "quotaBytes" to 100.0,
            "restoreBytes" to 40.0, "availableBytes" to 20.0,
            "requiredBytes" to 40.0, "finalUsedBytes" to 120.0
        )
        val applied = ErrorCodeMapper.apply(
            ErrorCodeMapper.format(
                "{usedBytes}/{quotaBytes} ({availableBytes} free)",
                details
            ).pattern,
            ErrorCodeMapper.format("{usedBytes}/{quotaBytes} ({availableBytes} free)", details).args
        )
        assertEquals("80/100 (20 free)", applied)
    }

    // ---- Placeholders: {field} ----

    @Test
    fun fieldPlaceholderIsSubstituted() {
        val details = mapOf<String, Any>("field" to "email")
        val formatted = ErrorCodeMapper.format("Invalid {field}", details)
        assertEquals("Invalid %1\$s", formatted.pattern)
        assertEquals("Invalid email", ErrorCodeMapper.apply(formatted.pattern, formatted.args))
    }

    @Test
    fun waitSecondsNullableIsTolerated() {
        // Absent/null waitSeconds (Map cannot hold a null value) -> no substitution.
        val formatted = ErrorCodeMapper.format("Wait {waitSeconds}s", mapOf<String, Any>())
        assertEquals(0, formatted.args.size)
        assertEquals("Wait {waitSeconds}s", formatted.pattern)
    }

    // ---- Missing placeholder ----

    @Test
    fun missingPlaceholderIsLeftUntouched() {
        val formatted = ErrorCodeMapper.format("Generic {count} error", null)
        assertEquals(0, formatted.args.size)
        assertEquals("Generic {count} error", formatted.pattern)
        assertEquals(
            "Generic {count} error",
            ErrorCodeMapper.apply(formatted.pattern, formatted.args)
        )
    }

    @Test
    fun placeholderWithNoValueStaysVisibleOnlyWhenNoValueExists() {
        // No count value: the token is tolerated as-is (never crashes).
        val details = mapOf<String, Any>("max" to 5)
        val formatted = ErrorCodeMapper.format("{count} of {max}", details)
        assertEquals("of %1\$s", formatted.pattern.replace("{count}", "").trim())
        assertEquals(listOf("5"), formatted.args.toList())
    }

    // ---- Raw details never rendered ----

    @Test
    fun detailsAreNeverRenderedRaw() {
        // Only placeholder-derived values may appear in output; the details map
        // itself is never concatenated into the message.
        val details = mapOf<String, Any>("count" to 3, "max" to 10, "field" to "email")
        val formatted = ErrorCodeMapper.format("{count}", details)
        val output = ErrorCodeMapper.apply(formatted.pattern, formatted.args)
        assertEquals("3", output)
        assertTrue(!output.contains("email")) // field not referenced by template
        assertTrue(!output.contains("LinkedTreeMap") && !output.contains("{max="))
    }

    @Test
    fun resolveResIdNeverConsultsDetails() {
        val withDetails = ApiException("QUOTA_EXCEEDED", mapOf("max" to 100), 413, null, null)
        val withoutDetails = ApiException("QUOTA_EXCEEDED", null, 413, null, null)
        assertEquals(
            ErrorCodeMapper.resolveResId(withDetails),
            ErrorCodeMapper.resolveResId(withoutDetails)
        )
    }

    // ---- Never throws on null / unexpected ----

    @Test
    fun neverThrowsOnNullOrUnexpectedInput() {
        // Unknown code + unknown status + null details must yield a safe resource.
        assertEquals(ErrorCodeMapper.resolveResId("NOPE_NOPE", 599), R.string.error_unknown)
        assertEquals(ErrorCodeMapper.resolveResId(null, 0), R.string.error_unknown)
        // Placeholder helpers tolerate nulls and arbitrary values.
        assertNull(ErrorCodeMapper.placeholderValue(null, "count"))
        assertEquals("", ErrorCodeMapper.apply("", emptyArray()))
        assertEquals("plain", ErrorCodeMapper.apply("plain", emptyArray()))
        val unexpected = mapOf<String, Any>("count" to "not-a-number", "max" to 42.5)
        val formatted = ErrorCodeMapper.format("{count} {max}", unexpected)
        assertEquals(listOf("42"), formatted.args.toList())
    }
}
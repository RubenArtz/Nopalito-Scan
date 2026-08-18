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

import nopalito.app.ui.screens.cloud.model.ApiDetails
import nopalito.app.ui.screens.cloud.model.ErrorResponse
import org.junit.Assert.*
import org.junit.Test

/**
 * Block: ErrorParser — tolerant conversion of HTTP error bodies into
 * [ErrorResponse] / [ApiException].
 *
 * Covers the real backend shapes, tolerance (missing/null/unexpected details,
 * invalid JSON, empty body), status-based fallbacks, legacy compatibility and
 * the quota helper flags.
 */
class ErrorParserTest {

    private fun parse(statusCode: Int, raw: String?): ErrorResponse =
        ErrorParser.parse(statusCode, raw)

    private fun toException(response: ErrorResponse, fallback: String = "status-fallback"): ApiException =
        response.toApiException { fallback }

    @Test
    fun fullResponseKeepsCodeMessageAndDetails() {
        val response = parse(
            400,
            """{"success":false,"message":"Some text","error":{"code":"X","details":{"max":5}}}"""
        )
        assertEquals(400, response.statusCode)
        assertEquals("X", response.code)
        assertEquals("Some text", response.message)
        assertEquals(5L, ApiDetails.getLong(response.details, "max"))
        assertEquals("Some text", response.backendMessage)
    }

    @Test
    fun validationErrorFieldsMessagesAreObjectsWithLocalizedText() {
        val response = parse(
            400,
            """{"success":false,"message":"Invalid",
                "error":{"code":"VALIDATION_ERROR",
                  "fields":[{"field":"name","message":"El nombre es obligatorio"},
                            {"field":"email","message":"Email inválido"}]}}"""
        )
        val fields = response.fields
        assertEquals(2, fields?.size)
        assertEquals("name", fields?.get(0)?.field)
        assertEquals("El nombre es obligatorio", fields?.get(0)?.message)
        assertEquals("email", fields?.get(1)?.field)
        assertEquals("Email inválido", fields?.get(1)?.message)
    }

    @Test
    fun restoreQuotaFlatFieldsAreGatheredIntoDetails() {
        val response = parse(
            400,
            """{"error":{"code":"QUOTA_EXCEEDED_ON_RESTORE",
                "quotaBytes":100,"usedBytes":80,"restoreBytes":40,
                "availableBytes":20,"requiredBytes":40,"finalUsedBytes":120}}"""
        )
        assertEquals(ApiException.QUOTA_EXCEEDED_ON_RESTORE, response.code)
        assertEquals(100L, ApiDetails.getLong(response.details, "quotaBytes"))
        assertEquals(80L, ApiDetails.getLong(response.details, "usedBytes"))
        assertEquals(40L, ApiDetails.getLong(response.details, "restoreBytes"))
        assertEquals(20L, ApiDetails.getLong(response.details, "availableBytes"))
        assertEquals(40L, ApiDetails.getLong(response.details, "requiredBytes"))
        assertEquals(120L, ApiDetails.getLong(response.details, "finalUsedBytes"))
    }

    @Test
    fun resendCooldownWaitSecondsIsNullable() {
        val withCooldown = parse(429, """{"error":{"code":"RESEND_COOLDOWN","details":{"waitSeconds":30}}}""")
        assertEquals(30, ApiDetails.getInt(withCooldown.details, "waitSeconds"))

        val absent = parse(429, """{"error":{"code":"RESEND_COOLDOWN"}}""")
        assertNull(ApiDetails.getInt(absent.details, "waitSeconds"))

        val nullCooldown = parse(429, """{"error":{"code":"RESEND_COOLDOWN","details":{"waitSeconds":null}}}""")
        assertNull(ApiDetails.getInt(nullCooldown.details, "waitSeconds"))
    }

    @Test
    fun pushTargetTooLargeExposesCountAndMax() {
        val response = parse(400, """{"error":{"code":"PUSH_TARGET_TOO_LARGE","details":{"count":120,"max":100}}}""")
        assertEquals(120, ApiDetails.getInt(response.details, "count"))
        assertEquals(100, ApiDetails.getInt(response.details, "max"))
    }

    @Test
    fun scanWithReasonOnly() {
        val response = parse(400, """{"error":{"code":"SCAN_ERROR","details":{"reason":"unreadable"}}}""")
        assertEquals("unreadable", ApiDetails.getString(response.details, "reason"))
        assertNull(ApiDetails.getInt(response.details, "max"))
    }

    @Test
    fun scanWithReasonAndMax() {
        val response = parse(
            400,
            """{"error":{"code":"SCAN_LIMIT","details":{"reason":"too_many","max":50}}}"""
        )
        assertEquals("too_many", ApiDetails.getString(response.details, "reason"))
        assertEquals(50, ApiDetails.getInt(response.details, "max"))
    }

    @Test
    fun detailsMayBeAbsent() {
        val response = parse(400, """{"error":{"code":"X"}}""")
        assertNull(response.details)
        assertNull(ApiDetails.getInt(response.details, "max"))
    }

    @Test
    fun detailsMayBeNull() {
        val response = parse(400, """{"error":{"code":"X","details":null}}""")
        assertNull(response.details)
        assertNull(ApiDetails.getLong(response.details, "max"))
    }

    @Test
    fun unexpectedDetailTypesReturnNullNeverCrash() {
        val response = parse(
            400,
            """{"error":{"code":"X","details":{"count":"not-a-number","field":42,
                "list":123,"nested":"not-a-map","bad":null,"arr":[1,2]}}}"""
        )
        val d = response.details
        assertNull(ApiDetails.getLong(d, "count"))
        assertNull(ApiDetails.getString(d, "field"))
        assertNull(ApiDetails.getMap(d, "list"))
        assertNull(ApiDetails.getMap(d, "nested"))
        assertNull(ApiDetails.getInt(d, "bad"))
        assertEquals(2, ApiDetails.getList(d, "arr")?.size)
    }

    @Test
    fun invalidJsonYieldsSafeStatusBasedResponse() {
        val response = parse(500, "{ not json at all ")
        assertEquals(500, response.statusCode)
        assertNull(response.code)
        assertNull(response.message)
        // backendMessage keeps the capped raw text for diagnosis only.
        assertTrue(response.backendMessage.orEmpty().contains("not json"))
        val e = toException(response)
        assertEquals("status-fallback", e.message)
        assertEquals(500, e.httpStatus)
    }

    @Test
    fun emptyBodyYieldsSafeStatusBasedResponse() {
        val response = parse(500, "")
        assertEquals(500, response.statusCode)
        assertNull(response.code)
        assertNull(response.message)
        assertNull(response.backendMessage)
        val e = toException(response)
        assertEquals("status-fallback", e.message)
    }

    @Test
    fun nullBodyYieldsSafeStatusBasedResponse() {
        val response = parse(503, null)
        assertEquals(503, response.statusCode)
        assertNull(response.code)
        assertNull(response.message)
        assertNull(response.backendMessage)
    }

    @Test
    fun errorWithoutCodeStillCarriesStatusAndMessage() {
        val response = parse(400, """{"success":false,"message":"a message","error":{}}""")
        assertNull(response.code)
        assertEquals("a message", response.message)
        val e = toException(response)
        assertEquals("a message", e.message)
        assertNull(e.code)
    }

    @Test
    fun errorWithoutMessageFallsBackToStatusMessage() {
        val response = parse(400, """{"error":{"code":"X"}}""")
        assertNull(response.message)
        val e = toException(response, "status-fallback")
        assertEquals("status-fallback", e.message)
        assertEquals("X", e.code)
    }

    @Test
    fun statusCodesArePreserved() {
        assertEquals(401, parse(401, """{"error":{"code":"X"}}""").statusCode)
        assertEquals(404, parse(404, """{"error":{"code":"X"}}""").statusCode)
        assertEquals(429, parse(429, """{"error":{"code":"X"}}""").statusCode)
        assertEquals(500, parse(500, """{"error":{"code":"X"}}""").statusCode)
    }

    @Test
    fun legacyResponseCompatibility() {
        // Legacy: message at top level, code under error, no details.
        val response = parse(400, """{"message":"raw server text","error":{"code":"LEGACY"}}""")
        assertEquals("LEGACY", response.code)
        assertEquals("raw server text", response.message)
        assertNull(response.details)
        val e = toException(response)
        assertEquals("raw server text", e.message)
        assertEquals("LEGACY", e.code)
        assertEquals("raw server text", e.backendMessage)
    }

    @Test
    fun legacyStringFieldsListIsTolerated() {
        val response = parse(400, """{"error":{"code":"VALIDATION_ERROR","fields":["email","name"]}}""")
        assertEquals(listOf("email", "name"), response.fields?.map { it.field })
        assertTrue(response.fields?.all { it.message == null } == true)
    }

    @Test
    fun sanitizeStripsHtmlControlCharsAndCapsLength() {
        val dirty = "<b>hello</b>\u0000world   \u0007!\u0001"
        val cleaned = ErrorParser.sanitize(dirty)
        assertEquals("hello world !", cleaned)

        val long = "a".repeat(500)
        val capped = ErrorParser.sanitize(long)
        assertTrue((capped?.length ?: 0) <= 201)
        assertNull(ErrorParser.sanitize(null))
        assertNull(ErrorParser.sanitize("   "))
        assertNull(ErrorParser.sanitize(""))
    }

    @Test
    fun toApiExceptionCarriesAllFields() {
        val response = ErrorResponse(
            statusCode = 413,
            code = "FILE_TOO_LARGE",
            message = "too big",
            details = mapOf<String, Any>("max" to 100),
            backendMessage = "too big"
        )
        val e = toException(response)
        assertEquals("FILE_TOO_LARGE", e.code)
        assertEquals(413, e.httpStatus)
        assertEquals("too big", e.message)
        assertEquals(mapOf<String, Any>("max" to 100), e.details)
        assertEquals("too big", e.backendMessage)
    }

    @Test
    fun quotaHelperFlagsComeFromParsedCodes() {
        assertTrue(toException(parse(400, """{"error":{"code":"QUOTA_EXCEEDED"}}""")).isQuotaExceeded())
        assertTrue(
            toException(parse(400, """{"error":{"code":"QUOTA_EXCEEDED_ON_RESTORE"}}"""))
                .isRestoreQuotaExceeded()
        )
        val plain = toException(parse(400, """{"error":{"code":"OTHER"}}"""))
        assertFalse(plain.isQuotaExceeded())
        assertFalse(plain.isRestoreQuotaExceeded())
    }

    @Test
    fun fromBodyBuildsFromSuccessFalseEnvelope() {
        val body = nopalito.app.ui.screens.cloud.model.ApiResponse(
            success = false,
            message = "business error",
            data = null,
            error = nopalito.app.ui.screens.cloud.model.ApiError(
                code = "BUSINESS",
                details = mapOf<String, Any>("max" to 7)
            )
        )
        val response = ErrorParser.fromBody(200, body)
        assertEquals(200, response.statusCode)
        assertEquals("BUSINESS", response.code)
        assertEquals("business error", response.message)
        assertEquals(7L, ApiDetails.getLong(response.details, "max"))
    }
}
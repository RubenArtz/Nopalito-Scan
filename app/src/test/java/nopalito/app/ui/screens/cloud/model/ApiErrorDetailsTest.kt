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

package nopalito.app.ui.screens.cloud.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/**
 * Block: backend error.details parsing and normalization.
 *
 * Gson deserializes JSON objects into LinkedTreeMap and every JSON number into
 * a Double, so ApiDetails must normalize numbers, booleans, strings, nested
 * maps and lists safely — never showing raw Double decimals, null or
 * untranslated technical values.
 */
class ApiErrorDetailsTest {

    private val gson = Gson()

    private fun parseError(json: String): ApiError? =
        gson.fromJson(json, ApiResponse::class.java).error

    @Test
    fun parsesDetailsWithMixedValueTypes() {
        val error = parseError(
            """{"success":false,"message":"x",
               "error":{"code":"VALIDATION_ERROR",
                 "details":{"max":100,"count":3,"waitSeconds":45,"field":"email",
                            "ok":true,"tags":["a","b"],"nested":{"min":1}}}}"""
        )
        assertEquals("VALIDATION_ERROR", error?.code)
        val details = error?.details
        assertEquals(100L, ApiDetails.getLong(details, "max"))
        assertEquals(3, ApiDetails.getInt(details, "count"))
        assertEquals(45, ApiDetails.getInt(details, "waitSeconds"))
        assertEquals("email", ApiDetails.getString(details, "field"))
        assertEquals(true, ApiDetails.getBoolean(details, "ok"))
        assertEquals(2, ApiDetails.getList(details, "tags")?.size)
        assertEquals(1L, ApiDetails.getLong(ApiDetails.getMap(details, "nested"), "min"))
    }

    @Test
    fun gsonNumbersArriveAsDoubleAndAreNormalized() {
        val details = parseError(
            """{"success":false,"message":"x",
               "error":{"code":"QUOTA_EXCEEDED","details":{"availableBytes":1048576}}}"""
        )?.details
        // Prove the Gson behavior the normalization must tolerate.
        assertTrue(details?.get("availableBytes") is Double)
        assertEquals(1048576L, ApiDetails.getLong(details, "availableBytes"))
        assertEquals(1048576, ApiDetails.getInt(details, "availableBytes"))
        assertEquals(1048576.0, ApiDetails.getDouble(details, "availableBytes")!!, 0.0)
    }

    @Test
    fun detailsMayBeAbsentOrNull() {
        val withoutDetails = parseError("""{"success":false,"message":"x","error":{"code":"X"}}""")
        assertNull(withoutDetails?.details)
        assertNull(ApiDetails.getLong(withoutDetails?.details, "max"))
        assertNull(ApiDetails.getString(withoutDetails?.details, "field"))

        val nullDetails = parseError(
            """{"success":false,"message":"x","error":{"code":"X","details":null}}"""
        )
        assertNull(nullDetails?.details)
        assertNull(ApiDetails.getInt(nullDetails?.details, "count"))
    }

    @Test
    fun legacyErrorKeepsFieldsAndWaitSecondsWithoutDetails() {
        val error = parseError(
            """{"success":false,"message":"x",
               "error":{"code":"VALIDATION_ERROR","fields":["email","name"],"waitSeconds":30}}"""
        )
        assertEquals(listOf("email", "name"), error?.fields)
        assertEquals(30, error?.waitSeconds)
        assertNull(error?.details)
    }

    @Test
    fun errorMayBeAbsentEntirely() {
        val response = gson.fromJson(
            """{"success":true,"message":"ok","data":{}}""",
            ApiResponse::class.java
        )
        assertNull(response.error)
    }

    @Test
    fun numericStringValuesAreTolerated() {
        val details = mapOf(
            "waitSeconds" to "45",
            "max" to "100"
        )
        assertEquals(45L, ApiDetails.getLong(details, "waitSeconds"))
        assertEquals(100, ApiDetails.getInt(details, "max"))
    }

    @Test
    fun invalidOrMissingValuesReturnNullNeverCrash() {
        val details = mapOf<String, Any?>(
            "count" to 3.5,
            "field" to "email",
            "flag" to "yes",
            "nested" to "not-a-map",
            "list" to 42,
            "nullValue" to null as Any?
        )
        assertNull(ApiDetails.getString(details, "count"))   // number is not text
        assertNull(ApiDetails.getLong(details, "field"))     // text is not a number
        assertNull(ApiDetails.getBoolean(details, "flag"))   // "yes" is not a boolean
        assertNull(ApiDetails.getMap(details, "nested"))
        assertNull(ApiDetails.getList(details, "list"))
        assertNull(ApiDetails.getList(details, "missing"))
        assertNull(ApiDetails.getMap(details, "missing"))
        assertNull(ApiDetails.getBoolean(details, "missing"))
        assertNull(ApiDetails.getLong(details, "nullValue"))
        assertNull(ApiDetails.getLong(null, "max"))
    }

    @Test
    fun booleansAcceptJsonNumbersAndStrings() {
        val details = mapOf<String, Any?>(
            "a" to true,
            "b" to false,
            "c" to 1.0,   // Gson delivers 0/1 as Double
            "d" to 0.0,
            "e" to "true",
            "f" to "FALSE"
        )
        assertTrue(ApiDetails.getBoolean(details, "a")!!)
        assertFalse(ApiDetails.getBoolean(details, "b")!!)
        assertTrue(ApiDetails.getBoolean(details, "c")!!)
        assertFalse(ApiDetails.getBoolean(details, "d")!!)
        assertTrue(ApiDetails.getBoolean(details, "e")!!)
        assertFalse(ApiDetails.getBoolean(details, "f")!!)
    }

    @Test
    fun gettersAreNullSafeOnNullMap() {
        assertNull(ApiDetails.getString(null, "field"))
        assertNull(ApiDetails.getBoolean(null, "ok"))
        assertNull(ApiDetails.getList(null, "tags"))
        assertNull(ApiDetails.getMap(null, "nested"))
    }
}
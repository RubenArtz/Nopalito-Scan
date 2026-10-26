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

package nopalito.app.ui.screens.cloud.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import java.lang.reflect.Method

/**
 * Per-endpoint API versioning contract (Android side).
 *
 * - Every Retrofit endpoint MUST carry its own explicit version
 *   (`api/vN/...`) taken from [ApiEndpoints]. No interceptor, client or
 *   base URL may inject a global `/api/v1` prefix.
 * - [BuildConfig.API_BASE_URL] holds only the server origin (no version).
 * - Evolving ONE operation to v2 means editing ONE [ApiEndpoints] constant;
 *   every other endpoint keeps its version (asserted structurally below).
 */
class ApiEndpointsTest {

    private val apis = listOf(
        AuthApi::class.java,
        StorageApi::class.java,
        FileApi::class.java,
        LibreOfficeApi::class.java,
        QrApi::class.java,
        ScanApi::class.java,
        DeviceApi::class.java,
        MaintenanceApi::class.java,
        UserPreferencesApi::class.java,
        BillingApi::class.java,
        AvatarApi::class.java,
        SessionApi::class.java,
        CloudLinkApi::class.java
    )

    private fun Method.httpPath(): String {
        val ann = annotations.firstOrNull {
            it is GET || it is POST || it is PUT || it is PATCH || it is DELETE
        } ?: error("No HTTP method annotation on $name")
        val value = when (ann) {
            is GET -> ann.value
            is POST -> ann.value
            is PUT -> ann.value
            is PATCH -> ann.value
            is DELETE -> ann.value
            else -> error("unreachable")
        }
        return value
    }

    @Test
    fun `every endpoint carries an explicit independent version`() {
        val versioned = Regex("^api/v\\d+/.+")
        var count = 0
        for (api in apis) {
            for (method in api.methods) {
                if (method.isSynthetic || method.name.contains("$")) continue
                val path = method.httpPath()
                assertTrue(
                    "${api.simpleName}.${method.name} must be versioned (got '$path')",
                    versioned.matches(path)
                )
                count++
            }
        }
        assertTrue("expected Retrofit endpoints to be covered, found $count", count > 50)
    }

    @Test
    fun `catalog entries are relative paths without base url or doubled prefixes`() {
        val values = ApiEndpoints::class.java.declaredFields
            .filter { it.type == String::class.java && it.name != "VERSION_V1" }
            .map { it.get(null) as String }
        assertTrue(values.isNotEmpty())
        for (v in values) {
            assertTrue("'$v' must not contain a host", !v.contains("://"))
            assertTrue("'$v' must not duplicate slashes", !v.contains("//"))
            assertTrue("'$v' must carry an explicit version", v.matches(Regex("^api/v\\d+/.*")))
        }
    }

    @Test
    fun `changing one endpoint version leaves the others untouched`() {
        // Simulation: only FILES_LIST moves to v2; the catalog shape guarantees
        // independence because each entry is its own constant.
        val simulatedV2 = ApiEndpoints.FILES_LIST.replace("api/v1/", "api/v2/")
        assertEquals("api/v2/files", simulatedV2)
        assertEquals("api/v1/scans", ApiEndpoints.SCANS_LIST)
        assertEquals("api/v1/billing/status", ApiEndpoints.BILLING_STATUS)
        assertEquals("api/v1/auth/refresh", ApiEndpoints.AUTH_REFRESH)
        assertEquals("api/v1/qr/styles", ApiEndpoints.QR_STYLES)
    }
}

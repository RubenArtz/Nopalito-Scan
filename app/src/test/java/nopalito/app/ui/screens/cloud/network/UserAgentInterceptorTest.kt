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

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserAgentInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(vararg interceptors: okhttp3.Interceptor): OkHttpClient {
        val builder = OkHttpClient.Builder()
        interceptors.forEach { builder.addInterceptor(it) }
        return builder.build()
    }

    @Test
    fun `sends NopalitoScan identity with version and Android release`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        client(
            UserAgentInterceptor(value = { "NopalitoScan/1.0.22 (Android 15)" })
        ).newCall(
            Request.Builder().url(server.url("/api/auth/me")).build()
        ).execute()

        assertEquals(
            "NopalitoScan/1.0.22 (Android 15)",
            server.takeRequest().headers["User-Agent"]
        )
    }

    @Test
    fun `default value exposes app name version and platform`() {
        val default = UserAgentInterceptor.DEFAULT_USER_AGENT

        assertTrue(default, default.startsWith("${UserAgentInterceptor.APP_NAME}/"))
        assertTrue(default, default.contains(" (Android "))
        assertTrue(default, default.endsWith(")"))
    }

    @Test
    fun `replaces any caller supplied User-Agent instead of appending`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        client(
            UserAgentInterceptor(value = { "NopalitoScan/1.0.22 (Android 15)" })
        ).newCall(
            Request.Builder()
                .url(server.url("/api/files"))
                .header("User-Agent", "stale/0.0 (Old 1)")
                .build()
        ).execute()

        val headerValues = server.takeRequest().headers.values("User-Agent")
        assertEquals(listOf("NopalitoScan/1.0.22 (Android 15)"), headerValues)
    }

    @Test
    fun `does not disturb other headers on the request`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        client(
            UserAgentInterceptor(value = { "NopalitoScan/1.0.22 (Android 15)" })
        ).newCall(
            Request.Builder()
                .url(server.url("/api/files"))
                .header("Accept-Language", "es")
                .build()
        ).execute()

        val request = server.takeRequest()
        assertEquals("es", request.headers["Accept-Language"])
        assertEquals("NopalitoScan/1.0.22 (Android 15)", request.headers["User-Agent"])
    }
}

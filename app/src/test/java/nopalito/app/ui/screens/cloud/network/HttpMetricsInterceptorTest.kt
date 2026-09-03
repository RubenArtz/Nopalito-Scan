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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpMetricsInterceptorTest {

    private lateinit var server: MockWebServer
    private val lines = mutableListOf<String>()

    private val metrics = HttpMetricsInterceptor(logger = { lines += it })

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        lines.clear()
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
    fun `logs endpoint, status and duration on success`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        client(metrics).newCall(
            Request.Builder()
                .url(server.url("/api/files"))
                .header("Authorization", "Bearer super-secret-token")
                .build()
        ).execute()

        val line = lines.single()
        assertTrue(line, line.contains("GET /api/files -> 200"))
        assertTrue(line, line.contains(" in "))
        assertTrue(line, line.endsWith("ms (retries=0)"))
        // Requirement 18: the token must never appear.
        assertFalse(line, line.contains("super-secret-token"))
        assertFalse(lines.joinToString(), lines.joinToString().contains("Bearer "))
    }

    @Test
    fun `reports retry count published by RetryInterceptor`() {
        server.enqueue(MockResponse.Builder().code(502).body("{}").build())
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        // Metrics OUTERMOST so it observes the final attempt's header.
        client(metrics, RetryInterceptor(maxAttempts = 2, initialBackoffMs = 10)).newCall(
            Request.Builder().url(server.url("/api/files")).build()
        ).execute()

        val line = lines.single()
        assertTrue(line, line.contains("(retries=1)"))
        assertFalse(lines.joinToString(), lines.joinToString().contains("super-secret"))
    }

    @Test
    fun `logs transport errors by simple exception name`() {
        // Nothing enqueued + immediate shutdown → connection refused.
        server.close()

        runCatching {
            client(metrics).newCall(
                Request.Builder().url(server.url("/api/storage/usage")).build()
            ).execute()
        }

        val line = lines.single()
        assertTrue(line, line.contains("ERR/"))
        assertTrue(line, line.contains("GET /api/storage/usage"))
    }
}
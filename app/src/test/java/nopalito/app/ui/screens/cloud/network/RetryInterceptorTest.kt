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
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RetryInterceptorTest {

    private lateinit var server: MockWebServer

    /** Records every backoff sleep instead of really waiting. */
    private val sleeps = mutableListOf<Long>()

    private val interceptor = RetryInterceptor(
        maxAttempts = 3,
        initialBackoffMs = 100,
        maxBackoffMs = 1_000,
        jitterRatio = 0.25,
        sleeper = { ms -> sleeps += ms }
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sleeps.clear()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun get(path: String): Request =
        Request.Builder().url(server.url(path)).build()

    private fun post(): Request = Request.Builder()
        .url(server.url("/api/files/upload"))
        .post("payload".toRequestBody())
        .build()

    private fun MockWebServer.enqueueCode(code: Int) {
        enqueue(MockResponse.Builder().code(code).body("{}").build())
    }

    @Test
    fun `200 passes through without retries`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        val response = client().newCall(get("/api/files")).execute()

        assertEquals(200, response.code)
        assertEquals(0, response.header(RetryAttemptTag.HEADER)?.toIntOrNull())
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `4xx is never retried`() {
        server.enqueueCode(404)

        val response = client().newCall(get("/api/files")).execute()

        assertEquals(404, response.code)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `non-idempotent POST is never retried even on transient status`() {
        server.enqueueCode(503)

        val response = client().newCall(post()).execute()

        assertEquals(503, response.code)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `transient 502 on GET is retried once until success`() {
        server.enqueueCode(502)
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        val response = client().newCall(get("/api/files")).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
        // One backoff sleep happened, honoring the exponential base.
        assertEquals(1, sleeps.size)
        assertTrue("backoff=${sleeps.first()}", sleeps.first() >= 100L)
        assertEquals(1, response.header(RetryAttemptTag.HEADER)?.toIntOrNull())
    }

    @Test
    fun `retries stop after maxAttempts and surface the server status`() {
        repeat(3) { server.enqueueCode(502) }

        val response = client().newCall(get("/api/files")).execute()

        assertEquals(502, response.code)
        assertEquals(3, server.requestCount)
        assertEquals(2, sleeps.size)
        assertEquals(2, response.header(RetryAttemptTag.HEADER)?.toIntOrNull())
    }

    @Test
    fun `Retry-After header overrides backoff for 429`() {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "1")
                .body("{}")
                .build()
        )
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        val response = client().newCall(get("/api/files")).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(1_000L), sleeps)
    }

    @Test
    fun `read timeout counts as transient error and is retried`() {
        val timingClient = OkHttpClient.Builder()
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .addInterceptor(
                RetryInterceptor(
                    maxAttempts = 2,
                    initialBackoffMs = 50,
                    sleeper = { sleeps += it }
                )
            )
            .build()

        // Attempt 1 stalls past the read timeout (headers never arrive);
        // attempt 2 answers fast.
        server.enqueue(
            MockResponse.Builder().code(200).body("slow").headersDelay(900, TimeUnit.MILLISECONDS)
                .build()
        )
        server.enqueue(MockResponse.Builder().code(200).body("fast").build())

        val response = timingClient.newCall(get("/api/files")).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
        assertEquals(1, sleeps.size)
    }
}
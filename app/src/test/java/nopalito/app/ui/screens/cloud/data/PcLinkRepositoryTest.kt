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

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import nopalito.app.ui.screens.cloud.model.PcLinkError
import nopalito.app.ui.screens.cloud.network.CloudLinkApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Cloud Link repository contract (Phase 2), exercised against a real
 * MockWebServer + Retrofit stack. The DataStore is mocked: persistence has
 * its own round-trip test in [PcLinkDataStoreTest].
 */
class PcLinkRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: CloudLinkApi
    private lateinit var repository: PcLinkRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CloudLinkApi::class.java)
        val store = mockk<PcLinkDataStore>(relaxed = true)
        coEvery { store.saveLinkSession(any(), any()) } returns Unit
        repository = PcLinkRepository(api, store)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun envelope(dataJson: String?, errorCode: String?): String {
        val data = dataJson ?: "null"
        val error = errorCode?.let { """, "error": {"code": "$it"}""" } ?: ""
        return """{"success":${errorCode == null},"message":"ok","data":$data$error}"""
    }

    private fun enqueueJson(code: Int, body: String) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build()
        )
    }

    @Test
    fun `approveQr success returns Unit`() = runTest {
        enqueueJson(200, envelope("null", null))
        val result = repository.approveQr("intent-id", "nonce")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `approveQr owner mismatch maps to UserMismatch`() = runTest {
        enqueueJson(403, envelope("null", "LINK_OWNER_MISMATCH"))
        val result = repository.approveQr("intent-id", "nonce")
        assertEquals(PcLinkError.UserMismatch, result.exceptionOrNull())
    }

    @Test
    fun `approveQr rate limit maps to RateLimited`() = runTest {
        enqueueJson(429, envelope("null", "RATE_LIMIT_EXCEEDED"))
        val result = repository.approveQr("intent-id", "nonce")
        assertEquals(PcLinkError.RateLimited, result.exceptionOrNull())
    }

    @Test
    fun `approveByPin blocked maps to IntentBlocked`() = runTest {
        enqueueJson(403, envelope("null", "LINK_INTENT_BLOCKED"))
        val result = repository.approveByPin("000000", "intent-id")
        assertEquals(PcLinkError.IntentBlocked, result.exceptionOrNull())
    }

    @Test
    fun `approveByPin expired maps to IntentExpired`() = runTest {
        enqueueJson(410, envelope("null", "LINK_INTENT_EXPIRED"))
        val result = repository.approveByPin("000000", "intent-id")
        assertEquals(PcLinkError.IntentExpired, result.exceptionOrNull())
    }

    @Test
    fun `approveByPin sends pin and intent`() = runTest {
        enqueueJson(200, envelope("null", null))
        repository.approveByPin("482916", "intent-id")
        val recorded = server.takeRequest()
        val body = recorded.body?.utf8().orEmpty()
        assertTrue(body, body.contains("482916"))
        assertTrue(body, body.contains("intent-id"))
    }

    @Test
    fun `listSessions returns scoped sessions`() = runTest {
        enqueueJson(
            200,
            envelope(
                """{"sessions":[{"id":"s1","web_device_id":"pc-1","ip_address":"1.2.3.4"}],"activeCount":1}""",
                null
            )
        )
        val result = repository.listSessions()
        assertTrue(result.isSuccess)
        val sessions = result.getOrThrow()
        assertEquals(1, sessions.size)
        assertEquals("pc-1", sessions[0].deviceLabel)
        assertEquals("1.2.3.4", sessions[0].ipLabel)
    }

    @Test
    fun `revokeSession success returns Unit`() = runTest {
        enqueueJson(200, envelope("null", null))
        val result = repository.revokeSession("s1")
        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertTrue(recorded.target, recorded.target.contains("s1"))
    }

    @Test
    fun `revokeSession unknown maps to IntentNotFound`() = runTest {
        enqueueJson(404, envelope("null", "LINK_SESSION_NOT_FOUND"))
        val result = repository.revokeSession("missing")
        assertEquals(PcLinkError.IntentNotFound, result.exceptionOrNull())
    }

    @Test
    fun `unreachable backend maps to NetworkError`() = runTest {
        server.close()
        val result = repository.approveQr("intent-id", "nonce")
        assertEquals(PcLinkError.NetworkError, result.exceptionOrNull())
    }

    @Test
    fun `mapHttpError prefers backend code over status`() {
        assertEquals(
            PcLinkError.UserMismatch,
            PcLinkRepository.mapHttpError(403, envelope("null", "LINK_OWNER_MISMATCH"))
        )
        assertEquals(
            PcLinkError.RateLimited,
            PcLinkRepository.mapHttpError(429, null)
        )
        assertEquals(
            PcLinkError.IntentExpired,
            PcLinkRepository.mapHttpError(410, null)
        )
        assertEquals(
            PcLinkError.Unauthorized,
            PcLinkRepository.mapHttpError(401, null)
        )
    }
}

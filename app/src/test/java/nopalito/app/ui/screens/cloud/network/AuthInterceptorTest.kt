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

import io.mockk.*
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.ui.screens.cloud.model.ApiResponse
import nopalito.app.ui.screens.cloud.model.TokenData
import okhttp3.Headers
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.*
import java.util.concurrent.TimeUnit
import retrofit2.Response as RetrofitResponse

/**
 * Header contract tests for [AuthInterceptor], exercised against a real
 * OkHttpClient + a local JDK HTTP server, so the full interceptor chain and
 * the real OkHttp [okhttp3.Interceptor.Chain] are covered (no stubs).
 */
class AuthInterceptorTest {

    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = AppLocaleOverride.locale
    }

    @After
    fun tearDown() {
        AppLocaleOverride.locale = savedLocale
    }

    private fun setAppLocale(tag: String) {
        AppLocaleOverride.locale = Locale.forLanguageTag(tag)
    }

    /** Minimal HTTP/1.1 server capturing every received request header set. */
    private class EchoServer(vararg responseCodes: Int) {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val captured = java.util.concurrent.CopyOnWriteArrayList<Headers>()
        private val codes = ArrayDeque<Int>(responseCodes.toList())

        @Volatile
        private var running = true

        private val thread = Thread {
            while (running) {
                try {
                    val socket = serverSocket.accept()
                    Thread {
                        try {
                            handle(socket)
                        } catch (_: Exception) {
                            // Client hung up early; nothing to record.
                        } finally {
                            socket.close()
                        }
                    }.start()
                } catch (_: Exception) {
                    // Socket closed on stop(); exit loop.
                }
            }
        }.apply { isDaemon = true }

        init {
            thread.start()
        }

        private fun handle(socket: Socket) {
            socket.soTimeout = 5_000
            val input = socket.getInputStream().buffered()
            val output = socket.getOutputStream()
            fun readLine(): String {
                val sb = StringBuilder()
                var b = input.read()
                while (b != -1 && b != '\n'.code) {
                    sb.append(b.toChar())
                    b = input.read()
                }
                return sb.toString().trimEnd('\r')
            }
            readLine() // request line
            val builder = Headers.Builder()
            var contentLength = 0
            var line = readLine()
            while (line.isNotEmpty()) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val name = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    builder.add(name, value)
                    if (name.equals("Content-Length", true)) {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
                line = readLine()
            }
            if (contentLength > 0) {
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = input.read(body, read, contentLength - read)
                    if (r < 0) break
                    read += r
                }
            }
            captured += builder.build()
            val code = codes.pollFirst() ?: 200
            val statusLine = if (code == 401) "HTTP/1.1 401 Unauthorized" else "HTTP/1.1 200 OK"
            val bodyBytes = "{}".toByteArray(Charsets.UTF_8)
            val head = "$statusLine\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
            output.write(head.toByteArray(Charsets.UTF_8))
            output.write(bodyBytes)
            output.flush()
        }

        fun start(): String = "http://127.0.0.1:${serverSocket.localPort}"

        fun stop() {
            running = false
            serverSocket.close()
            thread.join(2_000)
        }

        /** Wait (bounded) until at least [expected] requests were recorded. */
        fun awaitSize(expected: Int) {
            val deadline = System.currentTimeMillis() + 5_000
            while (captured.size < expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals("captured=${captured}", expected, captured.size)
        }
    }

    private fun newClient(
        accessToken: String? = "access-token",
        refreshToken: String? = "refresh-token",
        configureAuthApi: (AuthApi) -> Unit = {},
    ): Pair<OkHttpClient, AuthApi> {
        val tokenProvider = mockk<TokenProvider>()
        every { tokenProvider.getAccessToken() } returns accessToken
        every { tokenProvider.getRefreshToken() } returns refreshToken
        every { tokenProvider.clearTokens() } just runs
        every { tokenProvider.saveTokens(any(), any(), any()) } just runs
        val authApi = mockk<AuthApi>()
        configureAuthApi(authApi)
        val client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, lazy { authApi }))
            .build()
        return client to authApi
    }

    private fun request(baseUrl: String, path: String = "/api/files"): Request =
        Request.Builder().url("$baseUrl$path").build()

    private fun execute(
        client: OkHttpClient,
        request: Request,
        server: EchoServer,
        expectedRequests: Int = 1,
    ) {
        client.newCall(request).execute().use { }
        server.awaitSize(expectedRequests)
    }

    @Test
    fun bothLocaleHeadersPresentWithTheSameValue() {
        setAppLocale("es-419")
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            val (client, _) = newClient()
            execute(client, request(baseUrl), server)

            val headers = server.captured.single()
            assertEquals(listOf("es-419"), headers.values("Accept-Language"))
            assertEquals(listOf("es-419"), headers.values("x-app-language"))
            assertEquals(
                headers.values("Accept-Language"),
                headers.values("x-app-language"),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun ptBrIsSentAsPtBr() {
        setAppLocale("pt-BR")
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            execute(newClient().first, request(baseUrl), server)

            val headers = server.captured.single()
            assertEquals("pt-BR", headers["Accept-Language"])
            assertEquals("pt-BR", headers["x-app-language"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun es419IsSentAsEs419() {
        setAppLocale("es-419")
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            execute(newClient().first, request(baseUrl), server)

            val headers = server.captured.single()
            assertEquals("es-419", headers["Accept-Language"])
            assertEquals("es-419", headers["x-app-language"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun frCaReducesToFr() {
        setAppLocale("fr-CA")
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            execute(newClient().first, request(baseUrl), server)

            val headers = server.captured.single()
            assertEquals("fr", headers["Accept-Language"])
            assertEquals("fr", headers["x-app-language"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun zhFallsBackToEs() {
        setAppLocale("zh")
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            execute(newClient().first, request(baseUrl), server)

            val headers = server.captured.single()
            assertEquals("es", headers["Accept-Language"])
            assertEquals("es", headers["x-app-language"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun enUsIsSentAsEnUs() {
        setAppLocale("en-US")
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            execute(newClient().first, request(baseUrl), server)

            val headers = server.captured.single()
            assertEquals("en-US", headers["Accept-Language"])
            assertEquals("en-US", headers["x-app-language"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun esCoReducesToEs() {
        setAppLocale("es-CO")
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            execute(newClient().first, request(baseUrl), server)

            val headers = server.captured.single()
            assertEquals("es", headers["Accept-Language"])
            assertEquals("es", headers["x-app-language"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun emptyOrInvalidLocaleFallsBackToEs() {
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            val invalid = listOf(
                Locale.ROOT, // toLanguageTag() -> "und"
                Locale.forLanguageTag("und"),
                Locale.forLanguageTag("zh-Hans"),
                Locale.forLanguageTag("sv-SE"),
            )
            val (client, _) = newClient()
            for ((index, locale) in invalid.withIndex()) {
                AppLocaleOverride.locale = locale
                execute(client, request(baseUrl), server, expectedRequests = index + 1)
            }
            assertTrue(server.captured.size == invalid.size)
            for (headers in server.captured) {
                assertEquals("es", headers["Accept-Language"])
                assertEquals("es", headers["x-app-language"])
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun preexistingLocaleHeadersAreReplacedNotDuplicated() {
        setAppLocale("pt-BR")
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            val (client, _) = newClient()
            val request = Request.Builder()
                .url("$baseUrl/api/files")
                .addHeader("Accept-Language", "en")
                .addHeader("x-app-language", "en")
                .addHeader("Accept-Language", "de")
                .build()
            execute(client, request, server)

            val headers = server.captured.single()
            assertEquals(1, headers.values("Accept-Language").size)
            assertEquals(1, headers.values("x-app-language").size)
            assertEquals("pt-BR", headers["Accept-Language"])
            assertEquals("pt-BR", headers["x-app-language"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun normalRequestCarriesLocaleHeadersAndBearerToken() {
        setAppLocale("de-DE")
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            execute(newClient(accessToken = "abc").first, request(baseUrl), server)

            val headers = server.captured.single()
            assertEquals("de-DE", headers["Accept-Language"])
            assertEquals("de-DE", headers["x-app-language"])
            assertEquals("Bearer abc", headers["Authorization"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun multipartRequestCarriesLocaleHeaders() {
        setAppLocale("pt-BR")
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("category", "document")
                .build()
            val (client, _) = newClient()
            val request = Request.Builder()
                .url("$baseUrl/api/files/upload")
                .post(multipart)
                .build()
            execute(client, request, server)

            val headers = server.captured.single()
            assertEquals("pt-BR", headers["Accept-Language"])
            assertEquals("pt-BR", headers["x-app-language"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun downloadRequestCarriesLocaleHeaders() {
        setAppLocale("fr-FR")
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            execute(newClient().first, request(baseUrl, "/api/files/123/download"), server)

            val headers = server.captured.single()
            assertEquals("fr-FR", headers["Accept-Language"])
            assertEquals("fr-FR", headers["x-app-language"])
            assertEquals("Bearer access-token", headers["Authorization"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun unauthenticatedRequestCarriesLocaleHeadersAndNoAuthorization() {
        setAppLocale("es-419")
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            execute(newClient(accessToken = null).first, request(baseUrl), server)

            val headers = server.captured.single()
            assertEquals("es-419", headers["Accept-Language"])
            assertEquals("es-419", headers["x-app-language"])
            assertNull(headers["Authorization"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun retryAfterRefreshCarriesLocaleHeadersAndNewToken() {
        setAppLocale("pt-BR")
        val tokenData = TokenData(
            accessToken = "new-access",
            refreshToken = "new-refresh",
            accessTokenExpiresIn = "3600",
            refreshTokenExpiresIn = "86400",
        )
        val server = EchoServer(401, 200)
        val baseUrl = server.start()
        try {
            val (client, _) = newClient(configureAuthApi = { api ->
                coEvery { api.refreshToken(any()) } returns
                        RetrofitResponse.success(ApiResponse(success = true, message = "ok", data = tokenData))
            })
            execute(client, request(baseUrl), server, expectedRequests = 2)

            assertEquals(2, server.captured.size)
            val original = server.captured[0]
            val retry = server.captured[1]
            assertEquals("Bearer access-token", original["Authorization"])
            assertEquals("pt-BR", original["Accept-Language"])
            assertEquals("pt-BR", original["x-app-language"])
            assertEquals("Bearer new-access", retry["Authorization"])
            assertEquals("pt-BR", retry["Accept-Language"])
            assertEquals("pt-BR", retry["x-app-language"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun localeIsReevaluatedBetweenTwoRequests() {
        val server = EchoServer()
        val baseUrl = server.start()
        try {
            val (client, _) = newClient()

            setAppLocale("pt-BR")
            execute(client, request(baseUrl), server, expectedRequests = 1)
            assertEquals("pt-BR", server.captured[0]["Accept-Language"])

            setAppLocale("fr")
            execute(client, request(baseUrl), server, expectedRequests = 2)
            assertEquals("fr", server.captured[1]["Accept-Language"])
            assertTrue(
                server.captured[1]["Accept-Language"] != server.captured[0]["Accept-Language"],
            )
        } finally {
            server.stop()
        }
    }
}

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

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException

/** Header published on every response carrying the number of retries used. */
object RetryAttemptTag {
    const val HEADER = "X-Nopalito-Retry-Count"
}

/**
 * Limited automatic retry with exponential backoff + jitter, restricted to
 * TRANSIENT failures only:
 *
 * Retried:
 * - Transport [IOException]s (connection reset, DNS, broken pipe) and OkHttp
 *   read/write timeouts ([InterruptedIOException] family).
 * - HTTP 429 / 502 / 503 / 504 on IDEMPOTENT methods only (GET/HEAD).
 *
 * NEVER retried:
 * - Any other HTTP status, especially ALL 4xx client errors (400-499): they
 *   are deterministic; repeating them just repeats the failure.
 * - Non-idempotent methods (POST/PATCH/PUT/DELETE): a blind retry could
 *   duplicate a server-side operation (double upload, double delete...).
 * - Calls already canceled by the caller (screen left / ViewModel cleared):
 *   cancellation is detected before each attempt and via [Call.isCanceled].
 *
 * Backoff: min([maxBackoffMs], initialBackoffMs * multiplier^(attempt-1))
 * plus uniform ±jitter so parallel failures don't retry in lockstep. A
 * `Retry-After` seconds header on 429/503 overrides the computed backoff,
 * capped at [maxRetryAfterMs].
 *
 * Threading: runs on OkHttp dispatcher threads only — never the main thread.
 */
class RetryInterceptor(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialBackoffMs: Long = 500,
    private val maxBackoffMs: Long = 4_000,
    private val multiplier: Double = 2.0,
    private val jitterRatio: Double = 0.25,
    private val maxRetryAfterMs: Long = 10_000,
    /** Injectable sleep so unit tests run without real wall-clock delays. */
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {

    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0

        while (true) {
            attempt++
            if (chain.call().isCanceled()) {
                throw InterruptedIOException("Canceled before attempt $attempt")
            }

            var response: Response? = null
            try {
                response = chain.proceed(request)

                if (!isRetryable(response.code, request.method)) {
                    return attachAttempt(response, attempt)
                }
                if (attempt >= maxAttempts) {
                    // Terminal: surface the server's status after the last try.
                    return attachAttempt(response, attempt)
                }
                val delayMs = delayFor(response.code, response.header("Retry-After"), attempt)
                response.close()
                response = null
                sleeper(delayMs)
            } catch (e: IOException) {
                response?.close()
                // Canceled calls must propagate immediately (requirement 6).
                if (chain.call().isCanceled()) throw e
                if (attempt >= maxAttempts) throw e
                sleeper(backoffFor(attempt))
            }
        }
    }

    private fun attachAttempt(response: Response, attempt: Int): Response =
        response.newBuilder()
            .header(RetryAttemptTag.HEADER, (attempt - 1).toString())
            .build()

    private fun isRetryable(code: Int, method: String): Boolean =
        method in IDEMPOTENT_METHODS &&
                (code == 429 || code == 502 || code == 503 || code == 504)

    private fun delayFor(code: Int, retryAfterHeader: String?, attempt: Int): Long {
        val seconds = retryAfterHeader?.trim()?.toLongOrNull()
        if (seconds != null && seconds >= 0 && (code == 429 || code == 503)) {
            return (seconds * 1000L).coerceIn(0, maxRetryAfterMs)
        }
        return backoffFor(attempt)
    }

    private fun backoffFor(attempt: Int): Long {
        val exponential = initialBackoffMs * Math.pow(multiplier, (attempt - 1).toDouble()).toLong()
        val base = exponential.coerceAtMost(maxBackoffMs)
        val jitterBound = (base * jitterRatio).toLong() + 1
        // ThreadLocalRandom#nextLong(bound): same jitter semantics without the
        // API-35-gated RandomGenerator default method lint flags on java.util.Random.
        return base + java.util.concurrent.ThreadLocalRandom.current().nextLong(jitterBound)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        private val IDEMPOTENT_METHODS = setOf("GET", "HEAD")
    }
}
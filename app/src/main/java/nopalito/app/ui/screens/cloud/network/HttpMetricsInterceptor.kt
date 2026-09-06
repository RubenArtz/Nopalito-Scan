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

/**
 * Structured, single-line metrics log for every cloud request:
 *
 * ```
 * CloudApi GET /api/files?page=1 -> 200 OK in 450ms (retries=0)
 * ```
 *
 * Placement matters: this interceptor must be the FIRST application
 * interceptor so its duration covers auth refreshes AND retry attempts.
 * The retry count is published by [RetryInterceptor] through the request
 * tag ([RetryAttemptTag]).
 *
 * Privacy contract (requirement 18):
 * - Never logs headers or bodies; only method + encoded path + query.
 * - Authorization/Cookie/Set-Cookie values never appear in any line.
 * - Errors are logged by exception simple name only (no messages that could
 *   embed URLs with tokens).
 */
class HttpMetricsInterceptor(
    private val logger: (String) -> Unit = { android.util.Log.i(LOG_TAG, it) }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startNanos = System.nanoTime()
        var codeLabel = "ERR"
        var error: String? = null
        var retries = 0
        try {
            val response = chain.proceed(request)
            codeLabel = "${response.code} ${response.message.ifBlank { "-" }}"
            // Published by RetryInterceptor on every attempt's response.
            retries = response.header(RetryAttemptTag.HEADER)?.toIntOrNull() ?: 0
            return response
        } catch (e: Exception) {
            // Cancellation is normal when the user leaves a screen; keep it quiet.
            if (e !is kotlinx.coroutines.CancellationException) {
                error = e.javaClass.simpleName
                codeLabel = "ERR/$error"
            }
            throw e
        } finally {
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            logger(
                buildString {
                    append("CloudApi ")
                    append(request.method)
                    append(' ')
                    append(request.url.encodedPath)
                    val query = request.url.query
                    if (!query.isNullOrEmpty()) {
                        append('?')
                        append(query)
                    }
                    append(" -> ")
                    append(codeLabel)
                    append(" in ")
                    append(elapsedMs)
                    append("ms (retries=")
                    append(retries)
                    append(')')
                    if (error != null) {
                        append(" error=")
                        append(error)
                    }
                }
            )
        }
    }

    companion object {
        const val LOG_TAG = "CloudApi"
    }
}
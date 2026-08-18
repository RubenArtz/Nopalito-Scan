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

import com.google.gson.Gson
import nopalito.app.ui.screens.cloud.model.ApiError
import nopalito.app.ui.screens.cloud.model.ApiResponse
import nopalito.app.ui.screens.cloud.model.ErrorResponse
import nopalito.app.ui.screens.cloud.model.FieldError

/**
 * Tolerant parser that turns an HTTP error body into a structured [ErrorResponse]
 * (and finally into a safe [ApiException]).
 *
 * Contract:
 * - It NEVER throws: invalid JSON, an empty body, or an unexpected shape always
 *   yield a [ErrorResponse] carrying at least the HTTP [ErrorResponse.statusCode],
 *   so the caller can fall back to a status-based localized message.
 * - It covers the real backend shapes: `VALIDATION_ERROR.fields[]` (object list,
 *   each `field` + localized `message`), flat quota fields under `error`
 *   (`QUOTA_EXCEEDED_ON_RESTORE`), `error.details.waitSeconds`, push `count`/`max`
 *   and scan `reason`/`max`.
 * - [details] is only ever read through [ApiDetails] helpers; raw JSON maps are
 *   never rendered. Values that Gson delivers as `Double`/`LinkedTreeMap` are kept
 *   as-is for [ApiDetails] to normalize.
 * - No stack traces or full HTTP bodies leak: any server text is sanitized and
 *   capped (see [sanitize]) before it can reach a visible message or
 *   [ErrorResponse.backendMessage].
 */
object ErrorParser {

    private const val MAX_BACKEND_MESSAGE = 200
    private val gson = Gson()
    private val htmlTag = Regex("""<[^>]*>""")
    private val controlChars = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")
    private val whitespace = Regex("""\s+""")

    private val quotaFlatKeys = listOf(
        "quotaBytes", "usedBytes", "restoreBytes",
        "availableBytes", "requiredBytes", "finalUsedBytes"
    )

    /**
     * Parses the raw error body of a non-2xx response. Always returns a
     * [ErrorResponse]; never throws.
     */
    fun parse(statusCode: Int, rawBody: String?): ErrorResponse {
        if (rawBody.isNullOrBlank()) {
            return ErrorResponse(statusCode = statusCode)
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val root = gson.fromJson(rawBody, Map::class.java) as? Map<String, Any?>
            if (root == null) {
                ErrorResponse(statusCode = statusCode, backendMessage = sanitize(rawBody))
            } else {
                val errorObj = root["error"] as? Map<*, *>
                val code = (errorObj?.get("code") as? String)?.takeIf { it.isNotBlank() }
                val message = (root["message"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (errorObj?.get("message") as? String)?.takeIf { it.isNotBlank() }
                ErrorResponse(
                    statusCode = statusCode,
                    code = code,
                    message = sanitize(message),
                    details = extractDetails(errorObj),
                    fields = extractFields(errorObj),
                    backendMessage = sanitize(message)
                )
            }
        } catch (_: Exception) {
            ErrorResponse(statusCode = statusCode, backendMessage = sanitize(rawBody))
        }
    }

    /**
     * Builds an [ErrorResponse] from a 2xx response whose envelope carries
     * `success=false` (business-level error already deserialized by Gson).
     */
    fun fromBody(statusCode: Int, body: ApiResponse<*>?): ErrorResponse {
        val error: ApiError? = body?.error
        val message = body?.message?.takeIf { it.isNotBlank() }
        return ErrorResponse(
            statusCode = statusCode,
            code = error?.code,
            message = sanitize(message),
            details = error?.details,
            backendMessage = sanitize(message)
        )
    }

    /**
     * Normalizes server-provided text for safe display/logging: trims, strips
     * HTML tags, removes control characters, collapses whitespace and caps the
     * length. Returns null for null/blank input.
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        var s = raw.replace(htmlTag, " ")
            .replace(controlChars, "")
            .replace(whitespace, " ")
            .trim()
        if (s.isEmpty()) return null
        if (s.length > MAX_BACKEND_MESSAGE) {
            s = s.take(MAX_BACKEND_MESSAGE) + "…"
        }
        return s
    }

    /**
     * Gathers the typed `error.details` map plus, for `QUOTA_EXCEEDED_ON_RESTORE`,
     * the flat numeric fields that the backend places directly under `error`.
     */
    private fun extractDetails(errorObj: Map<*, *>?): Map<String, Any>? {
        if (errorObj == null) return null
        val details = LinkedHashMap<String, Any>()
        val nested = errorObj["details"]
        if (nested is Map<*, *>) {
            for ((key, value) in nested) {
                if (key is String && value != null) {
                    @Suppress("UNCHECKED_CAST")
                    details[key] = value
                }
            }
        }
        if (errorObj["code"] == ApiException.QUOTA_EXCEEDED_ON_RESTORE) {
            for (key in quotaFlatKeys) {
                val value = errorObj[key]
                if (value != null) {
                    @Suppress("UNCHECKED_CAST")
                    details[key] = value
                }
            }
        }
        return details.takeIf { it.isNotEmpty() }
    }

    /**
     * Parses `error.fields`. Supports the real object list (`[{field,message}]`)
     * and the legacy string list (`["email","name"]`). Field messages are
     * sanitized; missing/invalid entries are skipped.
     */
    private fun extractFields(errorObj: Map<*, *>?): List<FieldError>? {
        if (errorObj == null) return null
        val raw = errorObj["fields"] ?: return null
        if (raw !is List<*>) return null
        val result = raw.mapNotNull { item ->
            when (item) {
                is Map<*, *> -> {
                    val field = item["field"]?.toString()?.takeIf { it.isNotBlank() }
                    val message = sanitize((item["message"] as? String)?.takeIf { it.isNotBlank() })
                    field?.let { FieldError(it, message) }
                }

                is String -> FieldError(item, null) // legacy string list
                else -> null
            }
        }
        return result.takeIf { it.isNotEmpty() }
    }
}

/**
 * Converts a parsed [ErrorResponse] into the [ApiException] callers expect. The
 * visible message is the sanitized server message when present, otherwise the
 * localized status-based fallback from [localizedFallback]. The raw server text
 * is preserved separately in [ApiException.backendMessage].
 */
fun ErrorResponse.toApiException(localizedFallback: () -> String): ApiException {
    val display = message?.takeIf { it.isNotBlank() } ?: localizedFallback()
    return ApiException(
        code = code,
        details = details,
        httpStatus = statusCode,
        backendMessage = backendMessage,
        message = display
    )
}
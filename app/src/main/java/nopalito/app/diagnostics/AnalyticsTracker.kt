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

package nopalito.app.diagnostics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Pure validation and sanitization for Analytics event names and parameters.
 * Kept separate from the tracker so it stays unit-testable on the JVM.
 */
object AnalyticsEventValidator {

    private val eventName = Regex("""^[a-z_][a-z0-9_]{1,39}$""")

    fun isValidName(name: String): Boolean = eventName.matches(name)

    fun sanitizeParam(value: String, maxLength: Int = 100): String =
        PiiSanitizer.sanitize(value, maxLength)
}

/** Business event names used by the app. Lowercase with underscores only. */
object AnalyticsEvents {
    const val DOCUMENT_CREATED = "document_created"
    const val SCAN_COMPLETED = "scan_completed"
    const val OCR_COMPLETED = "ocr_completed"
    const val DOCUMENT_EXPORTED = "document_exported"
    const val SYNC_FAILED = "sync_failed"
    const val SUBSCRIPTION_STARTED = "subscription_started"
}

/**
 * Central, crash-safe gateway to Firebase Analytics.
 *
 * Only explicit business events are logged here — informational logs are
 * never mirrored automatically. Every parameter is sanitized and bounded so
 * no personal data (emails, tokens, OCR text, file names, document content)
 * can reach Analytics; typed helper methods only accept primitives and
 * closed vocabularies. All failures are swallowed after a Logcat line, so
 * Analytics can never break the app.
 */
class AnalyticsTracker(private val appContext: Context) {

    private val tag = "Analytics"

    fun logEvent(name: String, params: Map<String, Any?> = emptyMap()) {
        try {
            if (!AnalyticsEventValidator.isValidName(name)) {
                Log.d(tag, "dropped invalid event name=$name")
                return
            }
            val bundle = Bundle()
            for ((key, value) in params) {
                if (!AnalyticsEventValidator.isValidName(key)) continue
                when (value) {
                    null -> Unit
                    is Int -> bundle.putLong(key, value.toLong().coerceAtLeast(0))
                    is Long -> bundle.putLong(key, value.coerceAtLeast(0))
                    is Boolean -> bundle.putLong(key, if (value) 1L else 0L)
                    is Double -> bundle.putDouble(key, value)
                    is String -> bundle.putString(
                        key,
                        AnalyticsEventValidator.sanitizeParam(value)
                    )
                    else -> bundle.putString(
                        key,
                        AnalyticsEventValidator.sanitizeParam(value.toString())
                    )
                }
            }
            try {
                FirebaseAnalytics.getInstance(appContext).logEvent(name, bundle)
            } catch (e: Exception) {
                Log.d(tag, "sdk unavailable event=$name reason=${e.javaClass.simpleName}")
                return
            }
            Log.d(tag, "event=$name params=${bundle.keySet().sorted()}")
        } catch (e: Exception) {
            try {
                Log.d(tag, "log failed: ${e.javaClass.simpleName}")
            } catch (_: Exception) {
            }
        }
    }

    fun documentCreated(pageCount: Int, hasOcr: Boolean, format: String) {
        logEvent(
            AnalyticsEvents.DOCUMENT_CREATED,
            mapOf(
                "page_count" to pageCount,
                "has_ocr" to hasOcr,
                "format" to format.lowercase()
            )
        )
    }

    fun scanCompleted(source: String, pages: Int) {
        logEvent(
            AnalyticsEvents.SCAN_COMPLETED,
            mapOf("source" to source.lowercase(), "pages" to pages)
        )
    }

    fun ocrCompleted(wordCount: Int) {
        logEvent(AnalyticsEvents.OCR_COMPLETED, mapOf("word_count" to wordCount))
    }

    fun documentExported(format: String, pageCount: Int, destination: String) {
        logEvent(
            AnalyticsEvents.DOCUMENT_EXPORTED,
            mapOf(
                "format" to format.lowercase(),
                "page_count" to pageCount,
                "destination" to destination.lowercase()
            )
        )
    }

    fun syncFailed(operation: String, errorKind: String) {
        logEvent(
            AnalyticsEvents.SYNC_FAILED,
            mapOf(
                "operation" to operation.lowercase(),
                "error_kind" to errorKind.take(64)
            )
        )
    }

    fun subscriptionStarted(plan: String) {
        logEvent(
            AnalyticsEvents.SUBSCRIPTION_STARTED,
            mapOf("plan" to plan.lowercase().take(64))
        )
    }
}
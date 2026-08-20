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

package nopalito.app.push

/**
 * Sanitizer for notification payload text (FCM data-only messages rendered by
 * [NopalitoScanMessagingService]). The payload is untrusted server text, so it is
 * sanitized BEFORE on-device translation (a URL/token is never sent to the
 * translator) and the translator output is sanitized AGAIN before display.
 *
 * [sanitizeNotificationText] delegates to the same shared core as the
 * maintenance banner ([sanitizeMaintenanceText]) so both flows share one
 * deterministic, tested sanitization contract.
 */
fun sanitizeNotificationText(value: String?): String = sanitizeUntrustedText(value)

/**
 * Localization pipeline for one notification field:
 *
 * `raw → sanitize → translate → sanitize final → string`
 *
 * The fallback is NEVER raw server text: when translation fails (or returns the
 * original text), the result is still the sanitized text. Blank input yields
 * blank output (the caller skips the tray notification in that case).
 */
suspend fun localizeNotificationText(value: String?, translate: suspend (String) -> String): String {
    val safe = sanitizeNotificationText(value)
    if (safe.isEmpty()) return ""
    return sanitizeNotificationText(translate(safe))
}
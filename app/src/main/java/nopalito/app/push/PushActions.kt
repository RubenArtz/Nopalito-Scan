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

import androidx.core.net.toUri
import nopalito.app.push.PushActions.OPEN_APP

/**
 * Deep-link actions the panel can attach to a notification. When the user taps
 * the notification the app navigates to the matching screen. `open_app` is the
 * default (just launch/raise the app); `open_url` opens a browser with the URL
 * carried in the `url` data key.
 *
 * Whitelist shared with the backend (pushValidator): values here MUST match the
 * strings the panel sends in `clickAction`.
 */
object PushActions {

    const val OPEN_APP = "open_app"
    const val OPEN_CLOUD = "open_cloud"
    const val OPEN_SETTINGS = "open_settings"
    const val OPEN_STORAGE = "open_storage"
    const val OPEN_QR_HISTORY = "open_qr_history"
    const val OPEN_TOOLS = "open_tools"
    const val OPEN_URL = "open_url"

    /** Intent extra holding the action on the content PendingIntent. */
    const val EXTRA_PUSH_ACTION = "nopalito.push.action"

    /** Intent extra / FCM data key carrying the browser URL (open_url). */
    const val EXTRA_URL = "url"

    /** Intent action prefix FCM uses for auto-shown notifications (Android). */
    const val INTENT_ACTION_PREFIX = "NOPALITO.PUSH."

    /**
     * Maps the raw action (from data.click_action or intent.action) to a
     * canonical [OPEN_APP]-style value, or null when it is not a push action.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val value = raw.removePrefix(INTENT_ACTION_PREFIX)
        return if (all.contains(value)) value else null
    }

    private val all = setOf(
        OPEN_APP,
        OPEN_CLOUD,
        OPEN_SETTINGS,
        OPEN_STORAGE,
        OPEN_QR_HISTORY,
        OPEN_TOOLS,
        OPEN_URL,
    )

    /** Only well-formed http(s) URLs are ever opened (never custom schemes). */
    fun isHttpUrl(raw: String?): Boolean {
        return runCatching {
            val uri = raw!!.toUri()
            uri.scheme == "https" || uri.scheme == "http"
        }.getOrDefault(false)
    }
}
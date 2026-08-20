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

import nopalito.app.R
import nopalito.app.ui.screens.cloud.screens.formatCloudFileSize

/**
 * Localized tray content for a PROACTIVE cloud notification (data-only FCM).
 *
 * Contract shared with the backend (quotaMonitor / notificationService):
 * the message carries NO title/body text — only `type` plus semantic data:
 *   type ∈ {quota_80, quota_95, quota_100, quota_reminder, rejected_file,
 *           inactive_account, upload_error, test}
 *   data keys: event, threshold, percent, used_bytes, quota_bytes, days, name
 *   click_action: open_storage (quota events) / open_app (the rest)
 *
 * The app renders the tray notification with its own Android string
 * resources (one per locale), so the user always reads the phone's language.
 * Bytes are formatted on-device (same helper as the storage screen).
 */
data class ProactiveNotificationContent(
    val titleRes: Int,
    val bodyRes: Int,
    val titleArgs: List<Any> = emptyList(),
    val bodyArgs: List<Any> = emptyList(),
    val clickAction: String,
)

object ProactiveNotificationMapper {

    fun contentFor(type: String?, data: Map<String, String>): ProactiveNotificationContent? {
        return when (type) {
            "quota_80" -> quotaContent(
                R.string.notif_storage_80_title,
                R.string.notif_storage_80_body,
                data,
                PushActions.OPEN_STORAGE,
            )

            "quota_95" -> quotaContent(
                R.string.notif_storage_95_title,
                R.string.notif_storage_95_body,
                data,
                PushActions.OPEN_STORAGE,
            )

            "quota_100" -> quotaContent(
                R.string.notif_storage_100_title,
                R.string.notif_storage_100_body,
                data,
                PushActions.OPEN_STORAGE,
            )

            "quota_reminder" -> quotaContent(
                R.string.notif_storage_reminder_title,
                R.string.notif_storage_reminder_body,
                data,
                PushActions.OPEN_STORAGE,
            )

            "rejected_file" -> singleArgContent(
                R.string.notif_storage_rejected_title,
                R.string.notif_storage_rejected_body,
                data["name"],
                PushActions.OPEN_APP,
            )

            "inactive_account" -> singleArgContent(
                R.string.notif_account_inactive_title,
                R.string.notif_account_inactive_body,
                data["days"],
                PushActions.OPEN_APP,
            )

            "upload_error" -> singleArgContent(
                R.string.notif_upload_error_title,
                R.string.notif_upload_error_body,
                data["name"],
                PushActions.OPEN_APP,
            )

            "test" -> ProactiveNotificationContent(
                titleRes = R.string.notif_test_title,
                bodyRes = R.string.notif_test_body,
                clickAction = PushActions.OPEN_APP,
            )

            else -> null
        }
    }

    private fun quotaContent(
        titleRes: Int,
        bodyRes: Int,
        data: Map<String, String>,
        clickAction: String,
    ): ProactiveNotificationContent {
        val percent = data["percent"]
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { it.toIntOrNull() != null }
            ?.let { "$it%" }
            ?: "?"
        val used = formatCloudFileSize(data["used_bytes"]?.toLongOrNull())
        val quota = formatCloudFileSize(data["quota_bytes"]?.toLongOrNull())
        return ProactiveNotificationContent(
            titleRes = titleRes,
            bodyRes = bodyRes,
            bodyArgs = listOf(percent, used, quota),
            clickAction = clickAction,
        )
    }

    private fun singleArgContent(
        titleRes: Int,
        bodyRes: Int,
        rawArg: String?,
        clickAction: String,
    ): ProactiveNotificationContent {
        return ProactiveNotificationContent(
            titleRes = titleRes,
            bodyRes = bodyRes,
            bodyArgs = listOf(rawArg?.takeIf { it.isNotBlank() } ?: "?"),
            clickAction = clickAction,
        )
    }
}
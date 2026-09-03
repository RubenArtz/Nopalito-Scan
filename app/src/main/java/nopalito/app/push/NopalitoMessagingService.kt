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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nopalito.app.MainActivity
import nopalito.app.R
import nopalito.app.data.LocalMaintenanceState
import nopalito.app.data.MaintenanceEvent
import nopalito.app.data.MaintenanceEventBus
import nopalito.app.data.MaintenanceStateStore
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "PushMessaging"
private const val CHANNEL_ID = "push_general"

/**
 * FCM messaging service.
 *
 *  - [onNewToken]: FCM rotated this install's registration token → tell the
 *    backend (authenticated endpoint). No secret leaves the device: the token
 *    is useless without the Bearer JWT + app secret.
 *  - [onMessageReceived]: messages arrive as DATA-ONLY (no notification
 *    payload), so the app ALWAYS builds the tray notification itself. That is
 *    what makes on-device translation possible — FCM-rendered notification
 *    payloads would show the server text verbatim in the phone's tray.
 *
 * Messages handled here:
 *  - `type: maintenance`  → maintenance lifecycle events (created /
 *    pre_notification / activated / completed / cancelled).
 *  - `type: push`         → broadcasts from the admin panel (title/body text).
 *  - Proactive cloud events (`quota_80` / `quota_95` / `quota_100` /
 *    `quota_reminder` / `rejected_file` / `inactive_account` / `upload_error`
 *    / `test`) → data-only, NO title/body: the tray notification is built from
 *    the device's own string resources by [ProactiveNotificationMapper].
 *  - Legacy messages with a plain notification payload are still supported.
 *
 * All text sent by the server goes through the defensive pipeline
 * `raw → sanitize → translate (ML Kit) → sanitize final → render`; the fallback
 * when translation is not possible is the sanitized text, never the raw
 * original. Admin pushes (`type: push`) keep URLs/paths (authenticated channel);
 * maintenance text keeps the strict contract. Every known maintenance event has
 * a localized fallback title and a generic localized body, so a tray entry can
 * never end up blank. The translation target and all resource lookups follow
 * the app-selected language (AppLocaleOverride), falling back to English.
 * No full payloads or secrets are ever logged — only types, lengths and flags.
 */
@Suppress("DEPRECATION")
class NopalitoScanMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fallback id when FCM does not provide a message id (data-only). */
    private val fallbackId = AtomicInteger(0)

    private val tokenSync: FcmTokenSync
        get() = FcmTokenSync.getInstance(applicationContext)

    @Deprecated("Deprecated in Java")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        tokenSync.onNewToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val notification = remoteMessage.notification

        // Maintenance lifecycle events (data-only): FIRST sync the persisted
        // operational state (Phase 2: FCM is the primary change channel — the
        // store guards against old/duplicate versions internally), then build
        // a localized tray notification from the event type + server text.
        // State sync and tray rendering are independent: a discarded push
        // (old version) never suppresses the notification, and a skipped tray
        // (permission revoked) never blocks the state update.
        if (data["type"] == "maintenance") {
            Log.i(TAG, "onMessageReceived: maintenance event=${data["event"]?.take(30)}")
            scope.launch {
                val applied = MaintenanceStateStore.getInstance(applicationContext)
                    .updateFromFcm(data)
                if (applied) {
                    MaintenanceEventBus.tryEmit(
                        MaintenanceEvent(
                            type = data["event"].orEmpty(),
                            version = data["version"]?.toLongOrNull() ?: 0L,
                            source = LocalMaintenanceState.SOURCE_FCM,
                            maintenanceId = data["maintenance_id"],
                            correlationId = data["correlation_id"]?.trim()
                                ?.takeIf { it.isNotEmpty() },
                        )
                    )
                }
            }
            ensureChannel()
            showMaintenanceNotification(remoteMessage.messageId, data)
            return
        }

        // Proactive cloud events (data-only, no title/body): the tray
        // notification is rendered from the device's own string resources.
        if (ProactiveNotificationMapper.contentFor(data["type"], data) != null) {
            Log.i(TAG, "onMessageReceived: proactive type=${data["type"]}")
            ensureChannel()
            showProactiveNotification(remoteMessage.messageId, data)
            return
        }

        // Generic push: prefer the data payload (data-only deliveries from the
        // backend), fall back to a legacy notification payload. Either way the
        // text goes through sanitize -> translate -> sanitize. Blank strings are
        // treated as absent: an empty title/body must not render an empty tray
        // entry.
        val notifTitle = data["title"]?.takeIf { it.isNotBlank() } ?: notification?.title
        val notifBody = data["body"]?.takeIf { it.isNotBlank() } ?: notification?.body
        if (notifTitle != null || notifBody != null) {
            Log.i(
                TAG,
                "onMessageReceived: generic push (title=${
                    sanitizeNotificationText(notifTitle).take(40)
                })"
            )
            ensureChannel()
            val clickAction = PushActions.normalize(
                data["click_action"] ?: notification?.clickAction
            )
            showPushNotification(
                messageId = remoteMessage.messageId,
                title = notifTitle,
                body = notifBody,
                clickAction = clickAction,
                url = data[PushActions.EXTRA_URL],
                langHint = data["lang"],
            )
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            localizedString(R.string.push_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = localizedString(R.string.push_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Resolves a string resource in the app-selected language (in-app override
     * via [AppLocaleOverride]); the extension falls back to the platform
     * default resolution when the override cannot be applied. English is the
     * base catalog, so it remains the ultimate fallback.
     */
    private fun localizedString(resId: Int, vararg args: Any): String =
        stringFor(resId, AppLocaleOverride.locale, *args)

    /**
     * Observability: reports when sanitization changed a field's length
     * (before → after character counts). Lengths only — never the text itself.
     */
    private fun logSanitizerEffect(field: String, raw: String?, pushChannel: Boolean) {
        if (raw.isNullOrEmpty()) return
        val safe = if (pushChannel) sanitizePushText(raw) else sanitizeNotificationText(raw)
        if (safe.length != raw.length) {
            Log.i(TAG, "sanitizer adjusted $field: ${raw.length} -> ${safe.length} chars")
        }
    }

    /**
     * Shows a localized notification for a generic push (admin panel or any
     * data-only message carrying title/body). Translation runs in the
     * background; the sanitized server text is the fallback.
     */
    private fun showPushNotification(
        messageId: String?,
        title: String?,
        body: String?,
        clickAction: String?,
        url: String?,
        langHint: String?,
    ) {
        // Respect the user's choice on Android 13+ (POST_NOTIFICATIONS).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            Log.d(TAG, "Post notifications permission not granted; skipping tray notification")
            return
        }

        val rawTitle = title.orEmpty()
        val rawBody = body.orEmpty()
        logSanitizerEffect("push.title", rawTitle, pushChannel = true)
        logSanitizerEffect("push.body", rawBody, pushChannel = true)

        scope.launch {
            // raw -> sanitize -> translate -> sanitize final; never raw.
            // Push channel: URLs/paths are preserved (authenticated sender).
            val finalTitle =
                localizePushText(rawTitle) { TranslationHelper.translate(it, langHint) }
            val finalBody = localizePushText(rawBody) { TranslationHelper.translate(it, langHint) }

            if (finalTitle.isBlank() && finalBody.isBlank()) {
                Log.w(TAG, "Notification has no text content; skipping tray notification")
                return@launch
            }
            Log.i(
                TAG,
                "push notification: sourceLangHint=$langHint mlkitTarget=${TranslationHelper.getDeviceLanguageCode()} title=${
                    finalTitle.take(60)
                }"
            )

            // If the notification brings a valid http(s) URL, we open the browser by tapping on it.
            val openBrowser = url != null && PushActions.isHttpUrl(url)

            val contentIntent: PendingIntent = if (openBrowser) {
                PendingIntent.getActivity(
                    this@NopalitoScanMessagingService,
                    notificationRequestCode(messageId),
                    Intent(Intent.ACTION_VIEW, url.toUri()),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                // Default behavior: launch the main activity of the app
                val intent =
                    Intent(this@NopalitoScanMessagingService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        clickAction?.let {
                            putExtra(PushActions.EXTRA_PUSH_ACTION, it)
                        }
                        // Bring the URL in case any other code needs it
                        if (url != null) {
                            putExtra(PushActions.EXTRA_URL, url)
                        }
                    }
                PendingIntent.getActivity(
                    this@NopalitoScanMessagingService,
                    notificationRequestCode(messageId),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

            val builder = NotificationCompat.Builder(this@NopalitoScanMessagingService, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(finalTitle.ifBlank { null })
                .setContentText(finalBody.ifBlank { null })
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            try {
                NotificationManagerCompat.from(this@NopalitoScanMessagingService)
                    .notify(notificationId(messageId), builder.build())
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS revoked between the check and notify.
                Log.w(TAG, "Could not show notification: permission revoked")
            }
        }
    }

    /**
     * Shows a localized maintenance notification from a data-only FCM message.
     * Translates the backend text (written by the admin in any language) to
     * the app's selected language using ML Kit on-device translation. Every
     * field goes through sanitize -> translate -> sanitize final; never raw.
     * Known lifecycle events always resolve a localized title resource and a
     * generic localized body, so the tray entry is never blank.
     */
    private fun showMaintenanceNotification(messageId: String?, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            Log.d(
                TAG,
                "Post notifications permission not granted; skipping maintenance notification"
            )
            return
        }

        val event = data["event"] ?: ""
        Log.i(TAG, "maintenance message received: event=${event.ifBlank { "<missing>" }.take(30)}")
        val rawTitle = data["title"] ?: ""
        val rawMessage = data["message"] ?: ""
        val rawReason = data["reason"] ?: ""
        val endsAt = data["ends_at"] ?: ""
        val langHint = data["lang"]

        logSanitizerEffect("maint.title", rawTitle, pushChannel = false)
        logSanitizerEffect("maint.message", rawMessage, pushChannel = false)
        logSanitizerEffect("maint.reason", rawReason, pushChannel = false)

        scope.launch {
            val finalTitle =
                localizeNotificationText(rawTitle) { TranslationHelper.translate(it, langHint) }
            val finalMessage =
                localizeNotificationText(rawMessage) { TranslationHelper.translate(it, langHint) }
            val finalReason =
                localizeNotificationText(rawReason) { TranslationHelper.translate(it, langHint) }

            // Localized lifecycle titles (English base catalog + translated
            // variants): every known event has a local fallback so a missing or
            // empty server title can never produce a titleless tray entry.
            val eventTitleRes = when (event) {
                "created" -> R.string.cloud_notif_created
                "pre_notification" -> R.string.cloud_notif_pre_notification
                "activated" -> R.string.cloud_notif_activated
                "completed" -> R.string.cloud_notif_completed
                "cancelled" -> R.string.cloud_notif_cancelled
                else -> null
            }

            // Concluded events lead with the localized event title ("Maintenance
            // finished"); their server title moves to the body. Every other
            // event prefers the server title and falls back to the localized
            // event title, then to the generic maintenance title.
            val concluded = event == "completed" || event == "cancelled"
            val notifTitle: String = when {
                concluded && eventTitleRes != null -> localizedString(eventTitleRes)
                finalTitle.isNotBlank() -> finalTitle
                eventTitleRes != null -> localizedString(eventTitleRes)
                else -> localizedString(R.string.cloud_maint_generic_title)
            }
            val titleSource =
                if (concluded || finalTitle.isBlank()) "local-resource" else "server-text"

            // The body holds the user-facing message (or reason) + end time;
            // concluded events also carry the server title. If everything is
            // blank, a generic localized body is used instead of skipping the
            // notification.
            val endFormatted = formatDateTimeForLocale(endsAt)
            val notifBody = buildString {
                append(finalMessage.ifBlank { finalReason })
                if (finalMessage.isNotBlank() && finalReason.isNotBlank()) {
                    append(" — ").append(finalReason)
                }
                if (concluded && finalTitle.isNotBlank()) {
                    if (isNotEmpty()) append(" — ")
                    append(finalTitle)
                }
                if (endFormatted.isNotBlank()) {
                    if (isNotEmpty()) append(" — ")
                    append(endFormatted)
                }
            }.ifBlank { localizedString(R.string.cloud_notif_generic_body) }

            Log.i(
                TAG,
                "maintenance notification: event=${event.take(30)} titleSource=$titleSource len=${notifTitle.length}",
            )

            val contentIntent = PendingIntent.getActivity(
                this@NopalitoScanMessagingService,
                notificationRequestCode(messageId),
                Intent(this@NopalitoScanMessagingService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(PushActions.EXTRA_PUSH_ACTION, "open_app")
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            if (notifTitle.isBlank() && notifBody.isBlank()) {
                Log.w(
                    TAG,
                    "Maintenance notification has no text content; skipping tray notification"
                )
                return@launch
            }

            val builder = NotificationCompat.Builder(this@NopalitoScanMessagingService, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(notifTitle.ifBlank { null })
                .setContentText(notifBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notifBody))
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            try {
                NotificationManagerCompat.from(this@NopalitoScanMessagingService)
                    .notify(notificationId(messageId), builder.build())
            } catch (_: SecurityException) {
                Log.w(TAG, "Could not show maintenance notification: permission revoked")
            }
        }
    }

    /**
     * Shows a proactive cloud notification (quota / rejected file / inactive
     * account / upload error / test). The text comes from the app's string
     * resources in the SELECTED app locale — the backend never sends title/body
     * for these events, only the semantic data keys. Pure local-resource path
     * (no ML Kit involved).
     */
    private fun showProactiveNotification(messageId: String?, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            Log.d(TAG, "Post notifications permission not granted; skipping proactive notification")
            return
        }

        val content = ProactiveNotificationMapper.contentFor(data["type"], data) ?: return
        val bodyArgs = content.bodyArgs.toTypedArray()
        val titleArgs = content.titleArgs.toTypedArray()
        val bodyText = localizedString(content.bodyRes, *bodyArgs)
        Log.i(TAG, "proactive notification: type=${data["type"]} titleSource=local-resource")

        val contentIntent = PendingIntent.getActivity(
            this@NopalitoScanMessagingService,
            notificationRequestCode(messageId),
            Intent(this@NopalitoScanMessagingService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(PushActions.EXTRA_PUSH_ACTION, content.clickAction)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this@NopalitoScanMessagingService, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification)
            .setContentTitle(localizedString(content.titleRes, *titleArgs))
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        try {
            NotificationManagerCompat.from(this@NopalitoScanMessagingService)
                .notify(notificationId(messageId), builder.build())
        } catch (_: SecurityException) {
            Log.w(TAG, "Could not show proactive notification: permission revoked")
        }
    }

    /** Stable per-message id: FCM message id when available, else a counter. */
    private fun notificationId(messageId: String?): Int =
        messageId?.hashCode() ?: fallbackId.incrementAndGet()

    /** Request code for PendingIntents: must be unique per notification. */
    private fun notificationRequestCode(messageId: String?): Int = notificationId(messageId)

    /**
     * Formats an ISO datetime string to a locale-aware display format.
     */
    private fun formatDateTimeForLocale(isoString: String): String {
        if (isoString.isBlank()) return ""
        return try {
            val instant = java.time.Instant.parse(isoString)
            val zoneId = java.time.ZoneId.systemDefault()
            val zonedDateTime = instant.atZone(zoneId)
            val formatter = java.time.format.DateTimeFormatter.ofLocalizedDateTime(
                java.time.format.FormatStyle.MEDIUM,
                java.time.format.FormatStyle.SHORT
            )
            zonedDateTime.format(formatter)
        } catch (_: Exception) {
            isoString
        }
    }
}
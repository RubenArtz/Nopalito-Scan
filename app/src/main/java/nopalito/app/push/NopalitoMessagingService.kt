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
 *  - Legacy messages with a plain notification payload are still supported.
 *
 * All text sent by the server goes through the defensive pipeline
 * `raw → sanitize → translate (ML Kit) → sanitize final → render`; the fallback
 * when translation is not possible is the sanitized text, never the raw
 * original. No full payloads or secrets are ever logged.
 */
@Suppress("DEPRECATION")
class FairScanMessagingService : FirebaseMessagingService() {

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

        // Maintenance lifecycle events (data-only): build a localized
        // notification from the event type + server text.
        if (data["type"] == "maintenance") {
            Log.i(TAG, "onMessageReceived: maintenance event=${data["event"]?.take(30)}")
            ensureChannel()
            showMaintenanceNotification(remoteMessage.messageId, data)
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
            Log.i(TAG, "onMessageReceived: generic push (title=${sanitizeNotificationText(notifTitle).take(40)})")
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
            getString(R.string.push_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.push_channel_description)
        }
        manager.createNotificationChannel(channel)
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

        scope.launch {
            // raw -> sanitize -> translate -> sanitize final; never raw.
            val finalTitle = localizeNotificationText(rawTitle) { TranslationHelper.translate(it, langHint) }
            val finalBody = localizeNotificationText(rawBody) { TranslationHelper.translate(it, langHint) }

            if (finalTitle.isBlank() && finalBody.isBlank()) {
                Log.w(TAG, "Notification has no text content; skipping tray notification")
                return@launch
            }
            Log.i(TAG, "push notification: sourceLangHint=$langHint -> title=${finalTitle.take(60)}")

            // If the notification brings a valid http(s) URL, we open the browser by tapping on it.
            val openBrowser = url != null && PushActions.isHttpUrl(url)

            val contentIntent: PendingIntent = if (openBrowser) {
                PendingIntent.getActivity(
                    this@FairScanMessagingService,
                    notificationRequestCode(messageId),
                    Intent(Intent.ACTION_VIEW, url.toUri()),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                // Default behavior: launch the main activity of the app
                val intent = Intent(this@FairScanMessagingService, MainActivity::class.java).apply {
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
                    this@FairScanMessagingService,
                    notificationRequestCode(messageId),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

            val builder = NotificationCompat.Builder(this@FairScanMessagingService, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(finalTitle.ifBlank { null })
                .setContentText(finalBody.ifBlank { null })
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            try {
                NotificationManagerCompat.from(this@FairScanMessagingService)
                    .notify(notificationId(messageId), builder.build())
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS revoked between the check and notify.
                Log.w(TAG, "Could not show notification: permission revoked")
            }
        }
    }

    /**
     * Shows a localized maintenance notification from a data-only FCM message.
     * Translates the backend text (written by the admin in any language) to the
     * user's device language using ML Kit on-device translation. Every field
     * goes through sanitize -> translate -> sanitize final; never raw.
     */
    private fun showMaintenanceNotification(messageId: String?, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            Log.d(TAG, "Post notifications permission not granted; skipping maintenance notification")
            return
        }

        val event = data["event"] ?: return
        val rawTitle = data["title"] ?: ""
        val rawMessage = data["message"] ?: ""
        val rawReason = data["reason"] ?: ""
        val endsAt = data["ends_at"] ?: ""
        val langHint = data["lang"]

        scope.launch {
            val finalTitle = localizeNotificationText(rawTitle) { TranslationHelper.translate(it, langHint) }
            val finalMessage = localizeNotificationText(rawMessage) { TranslationHelper.translate(it, langHint) }
            val finalReason = localizeNotificationText(rawReason) { TranslationHelper.translate(it, langHint) }

            // Concluded events (completed/cancelled) get a localized event
            // title ("Maintenance finished") instead of repeating the
            // maintenance title; the maintenance title then moves to the body.
            val eventTitle = when (event) {
                "completed" -> getString(R.string.cloud_notif_completed)
                "cancelled" -> getString(R.string.cloud_notif_cancelled)
                else -> null
            }

            // The event title is shown once as the notification title; the
            // body holds the user-facing message (or reason) + end time.
            val notifBody = when (event) {
                "created", "pre_notification", "activated" -> buildString {
                    append(finalMessage.ifBlank { finalReason })
                    if (finalMessage.isNotBlank() && finalReason.isNotBlank()) {
                        append(" — ").append(finalReason)
                    }
                    val endFormatted = formatDateTimeForLocale(endsAt)
                    if (endFormatted.isNotBlank()) {
                        if (isNotEmpty()) append(" — ")
                        append(endFormatted)
                    }
                }

                "completed" -> listOf(
                    finalTitle,
                    finalMessage,
                    finalReason,
                    formatDateTimeForLocale(endsAt),
                ).filter { it.isNotBlank() }.joinToString(" — ")

                "cancelled" -> listOf(
                    finalTitle,
                    finalMessage,
                    finalReason,
                ).filter { it.isNotBlank() }.joinToString(" — ")

                else -> return@launch
            }
            if (notifBody.isBlank()) {
                Log.w(TAG, "Maintenance notification has no text content; skipping tray notification")
                return@launch
            }
            Log.i(TAG, "maintenance notification: event=${event.take(30)} title=${(eventTitle ?: finalTitle).take(60)}")

            val contentIntent = PendingIntent.getActivity(
                this@FairScanMessagingService,
                notificationRequestCode(messageId),
                Intent(this@FairScanMessagingService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(PushActions.EXTRA_PUSH_ACTION, "open_app")
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val builder = NotificationCompat.Builder(this@FairScanMessagingService, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle((eventTitle ?: finalTitle).ifBlank { null })
                .setContentText(notifBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notifBody))
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            try {
                NotificationManagerCompat.from(this@FairScanMessagingService)
                    .notify(notificationId(messageId), builder.build())
            } catch (_: SecurityException) {
                Log.w(TAG, "Could not show maintenance notification: permission revoked")
            }
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
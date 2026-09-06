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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Block: 8C notification-regression audit — structural isolation tests.
 *
 * Reads the Kotlin sources directly (JVM, no Android Context) and asserts that
 * the push notification flow and the maintenance localization flow are fully
 * separated:
 *
 *  - push classes never reference [MaintenanceLocalizer] or the maintenance
 *    sanitizer (so 8A/8C could not have changed notification text);
 *  - the maintenance path never renders notifications;
 *  - the new contract fields (`code`, `title_key`, `message_key`,
 *    `reason_key`) never reach FCM data payload reads.
 *
 * Working directory is the `app` module.
 */
class PushFlowIsolationTest {

    private val mainSrc = File("src/main/java")

    private fun sourceText(relativePath: String): String =
        File(mainSrc, relativePath).readText()

    private val pushClasses = listOf(
        "nopalito/app/push/NopalitoMessagingService.kt",
        "nopalito/app/push/PushActions.kt",
        "nopalito/app/push/FcmTokenSync.kt",
        "nopalito/app/push/DeviceIdentity.kt",
        "nopalito/app/push/TranslationHelper.kt",
    )

    private val maintenanceClasses = listOf(
        "nopalito/app/ui/screens/cloud/data/MaintenanceLocalizer.kt",
        "nopalito/app/ui/screens/cloud/screens/CloudMaintenanceScreen.kt",
    )

    // ---- 1. Push flow does not use the maintenance localization stack ----

    @Test
    fun pushClassesNeverReferenceMaintenanceLocalizer() {
        for (path in pushClasses) {
            val text = sourceText(path)
            assertFalse(
                "$path references MaintenanceLocalizer",
                text.contains("MaintenanceLocalizer")
            )
        }
    }

    @Test
    fun pushClassesNeverUseTheMaintenanceSanitizer() {
        for (path in pushClasses) {
            val text = sourceText(path)
            assertFalse(
                "$path uses sanitizeMaintenanceText",
                text.contains("sanitizeMaintenanceText")
            )
            assertFalse(
                "$path uses localizeMaintenanceText",
                text.contains("localizeMaintenanceText")
            )
            assertFalse("$path uses renderMaintenanceText", text.contains("renderMaintenanceText"))
        }
    }

    @Test
    fun sanitizerFileDoesNotTouchNotifications() {
        val text = sourceText("nopalito/app/push/MaintenanceTextSanitizer.kt")
        for (token in listOf(
            "RemoteMessage",
            "NotificationCompat",
            "FirebaseMessagingService",
            "NotificationManager"
        )) {
            assertFalse("MaintenanceTextSanitizer.kt references $token", text.contains(token))
        }
    }

    // ---- 2. Maintenance path does not render notifications ----

    @Test
    fun maintenancePathNeverRendersNotifications() {
        for (path in maintenanceClasses) {
            val text = sourceText(path)
            for (token in listOf(
                "RemoteMessage",
                "NotificationCompat",
                "FirebaseMessagingService",
                "showPushNotification",
                "showMaintenanceNotification"
            )) {
                assertFalse("$path references $token", text.contains(token))
            }
        }
    }

    // ---- 3. New contract fields never reach FCM data payloads ----

    @Test
    fun contractFieldsDoNotReachPushPayloadReads() {
        val service = sourceText("nopalito/app/push/NopalitoMessagingService.kt")
        for (token in listOf("title_key", "message_key", "reason_key", "data[\"code\"]")) {
            assertFalse("NopalitoMessagingService.kt reads $token", service.contains(token))
        }
    }

    // ---- 4. Push payload keys are only the documented ones ----

    @Test
    fun pushPayloadKeysMatchDocumentedContract() {
        val service = sourceText("nopalito/app/push/NopalitoMessagingService.kt")
        val documented = listOf(
            "data[\"type\"]", "data[\"event\"]", "data[\"title\"]",
            "data[\"message\"]", "data[\"reason\"]", "data[\"ends_at\"]",
            "data[\"lang\"]", "data[\"body\"]", "data[\"click_action\"]",
            "data[PushActions.EXTRA_URL]",
        )
        for (key in documented) {
            assertTrue("expected push data key $key", service.contains(key))
        }
    }

    // ---- 5. 8D: the push flow sanitizes and never falls back to raw text ----

    @Test
    fun pushServiceUsesNotificationSanitizationPipeline() {
        val service = sourceText("nopalito/app/push/NopalitoMessagingService.kt")
        assertTrue(
            "service must use localizeNotificationText",
            service.contains("localizeNotificationText")
        )
        assertTrue(
            "service must use sanitizeNotificationText",
            service.contains("sanitizeNotificationText")
        )
    }

    @Test
    fun pushServiceNeverFallsBackToRawText() {
        val service = sourceText("nopalito/app/push/NopalitoMessagingService.kt")
        assertFalse("raw fallback via ifBlank { raw }", service.contains("ifBlank { raw"))
    }

    @Test
    fun pushServiceNeverLogsFullPayloads() {
        val service = sourceText("nopalito/app/push/NopalitoMessagingService.kt")
        assertFalse("full raw body in log", service.contains("body=\${notifBody}"))
        assertFalse("full raw title in log", service.contains("title=\${notifTitle}"))
        assertFalse("raw message in log", service.contains("translatedMessage"))
    }

    @Test
    fun notificationSanitizerDelegatesToSharedCore() {
        val text = sourceText("nopalito/app/push/NotificationTextSanitizer.kt")
        assertTrue(
            "sanitizeNotificationText must delegate to the shared core",
            text.contains("sanitizeUntrustedText")
        )
    }
}
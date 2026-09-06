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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proactive-notification contract tests (JVM, no Android Context):
 * type + data keys → string resources + args + click action.
 *
 * The resource ids only prove the mapping chose the right keys; the actual
 * localized text lives in strings.xml (5 locales) and is rendered by the
 * notification service.
 */
class ProactiveNotificationMapperTest {

    private val quotaData = mapOf(
        "event" to "warning_80",
        "threshold" to "80",
        "percent" to "85",
        "used_bytes" to "870318080", // 830 MB
        "quota_bytes" to "1073741824", // 1.0 GB
    )

    @Test
    fun `quota_80 maps to warning strings with percent used quota and open_storage`() {
        val content = ProactiveNotificationMapper.contentFor("quota_80", quotaData)
        assertNotNull(content)
        assertEquals(R.string.notif_storage_80_title, content!!.titleRes)
        assertEquals(R.string.notif_storage_80_body, content.bodyRes)
        assertEquals(listOf("85%", "830.0 MB", "1.00 GB"), content.bodyArgs)
        assertEquals(PushActions.OPEN_STORAGE, content.clickAction)
    }

    @Test
    fun `quota_95 and quota_100 map to their own thresholds`() {
        val c95 =
            ProactiveNotificationMapper.contentFor("quota_95", quotaData + ("percent" to "97"))!!
        assertEquals(R.string.notif_storage_95_title, c95.titleRes)
        assertEquals(listOf("97%", "830.0 MB", "1.00 GB"), c95.bodyArgs)
        assertEquals(PushActions.OPEN_STORAGE, c95.clickAction)

        val c100 =
            ProactiveNotificationMapper.contentFor("quota_100", quotaData + ("percent" to "100"))!!
        assertEquals(R.string.notif_storage_100_title, c100.titleRes)
        assertEquals(listOf("100%", "830.0 MB", "1.00 GB"), c100.bodyArgs)
        assertEquals(PushActions.OPEN_STORAGE, c100.clickAction)
    }

    @Test
    fun `quota_reminder maps to reminder strings`() {
        val content = ProactiveNotificationMapper.contentFor(
            "quota_reminder",
            quotaData + ("event" to "reminder"),
        )!!
        assertEquals(R.string.notif_storage_reminder_title, content.titleRes)
        assertEquals(R.string.notif_storage_reminder_body, content.bodyRes)
        assertEquals(PushActions.OPEN_STORAGE, content.clickAction)
    }

    @Test
    fun `rejected_file carries the file name and opens the app`() {
        val content = ProactiveNotificationMapper.contentFor(
            "rejected_file",
            mapOf("name" to "malware.pdf"),
        )!!
        assertEquals(R.string.notif_storage_rejected_title, content.titleRes)
        assertEquals(R.string.notif_storage_rejected_body, content.bodyRes)
        assertEquals(listOf("malware.pdf"), content.bodyArgs)
        assertEquals(PushActions.OPEN_APP, content.clickAction)
    }

    @Test
    fun `inactive_account carries the day count`() {
        val content = ProactiveNotificationMapper.contentFor(
            "inactive_account",
            mapOf("days" to "30"),
        )!!
        assertEquals(R.string.notif_account_inactive_title, content.titleRes)
        assertEquals(R.string.notif_account_inactive_body, content.bodyRes)
        assertEquals(listOf("30"), content.bodyArgs)
        assertEquals(PushActions.OPEN_APP, content.clickAction)
    }

    @Test
    fun `upload_error carries the file name`() {
        val content = ProactiveNotificationMapper.contentFor(
            "upload_error",
            mapOf("name" to "report.pdf"),
        )!!
        assertEquals(R.string.notif_upload_error_title, content.titleRes)
        assertEquals(R.string.notif_upload_error_body, content.bodyRes)
        assertEquals(listOf("report.pdf"), content.bodyArgs)
        assertEquals(PushActions.OPEN_APP, content.clickAction)
    }

    @Test
    fun `test maps to test strings without args`() {
        val content = ProactiveNotificationMapper.contentFor("test", emptyMap())!!
        assertEquals(R.string.notif_test_title, content.titleRes)
        assertEquals(R.string.notif_test_body, content.bodyRes)
        assertEquals(emptyList<Any>(), content.bodyArgs)
        assertEquals(PushActions.OPEN_APP, content.clickAction)
    }

    @Test
    fun `unknown or missing types return null`() {
        assertNull(ProactiveNotificationMapper.contentFor(null, quotaData))
        assertNull(ProactiveNotificationMapper.contentFor("", quotaData))
        assertNull(ProactiveNotificationMapper.contentFor("maintenance", quotaData))
        assertNull(ProactiveNotificationMapper.contentFor("push", quotaData))
        assertNull(ProactiveNotificationMapper.contentFor("random", quotaData))
    }

    @Test
    fun `missing or malformed data fields degrade gracefully`() {
        val content = ProactiveNotificationMapper.contentFor("quota_80", emptyMap())!!
        assertEquals(listOf("?", "Unknown", "Unknown"), content.bodyArgs)

        val broken = ProactiveNotificationMapper.contentFor("quota_80", mapOf("percent" to "abc"))!!
        assertEquals("?", broken.bodyArgs[0])

        val noName = ProactiveNotificationMapper.contentFor("rejected_file", emptyMap())!!
        assertEquals(listOf("?"), noName.bodyArgs)
    }
}
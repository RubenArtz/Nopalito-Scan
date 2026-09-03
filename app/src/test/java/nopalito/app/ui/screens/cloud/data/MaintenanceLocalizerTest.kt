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

import nopalito.app.R
import nopalito.app.ui.screens.cloud.data.MaintenanceLocalizer.MaintenanceField
import nopalito.app.ui.screens.cloud.data.MaintenanceLocalizer.ResolvedField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Block: 8C — maintenance localization contract.
 *
 * Pure JVM tests for [MaintenanceLocalizer.resolveField], covering whitelists,
 * priority (`*_key` > `code` > sanitized legacy > generic resource), unknown
 * code/key handling (never displayed as text), legacy sanitization and
 * null/blank/incomplete inputs. [resolveText] (which needs a Context) is not
 * exercised here; parity is covered by [ErrorStringsParityTest].
 */
class MaintenanceLocalizerTest {

    private val TITLE = MaintenanceField.TITLE
    private val MESSAGE = MaintenanceField.MESSAGE
    private val REASON = MaintenanceField.REASON

    private fun resolve(
        code: String?,
        key: String?,
        legacy: String?,
        field: MaintenanceField = TITLE,
    ): ResolvedField = MaintenanceLocalizer.resolveField(code, key, legacy, field)

    private fun resource(r: ResolvedField): Int = (r as ResolvedField.Resource).resId

    // ---- Whitelists ----

    @Test
    fun allowedCodesAreTheThreeStableCodes() {
        assertEquals(
            setOf("maintenance.scheduled", "maintenance.emergency", "maintenance.upgrade"),
            MaintenanceLocalizer.allowedCodes
        )
    }

    @Test
    fun allowedKeysMatchTheDocumentedWhitelist() {
        assertEquals(
            setOf(
                "maintenance.scheduled_title",
                "maintenance.emergency_title",
                "maintenance.upgrade_title"
            ),
            MaintenanceLocalizer.allowedTitleKeys
        )
        assertEquals(
            setOf(
                "maintenance.scheduled_message",
                "maintenance.emergency_message",
                "maintenance.upgrade_message"
            ),
            MaintenanceLocalizer.allowedMessageKeys
        )
        assertEquals(
            setOf(
                "maintenance.scheduled_reason",
                "maintenance.emergency_reason",
                "maintenance.upgrade_reason"
            ),
            MaintenanceLocalizer.allowedReasonKeys
        )
    }

    // ---- Each known code x each field -> its specific resource ----

    @Test
    fun scheduledCodeMapsPerField() {
        assertEquals(
            R.string.cloud_maint_scheduled_title,
            resource(resolve("maintenance.scheduled", null, null, TITLE))
        )
        assertEquals(
            R.string.cloud_maint_scheduled_message,
            resource(resolve("maintenance.scheduled", null, null, MESSAGE))
        )
        assertEquals(
            R.string.cloud_maint_scheduled_reason,
            resource(resolve("maintenance.scheduled", null, null, REASON))
        )
    }

    @Test
    fun emergencyCodeMapsPerField() {
        assertEquals(
            R.string.cloud_maint_emergency_title,
            resource(resolve("maintenance.emergency", null, null, TITLE))
        )
        assertEquals(
            R.string.cloud_maint_emergency_message,
            resource(resolve("maintenance.emergency", null, null, MESSAGE))
        )
        assertEquals(
            R.string.cloud_maint_emergency_reason,
            resource(resolve("maintenance.emergency", null, null, REASON))
        )
    }

    @Test
    fun upgradeCodeMapsPerField() {
        assertEquals(
            R.string.cloud_maint_upgrade_title,
            resource(resolve("maintenance.upgrade", null, null, TITLE))
        )
        assertEquals(
            R.string.cloud_maint_upgrade_message,
            resource(resolve("maintenance.upgrade", null, null, MESSAGE))
        )
        assertEquals(
            R.string.cloud_maint_upgrade_reason,
            resource(resolve("maintenance.upgrade", null, null, REASON))
        )
    }

    // ---- Each valid key maps to its resource ----

    @Test
    fun eachValidTitleKeyMaps() {
        assertEquals(
            R.string.cloud_maint_scheduled_title,
            resource(resolve(null, "maintenance.scheduled_title", null, TITLE))
        )
        assertEquals(
            R.string.cloud_maint_emergency_title,
            resource(resolve(null, "maintenance.emergency_title", null, TITLE))
        )
        assertEquals(
            R.string.cloud_maint_upgrade_title,
            resource(resolve(null, "maintenance.upgrade_title", null, TITLE))
        )
    }

    @Test
    fun eachValidMessageKeyMaps() {
        assertEquals(
            R.string.cloud_maint_scheduled_message,
            resource(resolve(null, "maintenance.scheduled_message", null, MESSAGE))
        )
        assertEquals(
            R.string.cloud_maint_emergency_message,
            resource(resolve(null, "maintenance.emergency_message", null, MESSAGE))
        )
        assertEquals(
            R.string.cloud_maint_upgrade_message,
            resource(resolve(null, "maintenance.upgrade_message", null, MESSAGE))
        )
    }

    @Test
    fun eachValidReasonKeyMaps() {
        assertEquals(
            R.string.cloud_maint_scheduled_reason,
            resource(resolve(null, "maintenance.scheduled_reason", null, REASON))
        )
        assertEquals(
            R.string.cloud_maint_emergency_reason,
            resource(resolve(null, "maintenance.emergency_reason", null, REASON))
        )
        assertEquals(
            R.string.cloud_maint_upgrade_reason,
            resource(resolve(null, "maintenance.upgrade_reason", null, REASON))
        )
    }

    // ---- Priority: *_key > code > legacy > generic ----

    @Test
    fun keyWinsOverDifferentCode() {
        // emergency_title key with a scheduled code -> emergency_title resource
        val r = resolve("maintenance.scheduled", "maintenance.emergency_title", "legacy", TITLE)
        assertEquals(R.string.cloud_maint_emergency_title, resource(r))
    }

    @Test
    fun keyWinsOverLegacy() {
        val r = resolve(null, "maintenance.upgrade_message", "legacy raw", MESSAGE)
        assertEquals(R.string.cloud_maint_upgrade_message, resource(r))
    }

    @Test
    fun codeWinsOverLegacy() {
        val r = resolve("maintenance.emergency", null, "legacy raw", MESSAGE)
        assertEquals(R.string.cloud_maint_emergency_message, resource(r))
    }

    @Test
    fun legacyUsedWhenNoKeyOrCode() {
        val r = resolve(null, null, "some legacy text", TITLE)
        assertTrue(r is ResolvedField.Legacy)
        assertEquals("some legacy text", (r as ResolvedField.Legacy).text)
    }

    @Test
    fun legacyAlwaysSanitized() {
        val r =
            resolve(null, null, "<b>hi</b> secret=abc https://evil.com/x C:\\app\\file.txt", TITLE)
        assertTrue(r is ResolvedField.Legacy)
        val text = (r as ResolvedField.Legacy).text
        assertNotEquals("<b>hi</b> secret=abc https://evil.com/x C:\\app\\file.txt", text)
        assertTrue(text !in listOf("<b>", "evil", "secret=abc"))
    }

    @Test
    fun genericResourceUsedWhenNothingProvided() {
        assertEquals(R.string.cloud_maint_generic_title, resource(resolve(null, null, null, TITLE)))
        assertEquals(
            R.string.cloud_maint_generic_message,
            resource(resolve(null, null, null, MESSAGE))
        )
        assertEquals(
            R.string.cloud_maint_generic_reason,
            resource(resolve(null, null, null, REASON))
        )
    }

    // ---- Unknown codes / keys are never displayed ----

    @Test
    fun unknownCodeFallsToSanitizedLegacy() {
        val r = resolve("maintenance.unknown", null, "real legacy", TITLE)
        assertTrue(r is ResolvedField.Legacy)
        assertEquals("real legacy", (r as ResolvedField.Legacy).text)
    }

    @Test
    fun unknownCodeWithoutLegacyFallsToGeneric() {
        assertEquals(
            R.string.cloud_maint_generic_message,
            resource(resolve("maintenance.unknown", null, null, MESSAGE))
        )
    }

    @Test
    fun unknownKeyFallsToCode() {
        assertEquals(
            R.string.cloud_maint_upgrade_message,
            resource(resolve("maintenance.upgrade", "maintenance.unknown_key", "legacy", MESSAGE))
        )
    }

    @Test
    fun unknownKeyWithoutCodeFallsToSanitizedLegacy() {
        val r = resolve(null, "not.a.valid.key", "some text", TITLE)
        assertTrue(r is ResolvedField.Legacy)
    }

    @Test
    fun unknownKeyWithoutAnythingFallsToGeneric() {
        assertEquals(
            R.string.cloud_maint_generic_title,
            resource(resolve(null, "not.a.valid.key", null, TITLE))
        )
    }

    @Test
    fun malformedCodeAndKeyNeverSurfaceAsText() {
        // Unknown code+key and a raw-looking legacy -> legacy (sanitized), never the raw key/code text
        val r = resolve("maintenance.nope", "maintenance.nope_title", "clean legacy", TITLE)
        assertTrue(r is ResolvedField.Legacy)
        assertEquals("clean legacy", (r as ResolvedField.Legacy).text)
    }

    // ---- null / blank / incomplete ----

    @Test
    fun blankLegacyFallsThroughToGeneric() {
        assertEquals(
            R.string.cloud_maint_generic_message,
            resource(resolve(null, null, "", MESSAGE))
        )
        assertEquals(
            R.string.cloud_maint_generic_message,
            resource(resolve(null, null, "   \t ", MESSAGE))
        )
    }

    @Test
    fun blankLegacyWithUnknownCodeFallsToGeneric() {
        assertEquals(
            R.string.cloud_maint_generic_reason,
            resource(resolve("maintenance.other", null, "", REASON))
        )
    }

    @Test
    fun emptyKeyIsTreatedAsAbsent() {
        assertEquals(
            R.string.cloud_maint_scheduled_title,
            resource(resolve("maintenance.scheduled", "", "legacy", TITLE))
        )
    }

    @Test
    fun emptyCodeIsTreatedAsAbsent() {
        val r = resolve("", null, "legacy text", TITLE)
        assertTrue(r is ResolvedField.Legacy)
        assertEquals("legacy text", (r as ResolvedField.Legacy).text)
    }

    // ---- no raw backend text surfaces as a Resource ----

    @Test
    fun resourceNeverBuiltFromServerText() {
        // A key that looks like a resource path but isn't whitelisted -> not a Resource
        val r = resolve(null, "@string/cloud_maint_generic_title", "legacy", TITLE)
        assertTrue(r is ResolvedField.Legacy)
        val r2 = resolve(null, "cloud_maint_generic_title", null, TITLE)
        assertEquals(R.string.cloud_maint_generic_title, resource(r2))
    }
}
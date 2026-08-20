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
import nopalito.app.ui.screens.qr.moduleShapeLabel
import nopalito.app.ui.screens.qr.wifiSecurityLabel
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Block: i18n string resources — exact name parity across the five locale
 * catalogs, presence of every [ErrorCodeMapper] resource, absence of backend
 * `{placeholders}` and of duplicates, valid Android placeholders, and
 * end-to-end interpolation of the mapper against the real catalog text.
 *
 * Runs on the JVM by parsing the generated XML directly (no Android Context
 * needed); the working directory is the `app` module.
 */
class ErrorStringsParityTest {

    private val directories = listOf(
        "values",
        "values-b+es+419",
        "values-de",
        "values-fr",
        "values-pt-rBR",
    )

    private fun fileFor(dir: String): File = File("src/main/res/$dir/strings.xml")

    private fun parse(dir: String): Document {
        val file = fileFor(dir)
        assertTrue("missing strings.xml: ${file.absolutePath}", file.exists())
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file)
    }

    /** name -> text (trimmed, normalized whitespace) for a locale catalog. */
    private fun catalog(dir: String): Map<String, String> {
        val doc = parse(dir)
        val nodes = doc.getElementsByTagName("string")
        val result = HashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i)
            val name = el.attributes?.getNamedItem("name")?.nodeValue ?: continue
            result[name] = el.textContent.trim().replace(Regex("\\s+"), " ")
        }
        return result
    }

    private fun resourceNameOf(resId: Int): String? {
        // Map a resolved R.string id back to its field name via the R class.
        for (field in R.string::class.java.fields) {
            if (field.getInt(null) == resId) return field.name
        }
        return null
    }

    private fun catalogs(): Map<String, Map<String, String>> =
        directories.associateWith { catalog(it) }

    // ---- Exact name parity ----

    @Test
    fun allFiveCatalogsHaveIdenticalResourceNames() {
        val catalogs = catalogs()
        val base = catalogs["values"]!!.keys
        for (dir in directories) {
            assertEquals(
                "name parity vs values for $dir",
                base,
                catalogs[dir]!!.keys
            )
        }
    }

    @Test
    fun noDuplicateResourceNamesInAnyCatalog() {
        for (dir in directories) {
            val doc = parse(dir)
            val names = (0 until doc.getElementsByTagName("string").length)
                .map { doc.getElementsByTagName("string").item(it) }
                .mapNotNull { it.attributes?.getNamedItem("name")?.nodeValue }
            assertEquals("duplicates in $dir", names.size, names.toSet().size)
        }
    }

    // ---- Every resource the mapper can emit is present in all five ----

    @Test
    fun everyMapperResourceExistsInAllFiveCatalogs() {
        val catalogs = catalogs()
        val required = REQUIRED_MAPPER_RESOURCES
        for (dir in directories) {
            val names = catalogs[dir]!!.keys
            val missing = required - names
            assertTrue("missing in $dir: $missing", missing.isEmpty())
        }
    }

    @Test
    fun mapperResolutionsArePresentInEveryCatalog() {
        val catalogs = catalogs()
        val resolutions = listOf(
            ErrorCodeMapper.resolveResId("QUOTA_EXCEEDED", 400),
            ErrorCodeMapper.resolveResId("QUOTA_EXCEEDED_ON_RESTORE", 400),
            ErrorCodeMapper.resolveResId("RESEND_COOLDOWN", 429),
            ErrorCodeMapper.resolveResId("MAINTENANCE_ACTIVE", 503),
            ErrorCodeMapper.resolveResId("RATE_LIMIT_EXCEEDED", 500),
            ErrorCodeMapper.resolveResId("INVALID_MIGRATION_ID", 400),
            ErrorCodeMapper.resolveResId("QR_DATA_TOO_LARGE", 400),
            ErrorCodeMapper.resolveResId("JOB_NOT_FOUND", 404),
            ErrorCodeMapper.resolveResId("QUOTA_SOMETHING", 413),
            ErrorCodeMapper.resolveResId("AUTH_ACCOUNT_SUSPENDED", 403),
            ErrorCodeMapper.resolveResId("AUTH_PASSWORD_RESET_BLOCKED_SUSPENDED", 403),
            ErrorCodeMapper.resolveResId("AUTH_ACCOUNT_STATUS_UNKNOWN", 503),
            ErrorCodeMapper.resolveResId("AUTH_REGISTER_IP_LIMIT_REACHED", 429),
            ErrorCodeMapper.resolveResId("AUTH_REGISTER_VPN_NOT_ALLOWED", 403),
            ErrorCodeMapper.resolveResId("AUTH_UNEXPECTED_AUTH_CODE", 500),
            ErrorCodeMapper.resolveResId("ACCOUNT_ALREADY_DELETED", 409),
            ErrorCodeMapper.resolveResId("ANONYMOUS_USER_PROTECTED", 403),
            ErrorCodeMapper.resolveResId(null, 403),
            ErrorCodeMapper.resolveResId(null, 401),
            ErrorCodeMapper.resolveResId(null, 0),
        )
        for (resId in resolutions) {
            val name = resourceNameOf(resId)
            assertTrue("resource id $resId -> $name", name != null)
            for (dir in directories) {
                assertTrue("$name missing in $dir", catalogs[dir]!!.containsKey(name))
            }
        }
    }

    // ---- Placeholders ----

    @Test
    fun noBackendCurlyPlaceholdersRemain() {
        for (dir in directories) {
            for ((name, text) in catalog(dir)) {
                assertFalse("backend {placeholder} in $dir/$name: '$text'", text.contains('{') || text.contains('}'))
            }
        }
    }

    @Test
    fun androidPlaceholdersInMapperResourcesAreValid() {
        for (dir in directories) {
            val texts = REQUIRED_MAPPER_RESOURCES.mapNotNull { catalog(dir)[it] }
            for (text in texts) {
                // Every '%' must be part of a positional specifier like %1$s / %2$d.
                val tokens = Regex("%[0-9]+\\$[a-z]").findAll(text).map { it.value }.toList()
                val bare = text.filter { it == '%' }.length
                assertEquals("malformed % in $dir: '$text'", tokens.size, bare)
            }
        }
    }

    @Test
    fun errorUnknownIsPresentEverywhere() {
        for (dir in directories) {
            assertTrue(catalog(dir).containsKey("error_unknown"))
        }
    }

    // ---- Maintenance contract resources ----

    @Test
    fun everyMaintenanceResourceExistsInAllFiveCatalogs() {
        val catalogs = catalogs()
        for (dir in directories) {
            val names = catalogs[dir]!!.keys
            val missing = REQUIRED_MAINTENANCE_RESOURCES - names
            assertTrue("missing maintenance resources in $dir: $missing", missing.isEmpty())
        }
    }

    // ---- Stage 8F resources (QR labels + trash countdown) ----

    @Test
    fun everyStage8FResourceExistsInAllFiveCatalogs() {
        val catalogs = catalogs()
        for (dir in directories) {
            val names = catalogs[dir]!!.keys
            val missing = REQUIRED_STAGE_8F_RESOURCES - names
            assertTrue("missing stage-8F resources in $dir: $missing", missing.isEmpty())
        }
    }

    @Test
    fun stage8FPlaceholdersAreValidInEveryCatalog() {
        for (dir in directories) {
            val texts = REQUIRED_STAGE_8F_RESOURCES.mapNotNull { catalog(dir)[it] }
            for (text in texts) {
                // Every '%' must be part of a positional specifier like %1$s / %2$d.
                val tokens = Regex("%[0-9]+\\$[a-z]").findAll(text).map { it.value }.toList()
                val bare = text.filter { it == '%' }.length
                assertEquals("malformed % in $dir: '$text'", tokens.size, bare)
            }
        }
    }

    @Test
    fun stage8FQrLabelResourcesResolveToTheFiveCatalogs() {
        val catalogs = catalogs()
        val resolutions = listOf(
            wifiSecurityLabel("WPA"),
            wifiSecurityLabel("WEP"),
            wifiSecurityLabel("Abierta"),
            wifiSecurityLabel("Open"),
            wifiSecurityLabel(null),
            moduleShapeLabel("square"),
            moduleShapeLabel("rounded"),
            moduleShapeLabel("circle"),
            moduleShapeLabel("diamond"),
            moduleShapeLabel("unknown"),
        )
        for (resId in resolutions) {
            val name = resourceNameOf(resId)
            assertTrue("resource id $resId -> $name", name != null)
            for (dir in directories) {
                assertTrue("$name missing in $dir", catalogs[dir]!!.containsKey(name))
            }
        }
    }

    @Test
    fun everyMaintenanceResolutionReferencesExistingResource() {
        val catalogs = catalogs()
        val resolutions = ArrayList<Int>()
        // one known code per field -> specific resource
        for (code in MaintenanceLocalizer.allowedCodes) {
            for (field in MaintenanceField.entries) {
                val r = MaintenanceLocalizer.resolveField(code, null, null, field)
                assertTrue(
                    "code $code/$field should resolve to a resource",
                    r is MaintenanceLocalizer.ResolvedField.Resource
                )
                resolutions.add((r as MaintenanceLocalizer.ResolvedField.Resource).resId)
            }
        }
        // every allowed key -> its resource
        for (key in MaintenanceLocalizer.allowedTitleKeys) {
            val r = MaintenanceLocalizer.resolveField(null, key, null, MaintenanceField.TITLE)
            resolutions.add((r as MaintenanceLocalizer.ResolvedField.Resource).resId)
        }
        for (key in MaintenanceLocalizer.allowedMessageKeys) {
            val r = MaintenanceLocalizer.resolveField(null, key, null, MaintenanceField.MESSAGE)
            resolutions.add((r as MaintenanceLocalizer.ResolvedField.Resource).resId)
        }
        for (key in MaintenanceLocalizer.allowedReasonKeys) {
            val r = MaintenanceLocalizer.resolveField(null, key, null, MaintenanceField.REASON)
            resolutions.add((r as MaintenanceLocalizer.ResolvedField.Resource).resId)
        }
        // nothing present -> generic resource
        for (field in MaintenanceField.entries) {
            val r = MaintenanceLocalizer.resolveField(null, null, null, field)
            resolutions.add((r as MaintenanceLocalizer.ResolvedField.Resource).resId)
        }
        for (resId in resolutions.distinct()) {
            val name = resourceNameOf(resId)
            assertTrue("maintenance resource id $resId -> $name", name != null)
            for (dir in directories) {
                assertTrue("$name missing in $dir", catalogs[dir]!!.containsKey(name))
            }
        }
    }

    // ---- Interpolation against the real catalog text ----

    @Test
    fun waitSecondsInterpolatesIntoResendCooldown() {
        for (dir in directories) {
            val template = catalog(dir)["cloud_error_resend_cooldown"]!!
            val formatted = ErrorCodeMapper.format(template, mapOf<String, Any>("waitSeconds" to 30))
            val output = ErrorCodeMapper.apply(formatted.pattern, formatted.args)
            assertTrue("$dir: '$output' should contain 30", output.contains("30"))
            assertFalse("$dir: leftover {waitSeconds} in '$output'", output.contains("{waitSeconds}"))
        }
    }

    @Test
    fun maxInterpolatesIntoQrResource() {
        for (dir in directories) {
            val template = catalog(dir)["cloud_error_qr"]!!
            val formatted = ErrorCodeMapper.format(template, mapOf<String, Any>("max" to 8000))
            val output = ErrorCodeMapper.apply(formatted.pattern, formatted.args)
            assertTrue("$dir: '$output' should contain 8000", output.contains("8000"))
            assertFalse("$dir: leftover {max} in '$output'", output.contains("{max}"))
        }
    }

    @Test
    fun everySupportedPlaceholderKeyInterpolates() {
        // Each supported key is substituted into a canonical template, proving
        // the mapper interpolates count / max / field / quota / waitSeconds
        // uniformly against the real placeholder set (even where the current
        // catalog has no dedicated string carrying that field yet).
        val cases = mapOf(
            "count" to mapOf<String, Any>("count" to 5),
            "max" to mapOf<String, Any>("max" to 100),
            "field" to mapOf<String, Any>("field" to "email"),
            "usedBytes" to mapOf<String, Any>("usedBytes" to 80),
            "quotaBytes" to mapOf<String, Any>("quotaBytes" to 100),
            "restoreBytes" to mapOf<String, Any>("restoreBytes" to 40),
            "availableBytes" to mapOf<String, Any>("availableBytes" to 20),
            "requiredBytes" to mapOf<String, Any>("requiredBytes" to 40),
            "finalUsedBytes" to mapOf<String, Any>("finalUsedBytes" to 120),
            "waitSeconds" to mapOf<String, Any>("waitSeconds" to 30),
        )
        for ((key, details) in cases) {
            val formatted = ErrorCodeMapper.format("Test {key}".replace("{key}", "{$key}"), details)
            val output = ErrorCodeMapper.apply(formatted.pattern, formatted.args)
            assertFalse("leftover {$key} for $key", output.contains("{$key}"))
            assertTrue("value not substituted for $key: '$output'", formatted.args.isNotEmpty())
        }
    }

    // ---- Biometric gate resources (Etapa 7) ----

    @Test
    fun everyBiometricGateResourceExistsInAllFiveCatalogs() {
        val catalogs = catalogs()
        for (dir in directories) {
            val names = catalogs[dir]!!.keys
            val missing = REQUIRED_BIOMETRIC_RESOURCES - names
            assertTrue("missing biometric gate resources in $dir: $missing", missing.isEmpty())
        }
    }

    @Test
    fun biometricGateResourcesHaveNoBackendCurlyPlaceholders() {
        for (dir in directories) {
            for (name in REQUIRED_BIOMETRIC_RESOURCES) {
                val text = catalog(dir)[name] ?: continue
                assertFalse("backend {placeholder} in $dir/$name: '$text'", text.contains('{') || text.contains('}'))
            }
        }
    }

    @Test
    fun biometricGatePlaceholdersAreValidInEveryCatalog() {
        for (dir in directories) {
            val texts = REQUIRED_BIOMETRIC_RESOURCES.mapNotNull { catalog(dir)[it] }
            for (text in texts) {
                // Every '%' must be part of a positional specifier like %1$s / %2$d.
                val tokens = Regex("%[0-9]+\\$[a-z]").findAll(text).map { it.value }.toList()
                val bare = text.filter { it == '%' }.length
                assertEquals("malformed % in $dir: '$text'", tokens.size, bare)
            }
        }
    }

    private companion object {
        val REQUIRED_BIOMETRIC_RESOURCES = setOf(
            "cloud_biometric_unlock_title",
            "cloud_biometric_unlock_message",
            "cloud_biometric_unlock_button",
            "cloud_biometric_unlock_use_another_account",
            "cloud_biometric_unlock_locked_out",
            "cloud_biometric_unlock_unavailable",
            "cloud_biometric_unlock_failed",
            "cloud_biometric_prompt_title",
            "cloud_biometric_prompt_subtitle",
            "cloud_biometric_prompt_negative",
            "cloud_biometric_toggle_title",
            "cloud_biometric_toggle_subtitle",
            "cloud_biometric_toggle_failed",
        )

        val REQUIRED_MAPPER_RESOURCES = setOf(
            // specific codes
            "cloud_error_quota_exceeded",
            "cloud_error_restore_quota",
            "cloud_error_resend_cooldown",
            "cloud_error_maintenance_active",
            "cloud_error_auth_account_suspended",
            "cloud_error_auth_password_reset_blocked",
            "cloud_error_auth_account_status_unknown",
            "cloud_error_auth_register_ip_limit",
            "cloud_error_auth_register_vpn",
            "cloud_error_account_already_deleted",
            "cloud_error_anonymous_protected",
            // groups
            "cloud_error_rate_limit",
            "cloud_error_migration",
            "cloud_error_qr",
            "cloud_error_job",
            "cloud_error_quota",
            "cloud_error_auth",
            // status
            "cloud_error_400", "cloud_error_401", "cloud_error_403", "cloud_error_404",
            "cloud_error_413", "cloud_error_415", "cloud_error_429",
            "cloud_error_500", "cloud_error_503",
            // fallback
            "error_unknown",
        )

        val REQUIRED_MAINTENANCE_RESOURCES = setOf(
            "cloud_maint_scheduled_title",
            "cloud_maint_scheduled_message",
            "cloud_maint_scheduled_reason",
            "cloud_maint_emergency_title",
            "cloud_maint_emergency_message",
            "cloud_maint_emergency_reason",
            "cloud_maint_upgrade_title",
            "cloud_maint_upgrade_message",
            "cloud_maint_upgrade_reason",
            "cloud_maint_generic_title",
            "cloud_maint_generic_message",
            "cloud_maint_generic_reason",
        )

        val REQUIRED_STAGE_8F_RESOURCES = setOf(
            "qr_content_required",
            "qr_generate_error",
            "qr_wifi_security_wpa",
            "qr_wifi_security_wep",
            "qr_wifi_security_open",
            "qr_shape_square",
            "qr_shape_rounded",
            "qr_shape_circle",
            "qr_shape_diamond",
            "cloud_trash_remaining_days_hours",
            "cloud_trash_remaining_hours_minutes",
            "cloud_trash_remaining_minutes_seconds",
            "cloud_trash_remaining_less_minute",
        )
    }
}
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

import android.content.Context
import androidx.annotation.StringRes
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.push.localizeMaintenanceText
import nopalito.app.push.sanitizeMaintenanceText
import nopalito.app.ui.screens.cloud.model.MaintenanceStatus

/**
 * Resolves a localized maintenance message for one of the three displayed
 * fields (`title`, `message`, `reason`) using the stable maintenance contract:
 *
 *  1. a valid `*_key` (from the whitelist) -> localized Android resource;
 *  2. a known `code` (from the whitelist)  -> localized Android resource;
 *  3. legacy free text                     -> sanitized legacy (ML Kit translated);
 *  4. otherwise                            -> generic localized maintenance resource.
 *
 * Codes and keys are only ever compared against the fixed whitelists below; an
 * unknown code or key is never displayed as text, never turned into a
 * `@StringRes`, and never rendered as JSON. Legacy text always passes through
 * [sanitizeMaintenanceText] (before and after ML Kit translation) via
 * [localizeMaintenanceText], so no raw backend text reaches the UI.
 */
object MaintenanceLocalizer {

    private val CODE_TITLE = mapOf(
        "maintenance.scheduled" to R.string.cloud_maint_scheduled_title,
        "maintenance.emergency" to R.string.cloud_maint_emergency_title,
        "maintenance.upgrade" to R.string.cloud_maint_upgrade_title,
    )
    private val CODE_MESSAGE = mapOf(
        "maintenance.scheduled" to R.string.cloud_maint_scheduled_message,
        "maintenance.emergency" to R.string.cloud_maint_emergency_message,
        "maintenance.upgrade" to R.string.cloud_maint_upgrade_message,
    )
    private val CODE_REASON = mapOf(
        "maintenance.scheduled" to R.string.cloud_maint_scheduled_reason,
        "maintenance.emergency" to R.string.cloud_maint_emergency_reason,
        "maintenance.upgrade" to R.string.cloud_maint_upgrade_reason,
    )

    private val TITLE_KEYS = mapOf(
        "maintenance.scheduled_title" to R.string.cloud_maint_scheduled_title,
        "maintenance.emergency_title" to R.string.cloud_maint_emergency_title,
        "maintenance.upgrade_title" to R.string.cloud_maint_upgrade_title,
    )
    private val MESSAGE_KEYS = mapOf(
        "maintenance.scheduled_message" to R.string.cloud_maint_scheduled_message,
        "maintenance.emergency_message" to R.string.cloud_maint_emergency_message,
        "maintenance.upgrade_message" to R.string.cloud_maint_upgrade_message,
    )
    private val REASON_KEYS = mapOf(
        "maintenance.scheduled_reason" to R.string.cloud_maint_scheduled_reason,
        "maintenance.emergency_reason" to R.string.cloud_maint_emergency_reason,
        "maintenance.upgrade_reason" to R.string.cloud_maint_upgrade_reason,
    )

    /** Whitelisted stable codes. Exposed for tests. */
    val allowedCodes: Set<String> = CODE_TITLE.keys

    /** Whitelisted localized keys per field. Exposed for tests. */
    val allowedTitleKeys: Set<String> = TITLE_KEYS.keys
    val allowedMessageKeys: Set<String> = MESSAGE_KEYS.keys
    val allowedReasonKeys: Set<String> = REASON_KEYS.keys

    /** A maintenance field, with its generic localized resource. */
    enum class MaintenanceField(@param:StringRes val genericRes: Int) {
        TITLE(R.string.cloud_maint_generic_title),
        MESSAGE(R.string.cloud_maint_generic_message),
        REASON(R.string.cloud_maint_generic_reason),
    }

    /** Resolution outcome for one field. */
    sealed class ResolvedField {
        /** A localized Android resource; shown as-is, never translated on-device. */
        data class Resource(@param:StringRes val resId: Int) : ResolvedField()

        /** Sanitized legacy text; should be ML Kit translated before display. */
        data class Legacy(val text: String) : ResolvedField()
    }

    /** Pure, deterministic resolution following the documented priority. */
    fun resolveField(
        code: String?,
        key: String?,
        legacy: String?,
        field: MaintenanceField,
    ): ResolvedField {
        val keyRes = keyResFor(key, field)
        if (keyRes != null) return ResolvedField.Resource(keyRes)

        val codeRes = codeResFor(code, field)
        if (codeRes != null) return ResolvedField.Resource(codeRes)

        val sanitized = sanitizeMaintenanceText(legacy)
        if (sanitized.isNotBlank()) return ResolvedField.Legacy(sanitized)

        return ResolvedField.Resource(field.genericRes)
    }

    /**
     * Produces the final display string for [field] of [status].
     * Localized resources are read from [context] (which already reflects the
     * app locale); legacy text is ML Kit translated (sanitized on both sides).
     */
    suspend fun resolveText(
        status: MaintenanceStatus,
        field: MaintenanceField,
        context: Context,
        translate: suspend (String) -> String,
    ): String {
        val legacy = legacyOf(status, field)
        val key = keyOf(status, field)
        return when (val r = resolveField(status.code, key, legacy, field)) {
            is ResolvedField.Resource -> context.stringFor(r.resId, AppLocaleOverride.locale)
            is ResolvedField.Legacy -> localizeMaintenanceText(r.text, translate)
        }
    }

    private fun keyResFor(key: String?, field: MaintenanceField): Int? = when (field) {
        MaintenanceField.TITLE -> key?.let { TITLE_KEYS[it] }
        MaintenanceField.MESSAGE -> key?.let { MESSAGE_KEYS[it] }
        MaintenanceField.REASON -> key?.let { REASON_KEYS[it] }
    }

    private fun codeResFor(code: String?, field: MaintenanceField): Int? = when (field) {
        MaintenanceField.TITLE -> code?.let { CODE_TITLE[it] }
        MaintenanceField.MESSAGE -> code?.let { CODE_MESSAGE[it] }
        MaintenanceField.REASON -> code?.let { CODE_REASON[it] }
    }

    private fun legacyOf(status: MaintenanceStatus, field: MaintenanceField): String? = when (field) {
        MaintenanceField.TITLE -> status.title
        MaintenanceField.MESSAGE -> status.message
        MaintenanceField.REASON -> status.reason
    }

    private fun keyOf(status: MaintenanceStatus, field: MaintenanceField): String? = when (field) {
        MaintenanceField.TITLE -> status.titleKey
        MaintenanceField.MESSAGE -> status.messageKey
        MaintenanceField.REASON -> status.reasonKey
    }
}
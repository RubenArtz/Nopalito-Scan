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

import androidx.annotation.StringRes
import nopalito.app.R
import nopalito.app.ui.screens.cloud.model.ApiDetails

/**
 * Maps a backend error to a localized string resource.
 *
 * Resolution order (fixed):
 * 1. specific error code;
 * 2. code group (prefix);
 * 3. HTTP status;
 * 4. [R.string.error_unknown].
 *
 * Never throws: an unknown/empty code falls to status, and an unknown status
 * falls to `error_unknown`. It only ever returns resource IDs that exist in the
 * current catalog; resource *names* are never built from server input, so no
 * arbitrary string can be interpolated into a resource lookup.
 */
object ErrorCodeMapper {

    /** Specific backend codes -> dedicated string resource. */
    private val SPECIFIC: Map<String, Int> = mapOf(
        ApiException.QUOTA_EXCEEDED to R.string.cloud_error_quota_exceeded,
        ApiException.QUOTA_EXCEEDED_ON_RESTORE to R.string.cloud_error_restore_quota,
        "RESEND_COOLDOWN" to R.string.cloud_error_resend_cooldown,
        "MAINTENANCE_ACTIVE" to R.string.cloud_error_maintenance_active,
    )

    /** Code group prefixes -> string resource (first match wins, insertion order). */
    private val GROUPS: List<Pair<String, Int>> = listOf(
        "RATE_LIMIT_" to R.string.cloud_error_rate_limit,
        "MAINTENANCE_" to R.string.cloud_error_maintenance_active,
        "INVALID_MIGRATION_" to R.string.cloud_error_migration,
        "MIGRATION_" to R.string.cloud_error_migration,
        "QR_" to R.string.cloud_error_qr,
        "JOB_" to R.string.cloud_error_job,
        "QUOTA_" to R.string.cloud_error_quota,
    )

    /** HTTP status -> existing `cloud_error_*` string resource. */
    private val STATUS: Map<Int, Int> = mapOf(
        400 to R.string.cloud_error_400,
        401 to R.string.cloud_error_401,
        404 to R.string.cloud_error_404,
        413 to R.string.cloud_error_413,
        415 to R.string.cloud_error_415,
        429 to R.string.cloud_error_429,
        500 to R.string.cloud_error_500,
        503 to R.string.cloud_error_503,
    )

    /**
     * Deterministic order in which backend `{placeholders}` are numbered. Each
     * key with an available value becomes `%N$s` (contiguous numbering) and
     * contributes its value to the substitution array in this same order.
     */
    private val PLACEHOLDER_ORDER = listOf(
        "count", "max", "field",
        "usedBytes", "quotaBytes", "restoreBytes",
        "availableBytes", "requiredBytes", "finalUsedBytes",
        "waitSeconds",
    )

    /** Resolves the string resource id for an [ApiException]. */
    @StringRes
    fun resolveResId(e: ApiException): Int = resolveResId(e.code, e.httpStatus)

    /** Resolves the string resource id for a code + HTTP status pair. */
    @StringRes
    fun resolveResId(code: String?, statusCode: Int): Int {
        if (!code.isNullOrBlank()) {
            SPECIFIC[code]?.let { return it }
            GROUPS.firstOrNull { (prefix, _) -> code.startsWith(prefix) }?.let { return it.second }
        }
        return STATUS[statusCode] ?: R.string.error_unknown
    }

    /** The value backing a `{key}` placeholder, or null when absent/invalid. */
    fun placeholderValue(details: Map<String, Any>?, key: String): String? = when (key) {
        "count" -> ApiDetails.getInt(details, "count")?.toString()
        "max" -> ApiDetails.getInt(details, "max")?.toString()
        "field" -> ApiDetails.getString(details, "field")
        "usedBytes" -> ApiDetails.getLong(details, "usedBytes")?.toString()
        "quotaBytes" -> ApiDetails.getLong(details, "quotaBytes")?.toString()
        "restoreBytes" -> ApiDetails.getLong(details, "restoreBytes")?.toString()
        "availableBytes" -> ApiDetails.getLong(details, "availableBytes")?.toString()
        "requiredBytes" -> ApiDetails.getLong(details, "requiredBytes")?.toString()
        "finalUsedBytes" -> ApiDetails.getLong(details, "finalUsedBytes")?.toString()
        "waitSeconds" -> ApiDetails.getInt(details, "waitSeconds")?.toString()
        else -> null
    }

    /**
     * Converts backend `{placeholders}` in [template] to Android positional
     * format (`%1$s`, `%2$s`, ...) and collects their values in matching order.
     * Missing/absent placeholders are left untouched (tolerated); numbering is
     * contiguous and deterministic; numeric values are never altered.
     */
    fun format(template: String, details: Map<String, Any>?): Formatted {
        val args = ArrayList<String>()
        var pattern = template
        var index = 0
        for (key in PLACEHOLDER_ORDER) {
            val value = placeholderValue(details, key) ?: continue
            index += 1
            pattern = pattern.replace("{$key}", "%$index\$s")
            args.add(value)
        }
        return Formatted(pattern, args.toTypedArray())
    }

    /**
     * Applies collected argument values to a pattern produced by [format],
     * replacing each `%N$s` token in order. Performs plain string replacement
     * (no `String.format`), so arbitrary `%` characters in the text are safe.
     */
    fun apply(pattern: String, args: Array<String>): String {
        var result = pattern
        for ((i, arg) in args.withIndex()) {
            result = result.replace("%${i + 1}\$s", arg)
        }
        return result
    }

}

/** Result of placeholder conversion: a pattern and its ordered values. */
data class Formatted(val pattern: String, val args: Array<String>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Formatted

        if (pattern != other.pattern) return false
        if (!args.contentEquals(other.args)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pattern.hashCode()
        result = 31 * result + args.contentHashCode()
        return result
    }
}
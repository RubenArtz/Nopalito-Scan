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

package nopalito.app.ui.screens.cloud.model

import com.google.gson.annotations.SerializedName

// ====== General API envelope ======

data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: T? = null,
    @SerializedName("error") val error: ApiError? = null
)

/**
 * A single backend validation error. [message] is a localized string (never a
 * list); [field] is the offending input name.
 */
data class FieldError(
    @SerializedName("field") val field: String,
    @SerializedName("message") val message: String?
)

/**
 * The structured, normalized result of parsing an HTTP error response.
 *
 * Produced by [nopalito.app.ui.screens.cloud.data.ErrorParser]. [statusCode] is
 * the HTTP status; [code] is the stable backend error identifier; [message] is
 * the server-provided display text (sanitized, may be null); [details] holds
 * typed values (quota, cooldown, push, scans) readable only via [ApiDetails];
 * [fields] holds per-field validation messages; [backendMessage] is the raw
 * server text sanitized and capped, kept ONLY as a final legacy fallback and
 * never as primary visible text.
 */
data class ErrorResponse(
    val statusCode: Int,
    val code: String? = null,
    val message: String? = null,
    val details: Map<String, Any>? = null,
    val fields: List<FieldError>? = null,
    val backendMessage: String? = null
)

/**
 * Structured error envelope returned by the backend.
 *
 * [details] is optional and tolerant: it may be absent (legacy responses),
 * null, or carry numbers, strings, booleans, nested maps and lists. Access its
 * values only through [ApiDetails] helpers — never with direct casts, because
 * Gson deserializes map values as `Double`/`LinkedTreeMap` and a raw cast can
 * crash or lose precision. [waitSeconds] and [fields] remain for legacy
 * responses that do not use [details].
 */
data class ApiError(
    @SerializedName("code") val code: String,
    @SerializedName("fields") val fields: List<String>? = null,
    @SerializedName("waitSeconds") val waitSeconds: Int? = null,
    @SerializedName("details") val details: Map<String, Any>? = null
)

/**
 * Safe, typed access to backend `error.details` values.
 *
 * The backend may send numbers, strings, booleans, nested maps and lists.
 * Gson deserializes JSON objects into `LinkedTreeMap` and every number into a
 * `Double`, so a direct `details["max"] as Int` can crash. These helpers
 * normalize the value to the requested type and return `null` for missing or
 * invalid values; callers then fall back to a localized message instead of
 * showing `null`, raw `Double` decimals or untranslated technical names.
 */
object ApiDetails {

    /** The value as a String (numbers/booleans are NOT coerced to text). */
    fun getString(details: Map<String, Any?>?, key: String): String? {
        val value = details?.get(key) ?: return null
        return value as? String
    }

    /** The value as a Long (accepts any Number or a numeric String). */
    fun getLong(details: Map<String, Any?>?, key: String): Long? {
        val value = details?.get(key) ?: return null
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    /** The value as an Int (accepts any Number or a numeric String). */
    fun getInt(details: Map<String, Any?>?, key: String): Int? {
        val value = details?.get(key) ?: return null
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    /** The value as a Double (accepts any Number or a numeric String). */
    fun getDouble(details: Map<String, Any?>?, key: String): Double? {
        val value = details?.get(key) ?: return null
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    /**
     * The value as a Boolean. Accepts JSON booleans, numeric 0/1 (which Gson
     * delivers as Double) and the strings "true"/"false"/"0"/"1".
     */
    fun getBoolean(details: Map<String, Any?>?, key: String): Boolean? {
        val value = details?.get(key) ?: return null
        return when (value) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> when (value.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }

            else -> null
        }
    }

    /** The value as a list, or null when absent or not a list. */
    fun getList(details: Map<String, Any?>?, key: String): List<*>? {
        val value = details?.get(key) ?: return null
        return value as? List<*>
    }

    /** The value as a nested map, or null when absent or not a map. */
    @Suppress("UNCHECKED_CAST")
    fun getMap(details: Map<String, Any?>?, key: String): Map<String, Any>? {
        val value = details?.get(key) ?: return null
        return value as? Map<String, Any>
    }
}

// ====== Auth models ======

data class AuthCodeRequest(
    @SerializedName("email") val email: String
)

/**
 * Body of POST /api/auth/register (password registration). The backend only
 * stores a bcrypt hash of [password] until the OTP is verified.
 */
data class RegisterRequest(
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("confirmPassword") val confirmPassword: String
)

/** Body of POST /api/auth/login/password. Includes optional device metadata for session list. */
data class LoginPasswordRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("device_name") val deviceName: String? = null
)

/**
 * Body of POST /api/auth/password/reset and /api/auth/password/set:
 * verifies the single-use code and stores the new password hash.
 */
data class NewPasswordWithCodeRequest(
    @SerializedName("email") val email: String,
    @SerializedName("code") val code: String,
    @SerializedName("newPassword") val newPassword: String,
    @SerializedName("confirmPassword") val confirmPassword: String
)

data class AuthCodeResponseData(
    @SerializedName("expiresInMinutes") val expiresInMinutes: Int,
    @SerializedName("resendAvailableInSeconds") val resendAvailableInSeconds: Int
)

data class VerifyCodeRequest(
    @SerializedName("email") val email: String,
    @SerializedName("code") val code: String,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("device_name") val deviceName: String? = null
)

data class TokenData(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("accessTokenExpiresIn") val accessTokenExpiresIn: String,
    @SerializedName("refreshTokenExpiresIn") val refreshTokenExpiresIn: String,
    @SerializedName("user") val user: UserData? = null
)

data class AvatarInfo(
    @SerializedName("url") val url: String? = null,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String? = null,
    @SerializedName("source") val source: String? = null, // custom|google|default
    @SerializedName("provider") val provider: String? = null,
    @SerializedName("version") val version: Int = 0,
    @SerializedName("updatedAt") val updatedAt: String? = null
)

data class UserData(
    @SerializedName("id") val id: String,
    @SerializedName("email") val email: String,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("is_verified") val isVerified: Boolean? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("last_login_at") val lastLoginAt: String? = null,
    /** Server-authoritative plan: "FREE" | "PERSONAL" | "PLUS". */
    @SerializedName("plan") val plan: String? = null,
    @SerializedName("storage_limit_bytes") val storageLimitBytes: Long? = null,
    @SerializedName("has_password") val hasPassword: Boolean? = null,
    @SerializedName("require_login_code") val requireLoginCode: Boolean? = null,
    @SerializedName("google_id") val googleId: String? = null,
    @SerializedName("google_picture") val googlePicture: String? = null,
    @SerializedName("auth_provider") val authProvider: String? = null,
    @SerializedName("avatar") val avatar: AvatarInfo? = null
)

data class UpdateLoginCodeRequest(
    @SerializedName("require_login_code") val requireLoginCode: Boolean
)

data class EmailChangeRequest(
    @SerializedName("newEmail") val newEmail: String
)

data class ConfirmEmailChangeRequest(
    @SerializedName("newEmail") val newEmail: String,
    @SerializedName("code") val code: String
)

/**
 * Flexible response of POST /api/auth/login/password: when the account
 * requires an email code the fields [expiresInMinutes]/[resendAvailableInSeconds]
 * are present; when the user disabled the code the tokens are returned
 * directly (same shape as TokenData). [require_login_code] echoes the stored
 * preference.
 */
data class LoginPasswordResponse(
    @SerializedName("expiresInMinutes") val expiresInMinutes: Int? = null,
    @SerializedName("resendAvailableInSeconds") val resendAvailableInSeconds: Int? = null,
    @SerializedName("require_login_code") val requireLoginCode: Boolean? = null,
    @SerializedName("accessToken") val accessToken: String? = null,
    @SerializedName("refreshToken") val refreshToken: String? = null,
    @SerializedName("accessTokenExpiresIn") val accessTokenExpiresIn: String? = null,
    @SerializedName("refreshTokenExpiresIn") val refreshTokenExpiresIn: String? = null,
    @SerializedName("user") val user: UserData? = null
)

/**
 * Response of GET /api/storage/usage. Server-authoritative: the client only
 * displays these values (bytes internally, MB/GB formatted in the UI).
 */
data class StorageUsage(
    @SerializedName("plan") val plan: String? = null,
    @SerializedName("usedBytes") val usedBytes: Long = 0L,
    @SerializedName("limitBytes") val limitBytes: Long = 0L,
    @SerializedName("freeBytes") val freeBytes: Long = 0L,
    @SerializedName("usedPercent") val usedPercent: Int = 0,
    @SerializedName("isPremium") val isPremium: Boolean? = null
) {
    /** Normalized plan id: FREE | PERSONAL | PLUS. */
    val normalizedPlan: String
        get() = plan?.uppercase()?.trim()?.takeIf { it.isNotEmpty() } ?: "FREE"

    val isFreePlan: Boolean
        get() = normalizedPlan == "FREE"

    val isPremiumPlan: Boolean
        get() = isPremium == true || normalizedPlan != "FREE"

    /** Clamped 0..1 progress for the storage bar. */
    val progressRatio: Float get() = (usedPercent / 100f).coerceIn(0f, 1f)
}

/**
 * Response model for GET /api/auth/me.
 * The backend returns: { success, message, data: { user: { id, email, ... } } }
 * This is NOT the same as TokenData — it only contains a user object.
 */
data class MeResponseData(
    @SerializedName("user") val user: UserData? = null
)

data class RefreshRequest(
    @SerializedName("refreshToken") val refreshToken: String
)

/**
 * Body of POST /api/auth/google. Sent from the Android app after a
 * successful Google Sign-In (Credential Manager or GoogleSignInClient).
 * The server verifies the ID token and returns the same [TokenData] as the
 * password login.
 */
data class GoogleAuthRequest(
    @SerializedName("idToken") val idToken: String,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("device_name") val deviceName: String? = null
)

// ====== Account linking (authenticated Google linking) ======

data class LinkGoogleRequest(
    @SerializedName("idToken") val idToken: String
)

data class LinkGoogleResponse(
    @SerializedName("linked") val linked: Boolean = true,
    @SerializedName("created") val created: Boolean = false,
    @SerializedName("email") val email: String? = null
)

// ====== Session recovery (release all active sessions via email OTP) ======

data class SessionRecoveryRequest(
    @SerializedName("email") val email: String
)

data class SessionRecoveryVerifyRequest(
    @SerializedName("email") val email: String,
    @SerializedName("code") val code: String
)

data class SessionRecoveryData(
    @SerializedName("sessionsRevoked") val sessionsRevoked: Boolean = true
)

// ====== File models ======

data class CloudFile(
    @SerializedName("id") val id: String,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("original_name") val originalName: String,
    @SerializedName("mime_type") val mimeType: String? = null,
    /** Backend stores this as `size_bytes` column but serializes it as `size` */
    @SerializedName("size") val size: Long? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("relative_path") val relativePath: String? = null,
    @SerializedName("checksum") val checksum: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("sync_updated_at") val syncUpdatedAt: String? = null,
    @SerializedName("is_deleted") val isDeleted: Boolean? = null,
    // ── Trash (papelera de reciclaje) metadata ──
    @SerializedName("deleted_at") val deletedAt: String? = null,
    /** Server-derived: "CLOUD_NORMAL" (files) or "QR_PAPELERA" (scans). */
    @SerializedName("trash_type") val trashType: String? = null,
    /** "TRASHED" or "ACTIVE". */
    @SerializedName("status") val status: String? = null,
    @SerializedName("trashed_at") val trashedAt: String? = null,
    /** Permanent-deletion deadline; the server cleanup deletes the row after it. */
    @SerializedName("scheduled_deletion_at") val scheduledDeletionAt: String? = null,
    /** Who moved the item to trash: "mobile" or "admin". */
    @SerializedName("trash_source") val trashSource: String? = null,
    // ── Logical export grouping ──
    @SerializedName("export_id") val exportId: String? = null,
    /** "file" or "folder" (root row of a multiple export) */
    @SerializedName("item_type") val itemType: String? = null,
    @SerializedName("item_count") val itemCount: Int? = null,
    @SerializedName("output_format") val outputFormat: String? = null,
    @SerializedName("is_cover") val isCover: Boolean? = null,
    /** Id of the cover file (folder rows only), used for the folder preview */
    @SerializedName("cover_file_id") val coverFileId: String? = null,
    // ── First-class folder hierarchy ──
    /** Parent folder id; null = root level. */
    @SerializedName("parent_id") val parentId: String? = null,
    /** Folder origin: "user" (manual) or "export" (multiple-image export). */
    @SerializedName("origin") val origin: String? = null,
    /** Traceability id of the export job that produced an export folder. */
    @SerializedName("export_job_id") val exportJobId: String? = null,
    /** Live count of active children (folder rows in level listings). */
    @SerializedName("live_items") val liveItems: Int? = null,
)

data class FileListData(
    @SerializedName("files") val files: List<CloudFile>
)

data class FileUploadResponse(
    @SerializedName("file") val file: CloudFile
)

/**
 * Body of PATCH /api/files/:id. [parentId] MOVES the item when present:
 * a folder id moves it into that folder, an empty string moves it to the
 * root, and null/absent leaves the location untouched (rename only).
 */
data class UpdateFileRequest(
    @SerializedName("original_name") val originalName: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("parentId") val parentId: String? = null
)

/**
 * Body of POST /api/files/folder.
 *
 * With [exportId] it creates (or idempotently reuses) the root row of a
 * multiple cloud export ([coverFileId] is the first uploaded file, used as
 * preview). Without [exportId] it creates a plain USER folder at [parentId]
 * (or at the root when null).
 */
data class CreateFolderRequest(
    @SerializedName("exportId") val exportId: String? = null,
    @SerializedName("name") val name: String,
    @SerializedName("format") val format: String? = null,
    @SerializedName("itemCount") val itemCount: Int? = null,
    @SerializedName("coverFileId") val coverFileId: String? = null,
    @SerializedName("parentId") val parentId: String? = null,
)

// ====== LibreOffice conversion (document → PDF) models ======

/** Per-file result inside a conversion job. */
data class ConversionItem(
    @SerializedName("itemId") val itemId: String? = null,
    @SerializedName("originalName") val originalName: String? = null,
    /** Backend file id of the produced PDF (only when status = "completed"). */
    @SerializedName("fileId") val fileId: String? = null,
    /** "queued" | "completed" | "failed" */
    @SerializedName("status") val status: String? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * A document → PDF conversion job.
 * [status]: "queued" | "processing" | "completed" | "partial" | "failed".
 */
data class ConversionJobData(
    @SerializedName("id") val id: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("progress") val progress: Int? = null,
    @SerializedName("total_files") val totalFiles: Int? = null,
    @SerializedName("converted_files") val convertedFiles: Int? = null,
    @SerializedName("failed_files") val failedFiles: Int? = null,
    @SerializedName("items") val items: List<ConversionItem>? = null
)

// ====== QR generator + cloud scan history models ======

data class QrGradientRequest(
    @SerializedName("type") val type: String = "linear",
    @SerializedName("colors") val colors: List<String>,
    @SerializedName("angle") val angle: Int? = null
)

data class QrFrameRequest(
    @SerializedName("text") val text: String,
    @SerializedName("style") val style: String = "normal",
    @SerializedName("margin") val margin: Int? = null
)

/** Design options for the QR generator (POST /api/qr/generate). */
data class QrDesignRequest(
    @SerializedName("foregroundColor") val foregroundColor: String? = null,
    @SerializedName("backgroundColor") val backgroundColor: String? = null,
    @SerializedName("gradient") val gradient: QrGradientRequest? = null,
    @SerializedName("moduleShape") val moduleShape: String? = null,
    @SerializedName("logo") val logo: String? = null,
    @SerializedName("backgroundImage") val backgroundImage: String? = null,
    @SerializedName("colorScheme") val colorScheme: String? = null,
    @SerializedName("size") val size: Int? = null,
    @SerializedName("scale") val scale: Int? = null,
    @SerializedName("errorCorrection") val errorCorrection: String? = null,
    @SerializedName("frame") val frame: QrFrameRequest? = null,
    @SerializedName("scanCheck") val scanCheck: Boolean? = null
)

/** Structured fields for wifi/email/sms/tel/vcard content types. */
data class QrFieldsRequest(
    @SerializedName("ssid") val ssid: String? = null,
    @SerializedName("password") val password: String? = null,
    @SerializedName("encryption") val encryption: String? = null,
    @SerializedName("to") val to: String? = null,
    @SerializedName("subject") val subject: String? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("org") val org: String? = null,
    @SerializedName("email") val email: String? = null
)

data class QrGenerateRequest(
    @SerializedName("data") val data: String,
    @SerializedName("type") val type: String? = null,
    @SerializedName("fields") val fields: QrFieldsRequest? = null,
    @SerializedName("design") val design: QrDesignRequest? = null,
    @SerializedName("format") val format: String? = null,
    @SerializedName("styleId") val styleId: String? = null
)

data class QrScanResult(
    @SerializedName("scannable") val scannable: Boolean? = null,
    @SerializedName("decoded") val decoded: Boolean? = null,
    @SerializedName("warnings") val warnings: List<String>? = null
)

/** Response of POST /api/qr/generate. */
data class QrGenerateData(
    @SerializedName("url") val url: String,
    @SerializedName("downloadUrl") val downloadUrl: String,
    @SerializedName("format") val format: String,
    @SerializedName("filename") val filename: String,
    @SerializedName("warnings") val warnings: List<String>? = null,
    @SerializedName("scan") val scan: QrScanResult? = null,
    @SerializedName("cached") val cached: Boolean? = null
)

/** Predefined design template from GET /api/qr/styles. */
data class QrStyle(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("design") val design: QrDesignRequest? = null
)

data class QrStylesData(
    @SerializedName("styles") val styles: List<QrStyle>
)

/** A scan stored in the cloud (GET /api/scans). */
data class QrScan(
    @SerializedName("id") val id: String,
    @SerializedName("content") val content: String,
    @SerializedName("format") val format: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("type_data") val typeData: String? = null,
    @SerializedName("design") val design: String? = null,
    @SerializedName("image_file_id") val imageFileId: String? = null,
    @SerializedName("scanned_at") val scannedAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    // ── Trash (papelera de reciclaje) metadata ──
    @SerializedName("deleted_at") val deletedAt: String? = null,
    /** Server-derived: "CLOUD_NORMAL" (files) or "QR_PAPELERA" (scans). */
    @SerializedName("trash_type") val trashType: String? = null,
    /** "TRASHED" or "ACTIVE". */
    @SerializedName("status") val status: String? = null,
    @SerializedName("trashed_at") val trashedAt: String? = null,
    /** Permanent-deletion deadline; the server cleanup deletes the row after it. */
    @SerializedName("scheduled_deletion_at") val scheduledDeletionAt: String? = null,
    /** Who moved the item to trash: "mobile" or "admin". */
    @SerializedName("trash_source") val trashSource: String? = null,
)

data class QrScansData(
    @SerializedName("scans") val scans: List<QrScan>
)

// ====== Maintenance models ======

/**
 * Cloud maintenance status (public endpoint, no auth required).
 *
 * [version] is the server-side monotonic state version: it increases on every
 * lifecycle transition (update/activate/complete/cancel) so a client holding
 * a persisted snapshot can discard stale responses and out-of-order pushes.
 * [updatedAt] mirrors the record's last change (ISO-8601). Both default to
 * "unknown" when talking to an older backend.
 */
data class MaintenanceStatus(
    @SerializedName("maintenance_active") val maintenanceActive: Boolean = false,
    @SerializedName("maintenance_scheduled") val maintenanceScheduled: Boolean = false,
    @SerializedName("id") val id: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("title_key") val titleKey: String? = null,
    @SerializedName("message_key") val messageKey: String? = null,
    @SerializedName("reason_key") val reasonKey: String? = null,
    @SerializedName("starts_at") val startsAt: String? = null,
    @SerializedName("ends_at") val endsAt: String? = null,
    @SerializedName("timezone") val timezone: String? = null,
    @SerializedName("retry_after") val retryAfter: Int = 30,
    @SerializedName("version") val version: Long = 0,
    @SerializedName("updated_at") val updatedAt: String? = null
)

// ====== FCM device registration (push notifications) models ======

/**
 * Body of POST /api/devices/fcm-token. The device persisted on the server is:
 *   user_id (from the Bearer JWT, never sent by the client),
 *   device_id (stable per app install, generated by the app),
 *   fcm_token (registration token from the Firebase SDK).
 */
data class RegisterFcmTokenRequest(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("fcmToken") val fcmToken: String,
    @SerializedName("platform") val platform: String = "android",
)

// ====== User preferences (language sync) + account status models ======

/**
 * Body of PUT /api/user/preferences/language. The stored language drives the
 * language of every backend account email (inactivity-deletion warning and
 * confirmation included). Accepts regional variants ('es-419', 'pt-BR'): the
 * backend reduces them to the base code before persisting.
 */
data class UpdateLanguageRequest(
    @SerializedName("language") val language: String,
)

/** Payload of GET /api/user/preferences. */
data class UserPreferencesData(
    @SerializedName("preferred_language") val preferredLanguage: String? = null,
    @SerializedName("require_login_code") val requireLoginCode: Boolean? = null,
)

// ====== Billing models (Google Play, legacy 4 productIds) ======

data class BillingGooglePlay(
    @SerializedName("productId") val productId: String? = null,
    @SerializedName("basePlanIdMonthly") val basePlanIdMonthly: String? = null,
    @SerializedName("basePlanIdAnnual") val basePlanIdAnnual: String? = null,
    @SerializedName("offerIdMonthly") val offerIdMonthly: String? = null,
    @SerializedName("offerIdAnnual") val offerIdAnnual: String? = null
)

data class BillingPlan(
    @SerializedName("id") val id: String,
    @SerializedName("storageLimitBytes") val storageLimitBytes: Long = 0L,
    @SerializedName("displayNameKey") val displayNameKey: String? = null,
    @SerializedName("descriptionKey") val descriptionKey: String? = null,
    @SerializedName("googlePlay") val googlePlay: BillingGooglePlay? = null,
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("legacy") val legacy: Boolean = false,
    @SerializedName("sortOrder") val sortOrder: Int = 0
)

data class BillingPlansData(
    @SerializedName("plans") val plans: List<BillingPlan>
)

data class BillingStatusData(
    @SerializedName("plan") val plan: String? = null,
    @SerializedName("storage_limit_bytes") val storageLimitBytes: Long? = null,
    @SerializedName("google_product_id") val googleProductId: String? = null,
    @SerializedName("google_base_plan_id") val googleBasePlanId: String? = null,
    @SerializedName("subscription_status") val subscriptionStatus: String? = null,
    @SerializedName(
        value = "subscription_expires_at",
        alternate = ["expiry_time", "expiryTime"]
    ) val subscriptionExpiresAt: String? = null,
    @SerializedName("billing_period") val billingPeriod: String? = null,
    @SerializedName("isActiveEntitlement") val isActiveEntitlement: Boolean? = null,
    @SerializedName("entitlementReason") val entitlementReason: String? = null
)

data class GooglePlayVerifyRequest(
    @SerializedName("purchaseToken") val purchaseToken: String,
    @SerializedName("productId") val productId: String? = null
)

// ====== Session management (active devices) ======

/**
 * Avatar payload returned by GET/PUT/DELETE /api/profile/avatar.
 */
data class AvatarData(
    @SerializedName("url") val url: String? = null,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("provider") val provider: String? = null,
    @SerializedName("version") val version: Int = 0,
    @SerializedName("updatedAt") val updatedAt: String? = null
)

data class AvatarResponse(
    @SerializedName("avatar") val avatar: AvatarData? = null
)

/**
 * Single active session (device) returned by GET /api/sessions.
 * `isCurrent` is derived from JWT sid claim.
 */
data class SessionData(
    @SerializedName("id") val id: String,
    @SerializedName("deviceId") val deviceId: String? = null,
    @SerializedName("deviceName") val deviceName: String? = null,
    @SerializedName("ipAddress") val ipAddress: String? = null,
    @SerializedName("userAgent") val userAgent: String? = null,
    @SerializedName("expiresAt") val expiresAt: String? = null,
    @SerializedName("createdAt") val createdAt: String? = null,
    @SerializedName("lastSeenAt") val lastSeenAt: String? = null,
    @SerializedName("isCurrent") val isCurrent: Boolean = false
)

data class SessionsData(
    @SerializedName("sessions") val sessions: List<SessionData>,
    @SerializedName("activeCount") val activeCount: Int = 0,
    @SerializedName("maxConcurrent") val maxConcurrent: Int = 0,
    @SerializedName("plan") val plan: String? = null
)
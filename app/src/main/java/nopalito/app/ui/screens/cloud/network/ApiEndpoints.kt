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

package nopalito.app.ui.screens.cloud.network

/**
 * Central catalog of backend endpoints with their EXPLICIT, INDEPENDENT version.
 *
 * - [BuildConfig.API_BASE_URL] holds ONLY the server origin (e.g.
 *   `https://api.nopalitoscan.org/`). It MUST NOT contain a version: no
 *   interceptor, client or helper may append `/api/v1` automatically.
 * - Every entry below carries its own full versioned path. Evolving ONE
 *   operation (e.g. `FILES_LIST` from `api/v1/files` to `api/v2/files`)
 *   means editing ONLY that constant; every other endpoint keeps its
 *   version untouched.
 * - Legacy unversioned paths (`api/...`) remain served by the backend for
 *   already-published apps; new code MUST reference the versioned entries.
 */
object ApiEndpoints {
    /** Current API version shared by all endpoints below (per-endpoint override = change one const). */
    const val VERSION_V1 = "v1"

    // Auth
    const val AUTH_REGISTER = "api/v1/auth/register"
    const val AUTH_REGISTER_VERIFY_CODE = "api/v1/auth/register/verify-code"
    const val AUTH_LOGIN_PASSWORD = "api/v1/auth/login/password"
    const val AUTH_LOGIN_VERIFY_CODE = "api/v1/auth/login/verify-code"
    const val AUTH_RESEND_CODE = "api/v1/auth/resend-code"
    const val AUTH_PASSWORD_FORGOT = "api/v1/auth/password/forgot"
    const val AUTH_PASSWORD_RESET = "api/v1/auth/password/reset"
    const val AUTH_PASSWORD_SET_CODE = "api/v1/auth/password/set-code"
    const val AUTH_PASSWORD_SET = "api/v1/auth/password/set"
    const val AUTH_REFRESH = "api/v1/auth/refresh"
    const val AUTH_GOOGLE = "api/v1/auth/google"
    const val AUTH_SESSION_RECOVERY_REQUEST = "api/v1/auth/session-recovery/request"
    const val AUTH_SESSION_RECOVERY_VERIFY = "api/v1/auth/session-recovery/verify"
    const val AUTH_ME = "api/v1/auth/me"
    const val AUTH_LOGOUT = "api/v1/auth/logout"
    const val ACCOUNT_REQUEST_EMAIL_CHANGE = "api/v1/account/request-email-change"
    const val ACCOUNT_CONFIRM_EMAIL_CHANGE = "api/v1/account/confirm-email-change"
    const val ACCOUNT_LINK_GOOGLE = "api/v1/account/link-google"

    // Storage
    const val STORAGE_USAGE = "api/v1/storage/usage"

    // Files
    const val FILES_UPLOAD = "api/v1/files/upload"
    const val FILES_FOLDER = "api/v1/files/folder"
    const val FILES_LIST = "api/v1/files"
    const val FILES_BULK_DOWNLOAD = "api/v1/files/bulk/download"
    const val FILES_DOWNLOAD = "api/v1/files/{id}/download"
    const val FILES_ITEM = "api/v1/files/{id}"
    const val FILES_UPDATE = "api/v1/files/{id}"
    const val FILES_TRASH_LIST = "api/v1/files/trash"
    const val FILES_RESTORE = "api/v1/files/{id}/restore"
    const val FILES_PERMANENT = "api/v1/files/{id}/permanent"

    // LibreOffice conversion
    const val LIBREOFFICE_CONVERT = "api/v1/libreoffice/convert/pdf"
    const val LIBREOFFICE_JOB = "api/v1/libreoffice/jobs/{jobId}"
    const val LIBREOFFICE_DOWNLOAD = "api/v1/libreoffice/jobs/{jobId}/download/{fileId}"
    const val LIBREOFFICE_PREVIEW = "api/v1/libreoffice/preview/pdf"

    // QR
    const val QR_GENERATE = "api/v1/qr/generate"
    const val QR_STYLES = "api/v1/qr/styles"

    // Scans
    const val SCANS_PUSH = "api/v1/scans"
    const val SCANS_LIST = "api/v1/scans"
    const val SCANS_TRASH = "api/v1/scans/trash"
    const val SCANS_RESTORE = "api/v1/scans/{id}/restore"
    const val SCANS_PERMANENT = "api/v1/scans/{id}/permanent"
    const val SCANS_DELETE = "api/v1/scans/{id}"

    // Devices
    const val DEVICES_FCM_TOKEN = "api/v1/devices/fcm-token"

    // Maintenance (public)
    const val MAINTENANCE_STATUS = "api/v1/maintenance/status"

    // User preferences
    const val USER_PREFERENCES = "api/v1/user/preferences"
    const val USER_LANGUAGE = "api/v1/user/preferences/language"
    const val USER_LOGIN_CODE = "api/v1/user/preferences/login-code"

    // Billing
    const val BILLING_PLANS = "api/v1/billing/plans"
    const val BILLING_STATUS = "api/v1/billing/status"
    const val BILLING_GOOGLE_VERIFY = "api/v1/billing/google/verify"

    // Avatar
    const val AVATAR = "api/v1/profile/avatar"

    // Sessions
    const val SESSIONS_LIST = "api/v1/sessions"
    const val SESSIONS_REVOKE = "api/v1/sessions/{id}/revoke"

    // Cloud Link
    const val CLOUD_LINK_APPROVE = "api/v1/cloud-link/intents/approve"
    const val CLOUD_LINK_APPROVE_BY_PIN = "api/v1/cloud-link/intents/approve-by-pin"
    const val CLOUD_LINK_SESSIONS = "api/v1/cloud-link/sessions"
    const val CLOUD_LINK_SESSION = "api/v1/cloud-link/sessions/{id}"
}

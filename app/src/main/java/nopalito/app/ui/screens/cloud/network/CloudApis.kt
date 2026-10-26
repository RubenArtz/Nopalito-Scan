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

import nopalito.app.ui.screens.cloud.model.ApiResponse
import nopalito.app.ui.screens.cloud.model.ApproveByPinRequest
import nopalito.app.ui.screens.cloud.model.ApproveQrRequest
import nopalito.app.ui.screens.cloud.model.AuthCodeRequest
import nopalito.app.ui.screens.cloud.model.AuthCodeResponseData
import nopalito.app.ui.screens.cloud.model.CloudLinkSessionsData
import nopalito.app.ui.screens.cloud.model.ConfirmEmailChangeRequest
import nopalito.app.ui.screens.cloud.model.ConversionJobData
import nopalito.app.ui.screens.cloud.model.CreateFolderRequest
import nopalito.app.ui.screens.cloud.model.EmailChangeRequest
import nopalito.app.ui.screens.cloud.model.FileListData
import nopalito.app.ui.screens.cloud.model.FileUploadResponse
import nopalito.app.ui.screens.cloud.model.LoginPasswordRequest
import nopalito.app.ui.screens.cloud.model.LoginPasswordResponse
import nopalito.app.ui.screens.cloud.model.MaintenanceStatus
import nopalito.app.ui.screens.cloud.model.MeResponseData
import nopalito.app.ui.screens.cloud.model.NewPasswordWithCodeRequest
import nopalito.app.ui.screens.cloud.model.QrGenerateData
import nopalito.app.ui.screens.cloud.model.QrGenerateRequest
import nopalito.app.ui.screens.cloud.model.QrScansData
import nopalito.app.ui.screens.cloud.model.QrStylesData
import nopalito.app.ui.screens.cloud.model.RefreshRequest
import nopalito.app.ui.screens.cloud.model.RegisterFcmTokenRequest
import nopalito.app.ui.screens.cloud.model.RegisterRequest
import nopalito.app.ui.screens.cloud.model.StorageUsage
import nopalito.app.ui.screens.cloud.model.TokenData
import nopalito.app.ui.screens.cloud.model.UpdateFileRequest
import nopalito.app.ui.screens.cloud.model.UpdateLanguageRequest
import nopalito.app.ui.screens.cloud.model.UpdateLoginCodeRequest
import nopalito.app.ui.screens.cloud.model.UserPreferencesData
import nopalito.app.ui.screens.cloud.model.VerifyCodeRequest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface AuthApi {
    @POST(ApiEndpoints.AUTH_REGISTER)
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<ApiResponse<AuthCodeResponseData>>

    @POST(ApiEndpoints.AUTH_REGISTER_VERIFY_CODE)
    suspend fun registerVerifyCode(
        @Body request: VerifyCodeRequest
    ): Response<ApiResponse<TokenData>>

    @POST(ApiEndpoints.AUTH_LOGIN_PASSWORD)
    suspend fun loginWithPassword(
        @Body request: LoginPasswordRequest
    ): Response<ApiResponse<LoginPasswordResponse>>

    @POST(ApiEndpoints.AUTH_LOGIN_VERIFY_CODE)
    suspend fun loginVerifyCode(
        @Body request: VerifyCodeRequest
    ): Response<ApiResponse<TokenData>>

    @POST(ApiEndpoints.AUTH_RESEND_CODE)
    suspend fun resendCode(
        @Body request: AuthCodeRequest
    ): Response<ApiResponse<AuthCodeResponseData>>

    // Password flows

    @POST(ApiEndpoints.AUTH_PASSWORD_FORGOT)
    suspend fun forgotPassword(
        @Body request: AuthCodeRequest
    ): Response<ApiResponse<AuthCodeResponseData>>

    @POST(ApiEndpoints.AUTH_PASSWORD_RESET)
    suspend fun resetPassword(
        @Body request: NewPasswordWithCodeRequest
    ): Response<ApiResponse<TokenData>>

    @POST(ApiEndpoints.AUTH_PASSWORD_SET_CODE)
    suspend fun setPasswordCode(
        @Body request: AuthCodeRequest
    ): Response<ApiResponse<AuthCodeResponseData>>

    @POST(ApiEndpoints.AUTH_PASSWORD_SET)
    suspend fun setPassword(
        @Body request: NewPasswordWithCodeRequest
    ): Response<ApiResponse<TokenData>>

    @POST(ApiEndpoints.AUTH_REFRESH)
    suspend fun refreshToken(
        @Body request: RefreshRequest
    ): Response<ApiResponse<TokenData>>

    @POST(ApiEndpoints.AUTH_GOOGLE)
    suspend fun googleAuth(
        @Body request: nopalito.app.ui.screens.cloud.model.GoogleAuthRequest
    ): Response<ApiResponse<TokenData>>

    @POST(ApiEndpoints.AUTH_SESSION_RECOVERY_REQUEST)
    suspend fun requestSessionRecovery(
        @Body request: nopalito.app.ui.screens.cloud.model.SessionRecoveryRequest
    ): Response<ApiResponse<AuthCodeResponseData>>

    @POST(ApiEndpoints.AUTH_SESSION_RECOVERY_VERIFY)
    suspend fun verifySessionRecovery(
        @Body request: nopalito.app.ui.screens.cloud.model.SessionRecoveryVerifyRequest
    ): Response<ApiResponse<nopalito.app.ui.screens.cloud.model.SessionRecoveryData>>

    @GET(ApiEndpoints.AUTH_ME)
    suspend fun getMe(): Response<ApiResponse<MeResponseData>>

    @POST(ApiEndpoints.AUTH_LOGOUT)
    suspend fun logout(
        @Body request: RefreshRequest
    ): Response<ApiResponse<Unit>>

    /** Sid-based logout — authenticated by Bearer JWT, body optional/ignored. */
    @POST(ApiEndpoints.AUTH_LOGOUT)
    suspend fun logoutSid(): Response<ApiResponse<Unit>>

    @POST(ApiEndpoints.ACCOUNT_REQUEST_EMAIL_CHANGE)
    suspend fun requestEmailChange(
        @Body request: EmailChangeRequest
    ): Response<ApiResponse<AuthCodeResponseData>>

    @POST(ApiEndpoints.ACCOUNT_CONFIRM_EMAIL_CHANGE)
    suspend fun confirmEmailChange(
        @Body request: ConfirmEmailChangeRequest
    ): Response<ApiResponse<TokenData>>

    @POST(ApiEndpoints.ACCOUNT_LINK_GOOGLE)
    suspend fun linkGoogle(
        @Body request: nopalito.app.ui.screens.cloud.model.LinkGoogleRequest
    ): Response<ApiResponse<nopalito.app.ui.screens.cloud.model.LinkGoogleResponse>>
}

/** Server-authoritative storage usage (the client only displays it). */
interface StorageApi {
    @GET(ApiEndpoints.STORAGE_USAGE)
    suspend fun getUsage(): Response<ApiResponse<StorageUsage>>
}

interface FileApi {
    @Multipart
    @POST(ApiEndpoints.FILES_UPLOAD)
    suspend fun uploadFile(
        @Part file: MultipartBody.Part,
        @Part("category") category: RequestBody? = null,
        // Optional export-grouping metadata
        @Part("exportId") exportId: RequestBody? = null,
        @Part("itemType") itemType: RequestBody? = null,
        @Part("outputFormat") outputFormat: RequestBody? = null,
        @Part("itemCount") itemCount: RequestBody? = null,
        @Part("isCover") isCover: RequestBody? = null,
        /** Target folder for plain uploads (null = root). */
        @Part("parentId") parentId: RequestBody? = null
    ): Response<ApiResponse<FileUploadResponse>>

    /** Creates the root row (folder) of a multiple export. */
    @POST(ApiEndpoints.FILES_FOLDER)
    suspend fun createFolderGroup(
        @Body body: CreateFolderRequest
    ): Response<ApiResponse<FileUploadResponse>>

    @GET(ApiEndpoints.FILES_LIST)
    suspend fun listFiles(
        @Query("category") category: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        @Query("exportId") exportId: String? = null,
        // Level listing: an empty string requests the ROOT level including
        // folders; a folder id lists that level; null keeps the legacy flat
        // (file-only) response for old callers.
        @Query("parentId") parentId: String? = null
    ): Response<ApiResponse<FileListData>>

    /** Bulk ZIP download of ACTIVE items (folders expand to their subtree). */
    @Streaming
    @GET(ApiEndpoints.FILES_BULK_DOWNLOAD)
    suspend fun downloadBulkZip(
        @Query("ids") ids: String
    ): Response<ResponseBody>

    @Streaming
    @GET(ApiEndpoints.FILES_DOWNLOAD)
    suspend fun downloadFile(
        @Path("id") fileId: String
    ): Response<ResponseBody>

    @DELETE(ApiEndpoints.FILES_ITEM)
    suspend fun deleteFile(
        @Path("id") fileId: String
    ): Response<ApiResponse<Unit>>

    @PATCH(ApiEndpoints.FILES_UPDATE)
    suspend fun updateFile(
        @Path("id") fileId: String,
        @Body request: UpdateFileRequest
    ): Response<ApiResponse<FileUploadResponse>>

    // Trash
    @GET(ApiEndpoints.FILES_TRASH_LIST)
    suspend fun listDeletedFiles(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        /** One level INSIDE a trashed folder (trash browsing). */
        @Query("parentId") parentId: String? = null
    ): Response<ApiResponse<FileListData>>

    @POST(ApiEndpoints.FILES_RESTORE)
    suspend fun restoreFile(
        @Path("id") fileId: String
    ): Response<ApiResponse<FileUploadResponse>>

    @DELETE(ApiEndpoints.FILES_PERMANENT)
    suspend fun permanentlyDeleteFile(
        @Path("id") fileId: String
    ): Response<ApiResponse<Unit>>
}

/** Server-side document → PDF conversion (LibreOffice backend jobs). */
interface LibreOfficeApi {
    @Multipart
    @POST(ApiEndpoints.LIBREOFFICE_CONVERT)
    suspend fun convertToPdf(
        @Part files: List<MultipartBody.Part>
    ): Response<ApiResponse<ConversionJobData>>

    @GET(ApiEndpoints.LIBREOFFICE_JOB)
    suspend fun getJob(
        @Path("jobId") jobId: String
    ): Response<ApiResponse<ConversionJobData>>

    @Streaming
    @GET(ApiEndpoints.LIBREOFFICE_DOWNLOAD)
    suspend fun downloadConvertedFile(
        @Path("jobId") jobId: String,
        @Path("fileId") fileId: String
    ): Response<ResponseBody>

    /**
     * Ephemeral synchronous conversion: uploads one document and returns the
     * converted PDF bytes directly. The backend does not persist the result,
     * so this is ideal for on-the-fly previews of third-party documents.
     */
    @Multipart
    @Streaming
    @POST(ApiEndpoints.LIBREOFFICE_PREVIEW)
    suspend fun previewToPdf(
        @Part file: MultipartBody.Part
    ): Response<ResponseBody>
}

/** Highly customizable QR generation (Python subprocess backend). */
interface QrApi {
    @POST(ApiEndpoints.QR_GENERATE)
    suspend fun generate(
        @Body request: QrGenerateRequest
    ): Response<ApiResponse<QrGenerateData>>

    @GET(ApiEndpoints.QR_STYLES)
    suspend fun listStyles(): Response<ApiResponse<QrStylesData>>
}

/** Cloud scan history (QR/barcode scans pushed from the device). */
interface ScanApi {
    @Multipart
    @POST(ApiEndpoints.SCANS_PUSH)
    suspend fun pushScans(
        @Part("metadata") metadata: RequestBody,
        @Part images: List<MultipartBody.Part> = emptyList()
    ): Response<ApiResponse<QrScansData>>

    @GET(ApiEndpoints.SCANS_LIST)
    suspend fun listScans(): Response<ApiResponse<QrScansData>>

    @GET(ApiEndpoints.SCANS_TRASH)
    suspend fun listTrash(): Response<ApiResponse<QrScansData>>

    @POST(ApiEndpoints.SCANS_RESTORE)
    suspend fun restoreScan(@Path("id") id: String): Response<ApiResponse<Unit>>

    @DELETE(ApiEndpoints.SCANS_PERMANENT)
    suspend fun permanentlyDeleteScan(@Path("id") id: String): Response<ApiResponse<Unit>>

    @DELETE(ApiEndpoints.SCANS_DELETE)
    suspend fun deleteScan(@Path("id") id: String): Response<ApiResponse<Unit>>
}

/**
 * FCM device registration (push notifications). The fcm_token is sent ONLY to
 * the authenticated backend endpoint (Bearer JWT added by AuthInterceptor).
 * The app never contacts Firebase or holds a Firebase secret.
 */
interface DeviceApi {
    @POST(ApiEndpoints.DEVICES_FCM_TOKEN)
    suspend fun registerFcmToken(
        @Body request: RegisterFcmTokenRequest
    ): Response<ApiResponse<Unit>>

}

/** Cloud maintenance status (public endpoint, no auth required). */
interface MaintenanceApi {
    @GET(ApiEndpoints.MAINTENANCE_STATUS)
    suspend fun getMaintenanceStatus(): Response<ApiResponse<MaintenanceStatus>>
}

/**
 * User preferences + account lifecycle (JWT protected; Bearer added by
 * AuthInterceptor). The language persisted here controls the language of the
 * backend account emails, so changing it in Settings keeps those emails in
 * sync with the app.
 */
interface UserPreferencesApi {
    @GET(ApiEndpoints.USER_PREFERENCES)
    suspend fun getPreferences(): Response<ApiResponse<UserPreferencesData>>

    @PUT(ApiEndpoints.USER_LANGUAGE)
    suspend fun updateLanguage(
        @Body request: UpdateLanguageRequest
    ): Response<ApiResponse<Unit>>

    @PUT(ApiEndpoints.USER_LOGIN_CODE)
    suspend fun updateLoginCodePreference(
        @Body request: UpdateLoginCodeRequest
    ): Response<ApiResponse<UserPreferencesData>>
}

/**
 * Billing (Google Play) — prices are never served; Android uses ProductDetails.formattedPrice only.
 */
interface BillingApi {
    @GET(ApiEndpoints.BILLING_PLANS)
    suspend fun getPlans(): Response<ApiResponse<nopalito.app.ui.screens.cloud.model.BillingPlansData>>

    @GET(ApiEndpoints.BILLING_STATUS)
    suspend fun getStatus(): Response<ApiResponse<nopalito.app.ui.screens.cloud.model.BillingStatusData>>

    @POST(ApiEndpoints.BILLING_GOOGLE_VERIFY)
    suspend fun verifyGooglePlay(
        @Body request: nopalito.app.ui.screens.cloud.model.GooglePlayVerifyRequest
    ): Response<ApiResponse<Map<String, Any>>>
}

/**
 * Profile avatar (separate subsystem, public URLs with ?v= version).
 */
interface AvatarApi {
    @GET(ApiEndpoints.AVATAR)
    suspend fun getAvatar(): Response<ApiResponse<nopalito.app.ui.screens.cloud.model.AvatarResponse>>

    @Multipart
    @PUT(ApiEndpoints.AVATAR)
    suspend fun uploadAvatar(
        @Part avatar: MultipartBody.Part
    ): Response<ApiResponse<nopalito.app.ui.screens.cloud.model.AvatarResponse>>

    @DELETE(ApiEndpoints.AVATAR)
    suspend fun deleteAvatar(): Response<ApiResponse<nopalito.app.ui.screens.cloud.model.AvatarResponse>>
}

/**
 * Active sessions (devices) — JWT protected.
 */
interface SessionApi {
    @GET(ApiEndpoints.SESSIONS_LIST)
    suspend fun listSessions(): Response<ApiResponse<nopalito.app.ui.screens.cloud.model.SessionsData>>

    @POST(ApiEndpoints.SESSIONS_REVOKE)
    suspend fun revokeSession(@Path("id") sessionId: String): Response<ApiResponse<Map<String, Any>>>
}

/**
 * Cloud Link (Receive scans on your PC) — QR+PIN pairing approval from the
 * authenticated app plus web-session listing/revocation. The Bearer JWT is
 * added by AuthInterceptor, so no explicit token parameters are needed.
 */
interface CloudLinkApi {
    /** Approve the pairing shown on the PC by scanning its QR code. */
    @POST(ApiEndpoints.CLOUD_LINK_APPROVE)
    suspend fun approveQr(
        @Body request: ApproveQrRequest
    ): Response<ApiResponse<Unit>>

    /** Approve the pairing by typing the 6-digit PIN shown on the PC. */
    @POST(ApiEndpoints.CLOUD_LINK_APPROVE_BY_PIN)
    suspend fun approveByPin(
        @Body request: ApproveByPinRequest
    ): Response<ApiResponse<Unit>>

    /** Browsers currently linked to this account. */
    @GET(ApiEndpoints.CLOUD_LINK_SESSIONS)
    suspend fun listSessions(): Response<ApiResponse<CloudLinkSessionsData>>

    /** Unlink one browser remotely. */
    @DELETE(ApiEndpoints.CLOUD_LINK_SESSION)
    suspend fun revokeSession(
        @Path("id") sessionId: String
    ): Response<ApiResponse<Unit>>
}
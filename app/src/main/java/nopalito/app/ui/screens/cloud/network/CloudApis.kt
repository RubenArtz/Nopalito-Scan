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

import nopalito.app.ui.screens.cloud.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface AuthApi {
    @POST("api/auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<ApiResponse<AuthCodeResponseData>>

    @POST("api/auth/register/verify-code")
    suspend fun registerVerifyCode(
        @Body request: VerifyCodeRequest
    ): Response<ApiResponse<TokenData>>

    @POST("api/auth/login/password")
    suspend fun loginWithPassword(
        @Body request: LoginPasswordRequest
    ): Response<ApiResponse<AuthCodeResponseData>>

    @POST("api/auth/login/verify-code")
    suspend fun loginVerifyCode(
        @Body request: VerifyCodeRequest
    ): Response<ApiResponse<TokenData>>

    @POST("api/auth/resend-code")
    suspend fun resendCode(
        @Body request: AuthCodeRequest
    ): Response<ApiResponse<AuthCodeResponseData>>

    // ── Password flows ──

    @POST("api/auth/password/forgot")
    suspend fun forgotPassword(
        @Body request: AuthCodeRequest
    ): Response<ApiResponse<AuthCodeResponseData>>

    @POST("api/auth/password/reset")
    suspend fun resetPassword(
        @Body request: NewPasswordWithCodeRequest
    ): Response<ApiResponse<TokenData>>

    @POST("api/auth/password/set-code")
    suspend fun setPasswordCode(
        @Body request: AuthCodeRequest
    ): Response<ApiResponse<AuthCodeResponseData>>

    @POST("api/auth/password/set")
    suspend fun setPassword(
        @Body request: NewPasswordWithCodeRequest
    ): Response<ApiResponse<TokenData>>

    @POST("api/auth/refresh")
    suspend fun refreshToken(
        @Body request: RefreshRequest
    ): Response<ApiResponse<TokenData>>

    @GET("api/auth/me")
    suspend fun getMe(): Response<ApiResponse<MeResponseData>>

    @POST("api/auth/logout")
    suspend fun logout(
        @Body request: RefreshRequest
    ): Response<ApiResponse<Unit>>
}

/** Server-authoritative storage usage (the client only displays it). */
interface StorageApi {
    @GET("api/storage/usage")
    suspend fun getUsage(): Response<ApiResponse<StorageUsage>>
}

interface FileApi {
    @Multipart
    @POST("api/files/upload")
    suspend fun uploadFile(
        @Part file: MultipartBody.Part,
        @Part("category") category: RequestBody? = null,
        // Optional export-grouping metadata
        @Part("exportId") exportId: RequestBody? = null,
        @Part("itemType") itemType: RequestBody? = null,
        @Part("outputFormat") outputFormat: RequestBody? = null,
        @Part("itemCount") itemCount: RequestBody? = null,
        @Part("isCover") isCover: RequestBody? = null
    ): Response<ApiResponse<FileUploadResponse>>

    /** Creates the root row (folder) of a multiple export. */
    @POST("api/files/folder")
    suspend fun createFolderGroup(
        @Body body: CreateFolderRequest
    ): Response<ApiResponse<FileUploadResponse>>

    /** Lists the multiple exports (folder rows). */
    @GET("api/files/exports")
    suspend fun listExportGroups(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): Response<ApiResponse<ExportGroupsData>>

    @GET("api/files")
    suspend fun listFiles(
        @Query("category") category: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        @Query("exportId") exportId: String? = null
    ): Response<ApiResponse<FileListData>>

    @Streaming
    @GET("api/files/{id}/download")
    suspend fun downloadFile(
        @Path("id") fileId: String
    ): Response<ResponseBody>

    @DELETE("api/files/{id}")
    suspend fun deleteFile(
        @Path("id") fileId: String
    ): Response<ApiResponse<Unit>>

    @PATCH("api/files/{id}")
    suspend fun updateFile(
        @Path("id") fileId: String,
        @Body request: UpdateFileRequest
    ): Response<ApiResponse<FileUploadResponse>>

    // ── Trash ──
    @GET("api/files/trash")
    suspend fun listDeletedFiles(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): Response<ApiResponse<FileListData>>

    @POST("api/files/{id}/restore")
    suspend fun restoreFile(
        @Path("id") fileId: String
    ): Response<ApiResponse<FileUploadResponse>>

    @DELETE("api/files/{id}/permanent")
    suspend fun permanentlyDeleteFile(
        @Path("id") fileId: String
    ): Response<ApiResponse<Unit>>
}

/** Server-side document → PDF conversion (LibreOffice backend jobs). */
interface LibreOfficeApi {
    @Multipart
    @POST("api/libreoffice/convert/pdf")
    suspend fun convertToPdf(
        @Part files: List<MultipartBody.Part>
    ): Response<ApiResponse<ConversionJobData>>

    @GET("api/libreoffice/jobs/{jobId}")
    suspend fun getJob(
        @Path("jobId") jobId: String
    ): Response<ApiResponse<ConversionJobData>>

    @Streaming
    @GET("api/libreoffice/jobs/{jobId}/download/{fileId}")
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
    @POST("api/libreoffice/preview/pdf")
    suspend fun previewToPdf(
        @Part file: MultipartBody.Part
    ): Response<ResponseBody>
}

/** Highly customizable QR generation (Python subprocess backend). */
interface QrApi {
    @POST("api/qr/generate")
    suspend fun generate(
        @Body request: QrGenerateRequest
    ): Response<ApiResponse<QrGenerateData>>

    @GET("api/qr/styles")
    suspend fun listStyles(): Response<ApiResponse<QrStylesData>>
}

/** Cloud scan history (QR/barcode scans pushed from the device). */
interface ScanApi {
    @Multipart
    @POST("api/scans")
    suspend fun pushScans(
        @Part("metadata") metadata: RequestBody,
        @Part images: List<MultipartBody.Part> = emptyList()
    ): Response<ApiResponse<QrScansData>>

    @GET("api/scans")
    suspend fun listScans(): Response<ApiResponse<QrScansData>>

    @GET("api/scans/trash")
    suspend fun listTrash(): Response<ApiResponse<QrScansData>>

    @POST("api/scans/{id}/restore")
    suspend fun restoreScan(@Path("id") id: String): Response<ApiResponse<Unit>>

    @DELETE("api/scans/{id}/permanent")
    suspend fun permanentlyDeleteScan(@Path("id") id: String): Response<ApiResponse<Unit>>

    @DELETE("api/scans/{id}")
    suspend fun deleteScan(@Path("id") id: String): Response<ApiResponse<Unit>>
}

/**
 * FCM device registration (push notifications). The fcm_token is sent ONLY to
 * the authenticated backend endpoint (Bearer JWT added by AuthInterceptor).
 * The app never contacts Firebase or holds a Firebase secret.
 */
interface DeviceApi {
    @POST("api/devices/fcm-token")
    suspend fun registerFcmToken(
        @Body request: RegisterFcmTokenRequest
    ): Response<ApiResponse<Unit>>

}

/** Cloud maintenance status (public endpoint, no auth required). */
interface MaintenanceApi {
    @GET("api/maintenance/status")
    suspend fun getMaintenanceStatus(): Response<ApiResponse<MaintenanceStatus>>
}
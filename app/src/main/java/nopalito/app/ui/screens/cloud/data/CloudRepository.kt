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
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.DownloadLocation
import nopalito.app.ui.screens.cloud.model.ApiDetails
import nopalito.app.ui.screens.cloud.model.ApiResponse
import nopalito.app.ui.screens.cloud.model.AuthCodeRequest
import nopalito.app.ui.screens.cloud.model.AuthCodeResponseData
import nopalito.app.ui.screens.cloud.model.CloudFile
import nopalito.app.ui.screens.cloud.model.ConfirmEmailChangeRequest
import nopalito.app.ui.screens.cloud.model.CreateFolderRequest
import nopalito.app.ui.screens.cloud.model.EmailChangeRequest
import nopalito.app.ui.screens.cloud.model.LoginPasswordRequest
import nopalito.app.ui.screens.cloud.model.LoginPasswordResponse
import nopalito.app.ui.screens.cloud.model.MaintenanceStatus
import nopalito.app.ui.screens.cloud.model.NewPasswordWithCodeRequest
import nopalito.app.ui.screens.cloud.model.QrGenerateData
import nopalito.app.ui.screens.cloud.model.QrGenerateRequest
import nopalito.app.ui.screens.cloud.model.QrScan
import nopalito.app.ui.screens.cloud.model.QrStyle
import nopalito.app.ui.screens.cloud.model.RefreshRequest
import nopalito.app.ui.screens.cloud.model.RegisterRequest
import nopalito.app.ui.screens.cloud.model.StorageUsage
import nopalito.app.ui.screens.cloud.model.TokenData
import nopalito.app.ui.screens.cloud.model.UpdateFileRequest
import nopalito.app.ui.screens.cloud.model.UpdateLanguageRequest
import nopalito.app.ui.screens.cloud.model.UpdateLoginCodeRequest
import nopalito.app.ui.screens.cloud.model.UserPreferencesData
import nopalito.app.ui.screens.cloud.model.VerifyCodeRequest
import nopalito.app.ui.screens.cloud.network.AuthApi
import nopalito.app.ui.screens.cloud.network.AvatarApi
import nopalito.app.ui.screens.cloud.network.CloudApiClient
import nopalito.app.ui.screens.cloud.network.FileApi
import nopalito.app.ui.screens.cloud.network.MaintenanceApi
import nopalito.app.ui.screens.cloud.network.QrApi
import nopalito.app.ui.screens.cloud.network.ScanApi
import nopalito.app.ui.screens.cloud.network.SessionApi
import nopalito.app.ui.screens.cloud.network.StorageApi
import nopalito.app.ui.screens.cloud.network.UserPreferencesApi
import nopalito.app.ui.screens.cloud.security.BiometricRefreshBridge
import nopalito.app.ui.screens.cloud.security.BiometricSessionManager
import nopalito.app.ui.screens.cloud.security.BiometricSessionRefresher
import nopalito.app.ui.screens.cloud.security.BiometricUnlockOutcome
import nopalito.app.ui.screens.export.ExportFormat
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.UUID as JUUID

private const val TAG = "CloudRepository"

/** A scan to push to the cloud history (POST /api/scans). */
data class QrScanUpload(
    val content: String,
    val type: String = "text",
    val typeData: String? = null,
    val format: String? = null,
    val design: String? = null,
    val scannedAt: Long = System.currentTimeMillis()
)

/** Phases of the bulk ZIP export shown to the user. */
enum class BulkZipPhase { PREPARING, DOWNLOADING }

class CloudRepository(private val context: Context) {
    private val apiClient = CloudApiClient.getInstance(context)
    private val fileApi: FileApi = apiClient.files
    private val authApi: AuthApi = apiClient.auth
    private val storageApi: StorageApi = apiClient.storage
    private val qrApi: QrApi = apiClient.qr
    private val scanApi: ScanApi = apiClient.scans
    private val maintenanceApi: MaintenanceApi = apiClient.maintenance
    private val userPreferencesApi: UserPreferencesApi = apiClient.userPreferences
    private val sessionApi: SessionApi = apiClient.sessionApi
    private val avatarApi: AvatarApi = apiClient.avatarApi
    private val tokenProvider = apiClient.tokenProviderInstance
    private val biometricSessionManager: BiometricSessionManager =
        apiClient.biometricSessionManagerInstance
    private val biometricRefreshBridge = BiometricRefreshBridge(biometricSessionManager)
    private val biometricSessionRefresher =
        BiometricSessionRefresher(biometricSessionManager.unlockSession) { refreshToken ->
            safeApiCall { authApi.refreshToken(RefreshRequest(refreshToken)) }
        }

    /**
     * Stable per-install device identifier for session tracking.
     * Uses SharedPreferences persisted UUID; English comments per project rule.
     */
    private fun deviceId(): String {
        val prefs = context.getSharedPreferences("nopalito_device", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id.isNullOrBlank()) {
            id = JUUID.randomUUID().toString()
            prefs.edit { putString("device_id", id) }
        }
        return id
    }

    private fun deviceName(): String {
        val raw = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        return if (raw.isBlank()) "Android device" else raw.take(120)
    }

    // ====== Session management (active devices) ======
    suspend fun listSessions(): Result<nopalito.app.ui.screens.cloud.model.SessionsData> {
        return safeApiCall { sessionApi.listSessions() }
    }

    suspend fun revokeSession(sessionId: String): Result<Map<String, Any>> {
        return safeApiCall { sessionApi.revokeSession(sessionId) }
    }

    /**
     * Public handle to the app-wide [BiometricSessionManager], so the gate
     * ViewModel can drive the unlock prompt. Ownership stays in
     * [CloudApiClient]; this only exposes the reference.
     */
    fun biometricSessionManager(): BiometricSessionManager = biometricSessionManager

    /**
     * Turns biometric cloud unlock on/off from the storage screen.
     *
     * Enable: migrates the current refresh token into the auth-bound blob (one
     * OS prompt), removing the normal copy only after the encrypted one is
     * verified — the same transaction [migrateRefreshToBiometric] runs.
     *
     * Disable: decrypts the refresh token out of the blob with the in-memory
     * Tier-2 (active while the user is on an authenticated screen), stores it
     * back in the normal prefs, and only then wipes the mode — the session
     * survives without biometrics. If Tier-2 is gone the mode is left on, so
     * the refresh token is never lost.
     */
    fun setBiometricEnabled(
        enabled: Boolean,
        allowWeak: Boolean,
        onResult: (BiometricUnlockOutcome) -> Unit,
    ) {
        val report: (BiometricUnlockOutcome) -> Unit = { outcome ->
            Log.d(TAG, "setBiometricEnabled: enabled=$enabled allowWeak=$allowWeak → $outcome")
            onResult(outcome)
        }
        if (!enabled) {
            val refreshToken = biometricSessionManager.unlockSession.decryptRefreshToken()
            if (refreshToken == null) {
                Log.w(TAG, "setBiometricEnabled: disable without active tier-2 → Failed")
                report(BiometricUnlockOutcome.Failed)
                return
            }
            tokenProvider.saveRefreshToken(refreshToken)
            biometricSessionManager.disable()
            report(BiometricUnlockOutcome.Disabled)
            return
        }
        val refreshToken = tokenProvider.getRefreshToken()
        if (refreshToken == null) {
            Log.w(TAG, "setBiometricEnabled: no refresh token in prefs → NotAvailable")
            report(BiometricUnlockOutcome.NotAvailable)
            return
        }
        biometricSessionManager.enable(refreshToken, allowWeak) { outcome ->
            if (outcome is BiometricUnlockOutcome.Enabled) {
                tokenProvider.removeRefreshToken()
            }
            report(outcome)
        }
    }

    // Plain client for fetching generated QR files from the public CDN URL.
    private val qrImageClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    private val cacheManager: CloudCacheManager by lazy {
        CloudCacheManager(
            context,
            CloudCacheDatabase.getInstance(context).cloudCacheDao()
        )
    }

    // ====== Maintenance ======

    /**
     * Checks if the cloud service is currently in maintenance.
     * This is a public endpoint (no auth required).
     * Returns null if no maintenance is active/scheduled.
     */
    suspend fun checkMaintenanceStatus(): Result<MaintenanceStatus?> {
        return safeApiCall { maintenanceApi.getMaintenanceStatus() }
    }

    // ====== Auth ======

    /**
     * Starts a password registration: the backend validates the password
     * policy, stores only a bcrypt hash of [password] on the pending code and
     * emails an OTP. The account (and the optional [displayName], shown in the
     * admin panel) is only created on verifyRegisterCode.
     */
    suspend fun register(
        displayName: String,
        email: String,
        password: String,
        confirmPassword: String
    ): Result<AuthCodeResponseData> {
        return safeApiCall {
            authApi.register(
                RegisterRequest(
                    displayName = displayName.trim().takeIf { it.isNotEmpty() },
                    email = email,
                    password = password,
                    confirmPassword = confirmPassword
                )
            )
        }
    }

    /**
     * Password login: behaviour depends on the server-stored preference
     * `require_login_code`. When true (default) the server emails a fresh
     * single-use code and this returns [AuthCodeResponseData]-like fields
     * (expiresInMinutes...); when false the server returns tokens directly and
     * this persists them so the OTP screen can be skipped.
     */
    suspend fun loginWithPassword(email: String, password: String): Result<LoginPasswordResponse> {
        val result = safeApiCall {
            authApi.loginWithPassword(
                LoginPasswordRequest(
                    email,
                    password,
                    deviceId(),
                    deviceName()
                )
            )
        }
        // Direct login (code disabled): tokens are present → persist session.
        result.onSuccess { data ->
            if (data.accessToken != null && data.refreshToken != null) {
                val tokenData = TokenData(
                    accessToken = data.accessToken,
                    refreshToken = data.refreshToken,
                    accessTokenExpiresIn = data.accessTokenExpiresIn ?: "",
                    refreshTokenExpiresIn = data.refreshTokenExpiresIn ?: "",
                    user = data.user
                )
                persistTokens(tokenData)
            }
        }
        return result
    }

    suspend fun resendCode(email: String): Result<AuthCodeResponseData> {
        return safeApiCall { authApi.resendCode(AuthCodeRequest(email)) }
    }

    suspend fun verifyLoginCode(email: String, code: String): Result<TokenData> {
        val result = safeApiCall {
            authApi.loginVerifyCode(
                VerifyCodeRequest(
                    email,
                    code,
                    deviceId(),
                    deviceName()
                )
            )
        }
        if (result.isSuccess) {
            persistTokens(result.getOrThrow())
        }
        return result
    }

    suspend fun verifyRegisterCode(email: String, code: String): Result<TokenData> {
        val result = safeApiCall {
            authApi.registerVerifyCode(
                VerifyCodeRequest(
                    email,
                    code,
                    deviceId(),
                    deviceName()
                )
            )
        }
        if (result.isSuccess) {
            persistTokens(result.getOrThrow())
        }
        return result
    }

    /**
     * Google Sign-In: exchanges a Google ID token for backend JWTs.
     * Reuses the same [persistTokens] logic as password login, including
     * biometric migration.
     */
    suspend fun googleSignIn(idToken: String): Result<TokenData> {
        val result = safeApiCall {
            authApi.googleAuth(
                nopalito.app.ui.screens.cloud.model.GoogleAuthRequest(
                    idToken = idToken,
                    deviceId = deviceId(),
                    deviceName = deviceName()
                )
            )
        }
        if (result.isSuccess) {
            persistTokens(result.getOrThrow())
        }
        return result
    }

    // ── Password recovery / migration ──

    /** Requests a reset code. The response is generic (no user enumeration). */
    suspend fun forgotPassword(email: String): Result<AuthCodeResponseData> {
        return safeApiCall { authApi.forgotPassword(AuthCodeRequest(email)) }
    }

    /** Requests a set-password code (migration of legacy OTP-only accounts). */
    suspend fun requestSetPasswordCode(email: String): Result<AuthCodeResponseData> {
        return safeApiCall { authApi.setPasswordCode(AuthCodeRequest(email)) }
    }

    suspend fun resetPassword(
        email: String,
        code: String,
        newPassword: String,
        confirmPassword: String
    ): Result<TokenData> {
        val result = safeApiCall {
            authApi.resetPassword(
                NewPasswordWithCodeRequest(
                    email,
                    code,
                    newPassword,
                    confirmPassword
                )
            )
        }
        // The code proved email ownership → sign in right away (no re-entry).
        if (result.isSuccess) {
            persistTokens(result.getOrThrow())
        }
        return result
    }

    suspend fun setPassword(
        email: String,
        code: String,
        newPassword: String,
        confirmPassword: String
    ): Result<TokenData> {
        val result = safeApiCall {
            authApi.setPassword(
                NewPasswordWithCodeRequest(
                    email,
                    code,
                    newPassword,
                    confirmPassword
                )
            )
        }
        if (result.isSuccess) {
            persistTokens(result.getOrThrow())
        }
        return result
    }

    suspend fun requestEmailChangeCode(newEmail: String): Result<AuthCodeResponseData> {
        return safeApiCall { authApi.requestEmailChange(EmailChangeRequest(newEmail)) }
    }

    suspend fun confirmEmailChange(newEmail: String, code: String): Result<TokenData> {
        val result =
            safeApiCall { authApi.confirmEmailChange(ConfirmEmailChangeRequest(newEmail, code)) }
        if (result.isSuccess) {
            // Persist refreshed tokens if returned; otherwise update cached email
            // Backend currently returns only {user:{id,email}} without tokens (session stays alive by design).
            // TokenData fields are non-null in the model, but Gson may set them to null at runtime when absent.
            // Use safe null-aware checks to avoid NPE (the crash you saw after email change).
            try {
                val data = result.getOrThrow()
                val access = data.accessToken
                val refresh = data.refreshToken
                if (access.isNotBlank() && refresh.isNotBlank()) {
                    persistTokens(data)
                } else if (data.user?.email != null) {
                    tokenProvider.saveUserEmail(data.user.email)
                    // keep existing tokens - session stays alive intentionally
                    data.user.displayName?.let { tokenProvider.saveUserDisplayName(it) }
                } else {
                    tokenProvider.saveUserEmail(newEmail)
                }
            } catch (_: Exception) {
                // Fallback: at least update cached email so UI reflects new address
                try {
                    tokenProvider.saveUserEmail(newEmail)
                } catch (_: Exception) {
                }
            }
        }
        return result
    }

    /**
     * Links the current authenticated account to a Google identity.
     * Does NOT rotate tokens or create a session — preserves the existing JWT.
     */
    suspend fun linkGoogleAccount(idToken: String): Result<nopalito.app.ui.screens.cloud.model.LinkGoogleResponse> {
        return safeApiCall {
            authApi.linkGoogle(nopalito.app.ui.screens.cloud.model.LinkGoogleRequest(idToken))
        }
    }

    /**
     * Session recovery — request code (enumeration-safe, unauthenticated).
     * Always returns success envelope; email is normalized by the backend.
     */
    suspend fun requestSessionRecovery(email: String): Result<nopalito.app.ui.screens.cloud.model.AuthCodeResponseData> {
        return safeApiCall {
            authApi.requestSessionRecovery(
                nopalito.app.ui.screens.cloud.model.SessionRecoveryRequest(
                    email.trim()
                )
            )
        }
    }

    /**
     * Session recovery — verify code and revoke all active sessions.
     * Does not return JWTs; caller must navigate back to normal login.
     */
    suspend fun verifySessionRecovery(
        email: String,
        code: String
    ): Result<nopalito.app.ui.screens.cloud.model.SessionRecoveryData> {
        return safeApiCall {
            authApi.verifySessionRecovery(
                nopalito.app.ui.screens.cloud.model.SessionRecoveryVerifyRequest(
                    email.trim(),
                    code.trim()
                )
            )
        }
    }

    // ====== Storage (server-authoritative; the client only displays it) ======

    suspend fun getStorageUsage(): Result<StorageUsage> {
        return safeApiCall { storageApi.getUsage() }
    }

    suspend fun refreshToken(): Result<TokenData> {
        if (biometricSessionManager.isEnabled) {
            return biometricRefresh()
        }
        val refreshToken = tokenProvider.getRefreshToken()
            ?: return Result.failure(LogoutException("No refresh token available"))
        val result = safeApiCall { authApi.refreshToken(RefreshRequest(refreshToken)) }
        result.onSuccess { tokenData ->
            tokenProvider.saveTokens(
                accessToken = tokenData.accessToken,
                refreshToken = tokenData.refreshToken
            )
        }
        return result
    }

    /**
     * Biometric-mode refresh: unlock Tier-2 once through the OS prompt (no-op
     * when the session is already active), then exchange and rotate the
     * refresh token with Tier-2 in memory — never a second prompt.
     */
    private suspend fun biometricRefresh(): Result<TokenData> {
        if (!biometricSessionManager.hasActiveSession && !biometricRefreshBridge.unlockSession()) {
            return Result.failure(NeedsBiometricUnlockException())
        }
        val result = biometricSessionRefresher.refresh()
        result.onSuccess { tokenData ->
            tokenProvider.saveAccessToken(tokenData.accessToken)
        }
        return result
    }

    /**
     * Persists freshly issued tokens, and when biometric mode is on, migrates
     * the refresh token into the auth-bound blob, removing the normal copy
     * only after the encrypted copy is verified.
     */
    private suspend fun persistTokens(tokenData: TokenData) {
        tokenProvider.saveTokens(
            accessToken = tokenData.accessToken,
            refreshToken = tokenData.refreshToken,
            user = tokenData.user
        )
        if (biometricSessionManager.isEnabled) {
            migrateRefreshToBiometric(tokenData.refreshToken)
        }
    }

    /**
     * Transactional migration: enable the mode (one prompt, Tier-2 wrapped),
     * verify the token reads back through Tier-2, and only then drop the
     * normal refresh copy. On failure the mode is rolled back and the normal
     * copy stays intact.
     */
    private suspend fun migrateRefreshToBiometric(refreshToken: String) {
        val migrated = biometricRefreshBridge.enableSession(refreshToken)
        if (migrated) {
            tokenProvider.removeRefreshToken()
        } else {
            biometricSessionManager.disable()
        }
    }

    suspend fun getMe(): Result<String?> {
        return safeApiCall(
            call = { authApi.getMe() },
            transform = { it.user?.email }
        )
    }

    suspend fun getProfile(): Result<nopalito.app.ui.screens.cloud.model.UserData?> {
        return safeApiCall(
            call = { authApi.getMe() },
            transform = { it.user }
        )
    }

    fun hasSession(): Boolean = tokenProvider.hasSession()

    fun isBiometricMode(): Boolean = biometricSessionManager.isEnabled

    fun getCurrentUserEmail(): String? = tokenProvider.getUserEmail()

    fun getCurrentUserDisplayName(): String? = tokenProvider.getUserDisplayName()

    // ====== Files ======

    suspend fun listFiles(
        category: String? = null,
        page: Int = 1,
        limit: Int = 50
    ): Result<List<CloudFile>> {
        return safeApiCall(
            call = { fileApi.listFiles(category, page, limit) },
            // Defensive filter: folder rows are grouping metadata (no physical file)
            // and must not be listed as files.
            transform = { it.files.filter { file -> file.itemType != "folder" } }
        )
    }

    /**
     * Lists ONE level of the folder tree: files AND folders whose parent is
     * [parentId] (null = root). Folders always come back, even with a
     * [category] filter — the filter only narrows the files.
     */
    suspend fun listFolder(
        parentId: String? = null,
        category: String? = null,
        page: Int = 1,
        limit: Int = 50
    ): Result<List<CloudFile>> {
        return safeApiCall(
            call = {
                // An empty parentId asks the backend for the ROOT level
                // including folders; null would keep the legacy flat listing.
                fileApi.listFiles(
                    category = category,
                    page = page,
                    limit = limit,
                    parentId = parentId ?: ""
                )
            },
            transform = { it.files }
        )
    }

    /**
     * Creates a USER folder (origin='user') at [parentId], or at the root when
     * null. The backend rejects duplicate sibling names (FOLDER_NAME_TAKEN),
     * non-folder/trashed parents and over-depth locations.
     */
    suspend fun createUserFolder(
        name: String,
        parentId: String? = null
    ): Result<CloudFile> {
        return safeApiCall(
            call = {
                fileApi.createFolderGroup(
                    CreateFolderRequest(name = name.trim(), parentId = parentId)
                )
            },
            transform = { it.file }
        )
    }

    /**
     * Moves a file or folder to [targetParentId] (null = move to the root).
     * One recursive server-side operation; cycle/depth/collision validation
     * happens on the backend (FOLDER_MOVE_CYCLE, DEPTH_LIMIT_EXCEEDED,
     * FOLDER_NAME_TAKEN / FILE_NAME_TAKEN...).
     */
    suspend fun moveItem(
        fileId: String,
        targetParentId: String? = null
    ): Result<CloudFile> {
        return safeApiCall(
            call = {
                fileApi.updateFile(
                    fileId,
                    UpdateFileRequest(parentId = targetParentId ?: "")
                )
            },
            transform = { it.file }
        )
    }

    /**
     * Uploads a file with optional export grouping metadata.
     * The new parameters default to null to keep compatibility with the
     * existing callers (e.g. CloudUploadViewModel).
     */
    suspend fun uploadFile(
        fileUri: Uri,
        category: String? = null,
        exportId: String? = null,
        itemType: String = "file",
        outputFormat: String? = null,
        itemCount: Int = 1,
        isCover: Boolean = false,
        parentId: String? = null,
    ): Result<CloudFile> {
        val tempFile = File(context.cacheDir, "cloud_upload_${System.currentTimeMillis()}")
        return try {
            // Reading a picked content:// stream can be slow — keep it off the
            // main thread so large files don't freeze the UI.
            val mimeType = withContext(Dispatchers.IO) {
                val inputStream = context.contentResolver.openInputStream(fileUri)
                    ?: throw IllegalStateException("Could not open $fileUri")
                inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                context.contentResolver.getType(fileUri) ?: "application/octet-stream"
            }
            val requestBody = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())

            val originalName = queryFileName(context, fileUri) ?: tempFile.name
            val multipart = MultipartBody.Part.createFormData("file", originalName, requestBody)

            val categoryPart = category?.toRequestBody(null)
            val exportIdPart = exportId?.toRequestBody(null)
            val itemTypePart = itemType.toRequestBody(null)
            val outputFormatPart = outputFormat?.toRequestBody(null)
            val itemCountPart = itemCount.toString().toRequestBody(null)
            val isCoverPart = isCover.toString().toRequestBody(null)
            val parentIdPart = parentId?.toRequestBody(null)

            val result = safeApiCall(
                call = {
                    fileApi.uploadFile(
                        file = multipart,
                        category = categoryPart,
                        exportId = exportIdPart,
                        itemType = itemTypePart,
                        outputFormat = outputFormatPart,
                        itemCount = itemCountPart,
                        isCover = isCoverPart,
                        parentId = parentIdPart,
                    )
                },
                transform = { it.file }
            )

            // Cache the uploaded file locally
            result.onSuccess { cloudFile ->
                val updatedAt = cloudFile.updatedAt ?: cloudFile.createdAt ?: ""
                cacheManager.saveFromFile(
                    fileId = cloudFile.id,
                    fileName = originalName,
                    mimeType = mimeType,
                    updatedAt = updatedAt,
                    sourceFile = tempFile
                )
            }

            result
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // safeApiCall never throws, so anything caught here failed while
            // reading/copying the local content:// — log the real cause.
            Log.w(TAG, "uploadFile: local read failed for $fileUri", e)
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Creates the root row (folder) of a multiple cloud export.
     * Logically groups the files that share [exportId].
     * [coverFileId] is the id of the first uploaded file (folder cover).
     */
    suspend fun createExportGroup(
        exportId: String,
        name: String,
        format: ExportFormat,
        itemCount: Int,
        coverFileId: String?,
    ): Result<CloudFile> {
        return createExportGroupRaw(exportId, name, format.name, itemCount, coverFileId)
    }

    /** Same as [createExportGroup] but with a raw format label (tools use plain strings). */
    suspend fun createExportGroupRaw(
        exportId: String,
        name: String,
        formatName: String,
        itemCount: Int,
        coverFileId: String?,
    ): Result<CloudFile> {
        return safeApiCall(
            call = {
                fileApi.createFolderGroup(
                    CreateFolderRequest(
                        exportId = exportId,
                        name = name,
                        format = formatName,
                        itemCount = itemCount,
                        coverFileId = coverFileId,
                    )
                )
            },
            transform = { it.file }
        )
    }

    /**
     * Uploads a set of files to the cloud grouped as a folder, mirroring the
     * main export flow: the first file is uploaded as the cover, a folder row is
     * created (`POST /files/folder`) and the rest are uploaded as its children
     * sharing the same [exportId]. A single file is uploaded plainly without a
     * folder. Returns per-file results in the same order as [fileUris].
     */
    suspend fun uploadGroup(
        fileUris: List<Uri>,
        groupName: String,
        formatName: String,
    ): List<Result<CloudFile>> {
        if (fileUris.isEmpty()) return emptyList()
        if (fileUris.size == 1) {
            return listOf(
                uploadFile(
                    fileUris.first(),
                    category = "exported",
                    outputFormat = formatName
                )
            )
        }
        val exportId = UUID.randomUUID().toString()
        val results = mutableListOf<Result<CloudFile>>()
        val cover = uploadFile(
            fileUri = fileUris.first(),
            category = "exported",
            exportId = exportId,
            itemType = "file",
            outputFormat = formatName,
            itemCount = fileUris.size,
            isCover = true,
        )
        results += cover
        createExportGroupRaw(
            exportId = exportId,
            name = groupName,
            formatName = formatName,
            itemCount = fileUris.size,
            coverFileId = cover.getOrNull()?.id,
        )
        for (index in 1 until fileUris.size) {
            results += uploadFile(
                fileUri = fileUris[index],
                category = "exported",
                exportId = exportId,
                itemType = "file",
                outputFormat = formatName,
                itemCount = fileUris.size,
                isCover = false,
            )
        }
        return results
    }

    /**
     * Download file bytes to persistent local cache (cache-first).
     * If the API fails (e.g. file in trash), falls back to cached version.
     */
    suspend fun downloadToCache(
        fileId: String,
        fileName: String,
        updatedAt: String? = null
    ): Result<File> {
        return try {
            // 1. Cache-first
            if (cacheManager.isCached(fileId, updatedAt)) {
                val cached = cacheManager.getLocalFile(fileId)
                if (cached != null) return Result.success(cached)
            }

            // 2. Network
            val response = fileApi.downloadFile(fileId)
            if (!response.isSuccessful) {
                val fallback = cacheManager.getLocalFile(fileId)
                if (fallback != null) return Result.success(fallback)
                return Result.failure(
                    ApiException(
                        null,
                        "Download failed: ${response.code()} ${response.message()}"
                    )
                )
            }
            val body = response.body()
                ?: return Result.failure(ApiException(null, "Empty response body"))

            // 3. Persist
            val file = cacheManager.save(fileId, fileName, null, updatedAt ?: "", body)
            Result.success(file)
        } catch (e: Exception) {
            val fallback = cacheManager.getLocalFile(fileId)
            if (fallback != null) return Result.success(fallback)
            Result.failure(e)
        }
    }

    /**
     * Download a file to the chosen download folder (SAF) or, when none is
     * configured, to the public Downloads/Nopalito Scan folder via MediaStore.
     * Reports download progress (0..1) through [onProgress].
     */
    suspend fun downloadFile(
        fileId: String,
        fileName: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Uri> {
        return try {
            val response = fileApi.downloadFile(fileId)
            if (!response.isSuccessful) {
                return Result.failure(ApiException(null, "Error downloading file"))
            }
            val body = response.body() ?: return Result.failure(Exception("Empty server response"))

            val uri = withContext(Dispatchers.IO) {
                DownloadLocation.saveStream(
                    context = context,
                    displayName = fileName,
                    mimeType = response.headers()["content-type"] ?: "application/octet-stream",
                    totalBytes = body.contentLength(),
                    openInput = body::byteStream,
                    onProgress = onProgress
                )
            } ?: throw Exception("Could not save file to the download folder")
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Bulk ZIP download (mobile): streams the archive to the user's download
     * location. [onPhase] flips PREPARING→DOWNLOADING when the response body
     * starts arriving; [onProgress] reports 0..1 of bytes read.
     */
    suspend fun downloadBulkZip(
        ids: List<String>,
        fileName: String,
        onPhase: (BulkZipPhase) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): Result<Uri> {
        return try {
            val response = fileApi.downloadBulkZip(ids.joinToString(","))
            if (!response.isSuccessful) {
                return Result.failure(
                    ApiException(null, "ZIP download failed: ${response.code()}")
                )
            }
            val body = response.body()
                ?: return Result.failure(ApiException(null, "Empty server response"))

            onPhase(BulkZipPhase.DOWNLOADING)
            val uri = withContext(Dispatchers.IO) {
                DownloadLocation.saveStream(
                    context = context,
                    displayName = fileName,
                    mimeType = "application/zip",
                    totalBytes = body.contentLength(),
                    openInput = body::byteStream,
                    onProgress = onProgress,
                )
            } ?: throw Exception("Could not save ZIP to the download folder")
            Result.success(uri)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "downloadBulkZip failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun deleteFile(fileId: String): Result<Unit> {
        return safeApiCall { fileApi.deleteFile(fileId) }
    }

    // ── Trash ──

    suspend fun listDeletedFiles(
        page: Int = 1,
        limit: Int = 50,
        parentId: String? = null
    ): Result<List<CloudFile>> {
        return safeApiCall(
            call = { fileApi.listDeletedFiles(page, limit, parentId) },
            transform = { it.files }
        )
    }

    suspend fun restoreFile(fileId: String): Result<CloudFile> {
        return safeApiCall(
            call = { fileApi.restoreFile(fileId) },
            transform = { it.file }
        )
    }

    suspend fun permanentlyDeleteFile(fileId: String): Result<Unit> {
        // Also evict from local cache
        cacheManager.evict(fileId)
        return safeApiCall { fileApi.permanentlyDeleteFile(fileId) }
    }

    suspend fun updateFile(
        fileId: String,
        originalName: String? = null,
        category: String? = null
    ): Result<CloudFile> {
        return safeApiCall(
            call = { fileApi.updateFile(fileId, UpdateFileRequest(originalName, category)) },
            transform = { it.file }
        )
    }

    // ====== Avatar (profile picture, separate subsystem) ======

    suspend fun getAvatar(): Result<nopalito.app.ui.screens.cloud.model.AvatarData> {
        return safeApiCall(
            call = { avatarApi.getAvatar() },
            transform = { it.avatar ?: nopalito.app.ui.screens.cloud.model.AvatarData() }
        )
    }

    suspend fun uploadAvatar(fileUri: Uri): Result<nopalito.app.ui.screens.cloud.model.AvatarData> {
        val tempFile = File(context.cacheDir, "avatar_upload_${System.currentTimeMillis()}.jpg")
        return try {
            val mimeType = withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(fileUri)
                    ?: throw IllegalStateException("Could not open $fileUri")
                input.use { ins -> tempFile.outputStream().use { out -> ins.copyTo(out) } }
                context.contentResolver.getType(fileUri) ?: "image/jpeg"
            }
            val name = queryFileName(context, fileUri) ?: tempFile.name
            val body = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("avatar", name, body)
            safeApiCall(
                call = { avatarApi.uploadAvatar(part) },
                transform = { it.avatar ?: nopalito.app.ui.screens.cloud.model.AvatarData() }
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "uploadAvatar: local read failed", e)
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    suspend fun deleteAvatar(): Result<nopalito.app.ui.screens.cloud.model.AvatarData> {
        return safeApiCall(
            call = { avatarApi.deleteAvatar() },
            transform = { it.avatar ?: nopalito.app.ui.screens.cloud.model.AvatarData() }
        )
    }

    // ====== User preferences (language sync) + account status ======

    /** True while a cloud session exists (access token available). */
    fun hasCloudSession(): Boolean = tokenProvider.getAccessToken() != null

    /**
     * Persists the app language on the backend so future account emails
     * (inactivity warnings, deletion confirmations) arrive in that language.
     *
     * Fire-and-forget friendly: when there is no cloud session it succeeds
     * without a network call (a local-only change is perfectly valid);
     * network/API failures come back as Result.failure so the caller can
     * surface a hint and retry on the next change. The Bearer token and the
     * Accept-Language / x-app-language headers are attached automatically.
     */
    suspend fun updateUserLanguage(languageCode: String): Result<Unit> {
        if (!tokenProvider.hasSession()) return Result.success(Unit)
        return safeApiCall {
            userPreferencesApi.updateLanguage(UpdateLanguageRequest(languageCode))
        }
    }

    suspend fun getPreferences(): Result<UserPreferencesData> {
        return safeApiCall { userPreferencesApi.getPreferences() }
    }

    suspend fun updateLoginCodePreference(enabled: Boolean): Result<UserPreferencesData> {
        return safeApiCall {
            userPreferencesApi.updateLoginCodePreference(UpdateLoginCodeRequest(enabled))
        }
    }

    // ====== QR generator ======

    /** Generates a customized QR code (Python subprocess backend). */
    suspend fun generateQr(request: QrGenerateRequest): Result<QrGenerateData> {
        return safeApiCall(call = { qrApi.generate(request) })
    }

    /** Loads the catalog of predefined design templates. */
    suspend fun listQrStyles(): Result<List<QrStyle>> {
        return safeApiCall(
            call = { qrApi.listStyles() },
            transform = { it.styles }
        )
    }

    /**
     * Downloads the generated QR bytes from its public CDN URL (not
     * authenticated). Returns null bytes when the fetch fails.
     */
    suspend fun fetchQrBytes(url: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                qrImageClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else response.body.bytes()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    // ====== Cloud scan history ======

    /**
     * Pushes a scan (metadata + optional thumbnail image) to the cloud history.
     * Returns the created scan or null when the request succeeded without one.
     */
    suspend fun pushScan(scan: QrScanUpload, image: File?): Result<QrScan?> {
        val metadata = JSONObject().apply {
            put("content", scan.content)
            put("type", scan.type)
            scan.typeData?.let { put("typeData", it) }
            scan.format?.let { put("format", it) }
            scan.design?.let { put("design", it) }
            put("scannedAt", scan.scannedAt)
            if (image != null) put("imageIndex", 0)
        }
        val scans = JSONArray().put(metadata)
        val body = JSONObject().put("scans", scans)
        val metadataPart = body.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val parts = if (image != null) {
            listOf(imagePart(image))
        } else {
            emptyList()
        }
        return safeApiCall(
            call = { scanApi.pushScans(metadataPart, parts) },
            transform = { it.scans.firstOrNull() }
        )
    }

    /** Lists the user's scan history from the cloud. */
    suspend fun listCloudScans(): Result<List<QrScan>> {
        return safeApiCall(
            call = { scanApi.listScans() },
            transform = { it.scans }
        )
    }

    /** Soft-deletes a scan in the cloud. */
    suspend fun deleteCloudScan(scanId: String): Result<Unit> {
        return safeApiCall { scanApi.deleteScan(scanId) }
    }

    /** Lists the user's soft-deleted scans (cloud QR trash). */
    suspend fun listCloudTrashScans(): Result<List<QrScan>> {
        return safeApiCall(
            call = { scanApi.listTrash() },
            transform = { it.scans }
        )
    }

    /** Restores a soft-deleted scan to the active cloud history. */
    suspend fun restoreCloudScan(scanId: String): Result<Unit> {
        return safeApiCall { scanApi.restoreScan(scanId) }
    }

    /** Permanently deletes a scan (no undo). */
    suspend fun permanentlyDeleteCloudScan(scanId: String): Result<Unit> {
        return safeApiCall { scanApi.permanentlyDeleteScan(scanId) }
    }

    /** Downloads a scan thumbnail by its file id (cache-first). */
    suspend fun getScanThumbnail(fileId: String): Result<File> {
        return downloadToCache(fileId, "$fileId.jpg")
    }

    // ====== Session ======

    fun onTokensCleared(callback: () -> Unit) {
        tokenProvider.onLogout(callback)
    }

    fun clearSession() {
        // In biometric mode the refresh token lives in the auth-bound blob:
        // wipe the mode too, or hasSession() would still report a session.
        if (biometricSessionManager.isEnabled) {
            biometricSessionManager.disable()
        }
        tokenProvider.clearTokens()
    }

    /** Sid-based logout — uses current access JWT, empty body. */
    suspend fun logoutSid(): Result<Unit> {
        return safeApiCall(
            call = { authApi.logoutSid() }
        )
    }

    // ====== Private helpers ======

    /**
     * Builds the multipart image part for /api/scans declaring the REAL content
     * type (magic bytes), because the backend rejects scans whose bytes do not
     * match the declared type (e.g. a generated QR saved as PNG must not be
     * declared as image/jpeg).
     */
    private fun imagePart(file: File): MultipartBody.Part {
        val (mime, ext) = detectImageType(file)
        return MultipartBody.Part.createFormData(
            "images",
            "scan.$ext",
            file.asRequestBody(mime.toMediaTypeOrNull())
        )
    }

    /** Sniffs the real image MIME from the file header (defaults to JPEG). */
    private fun detectImageType(file: File): Pair<String, String> {
        val header = ByteArray(12)
        val count = try {
            file.inputStream().use { it.read(header) }
        } catch (_: Exception) {
            0
        }
        return when {
            count >= 8 &&
                    header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() ->
                "image/png" to "png"

            count >= 2 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() ->
                "image/jpeg" to "jpg"

            count >= 12 &&
                    header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
                    header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                    header[10] == 0x42.toByte() && header[11] == 0x50.toByte() ->
                "image/webp" to "webp"

            else -> "image/jpeg" to "jpg"
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {
        }
        return name
    }

    private suspend fun <T> safeApiCall(
        call: suspend () -> retrofit2.Response<ApiResponse<T>>
    ): Result<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    if (body.data != null) {
                        Result.success(body.data)
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        (Result.success(null) as Result<T>)
                    }
                } else {
                    val error = ErrorParser.fromBody(response.code(), body)
                    Result.failure(
                        error.toApiException {
                            context.stringFor(
                                R.string.error_unknown,
                                AppLocaleOverride.locale
                            )
                        }
                    )
                }
            } else {
                val errorBodyString = try {
                    response.errorBody()?.string()
                } catch (_: Exception) {
                    null
                }
                val error = ErrorParser.parse(response.code(), errorBodyString)
                Result.failure(
                    error.toApiException { parseErrorMessage(error.statusCode) }
                )
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // Never swallow cancellation: the caller's scope is ending or a
            // newer request superseded this one (structured concurrency).
            throw e
        } catch (e: LogoutException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun <T, R> safeApiCall(
        call: suspend () -> retrofit2.Response<ApiResponse<T>>,
        transform: (T) -> R
    ): Result<R> {
        return safeApiCall(call).map { transform(it) }
    }

    private fun parseErrorMessage(httpCode: Int): String = when (httpCode) {
        400 -> context.stringFor(R.string.cloud_error_400, AppLocaleOverride.locale)
        401 -> context.stringFor(R.string.cloud_error_401, AppLocaleOverride.locale)
        404 -> context.stringFor(R.string.cloud_error_404, AppLocaleOverride.locale)
        413 -> context.stringFor(R.string.cloud_error_413, AppLocaleOverride.locale)
        415 -> context.stringFor(R.string.cloud_error_415, AppLocaleOverride.locale)
        429 -> context.stringFor(R.string.cloud_error_429, AppLocaleOverride.locale)
        500 -> context.stringFor(R.string.cloud_error_500, AppLocaleOverride.locale)
        503 -> context.stringFor(R.string.cloud_error_503, AppLocaleOverride.locale)
        else -> context.stringFor(
            R.string.cloud_error_unexpected,
            AppLocaleOverride.locale,
            httpCode
        )
    }
}

/**
 * Structured exception carrying the backend's error information.
 *
 * [code] is the stable identifier used for localization; [details] keeps the
 * structured detail values (access them only via [ApiDetails]); [httpStatus]
 * is the HTTP status of the failed call; [backendMessage] is the raw server
 * text kept ONLY as a final legacy fallback — it must never be the primary
 * visible text and is sanitized before any display.
 *
 * [message] stays for internal compatibility (logs, debugging) and is NOT the
 * visible user text. The 2-argument constructor `(code, message)` is kept for
 * call sites that predate the structured envelope.
 */
class ApiException(
    val code: String?,
    val details: Map<String, Any>? = null,
    val httpStatus: Int = 0,
    val backendMessage: String? = null,
    message: String? = null
) : Exception(message ?: "API request failed") {

    /** True when the upload would exceed the user's server-side storage plan. */
    fun isQuotaExceeded(): Boolean = code == QUOTA_EXCEEDED

    /** True when a trash restore was rejected because it would exceed the quota. */
    fun isRestoreQuotaExceeded(): Boolean = code == QUOTA_EXCEEDED_ON_RESTORE

    /** True when the account is suspended (all auth flows are blocked). */
    fun isAccountSuspended(): Boolean =
        code == AUTH_ACCOUNT_SUSPENDED || code == AUTH_PASSWORD_RESET_BLOCKED_SUSPENDED

    /** True when registration was rejected by an IP policy (limit or VPN). */
    fun isRegistrationBlocked(): Boolean =
        code == AUTH_REGISTER_IP_LIMIT_REACHED || code == AUTH_REGISTER_VPN_NOT_ALLOWED

    /** Legacy-compatible constructor: `(code, message)`. */
    constructor(code: String?, message: String) : this(code, null, 0, null, message)

    companion object {
        const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
        const val QUOTA_EXCEEDED_ON_RESTORE = "QUOTA_EXCEEDED_ON_RESTORE"
        const val EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED"
        const val ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND"
        const val INVALID_APP_SECRET = "INVALID_APP_SECRET"
        const val AUTH_ACCOUNT_SUSPENDED = "AUTH_ACCOUNT_SUSPENDED"
        const val AUTH_LOGIN_BLOCKED_SUSPENDED = "AUTH_LOGIN_BLOCKED_SUSPENDED"
        const val AUTH_ACCOUNT_STATUS_UNKNOWN = "AUTH_ACCOUNT_STATUS_UNKNOWN"
        const val AUTH_REGISTER_IP_LIMIT_REACHED = "AUTH_REGISTER_IP_LIMIT_REACHED"
        const val AUTH_REGISTER_VPN_NOT_ALLOWED = "AUTH_REGISTER_VPN_NOT_ALLOWED"
        const val AUTH_PASSWORD_RESET_BLOCKED_SUSPENDED = "AUTH_PASSWORD_RESET_BLOCKED_SUSPENDED"
        const val AUTH_FORBIDDEN_ACCOUNT_DELETED = "AUTH_FORBIDDEN_ACCOUNT_DELETED"
        const val AUTH_FORBIDDEN_ACCOUNT_INACTIVE_DELETED =
            "AUTH_FORBIDDEN_ACCOUNT_INACTIVE_DELETED"
        const val AUTH_TOO_MANY_DEVICES = "AUTH_TOO_MANY_DEVICES"
        const val ACCOUNT_ALREADY_DELETED = "ACCOUNT_ALREADY_DELETED"
        const val ANONYMOUS_USER_PROTECTED = "ANONYMOUS_USER_PROTECTED"
    }
}

class LogoutException(message: String) : Exception(message)

/**
 * Thrown when a biometric-mode refresh could not run because the OS prompt
 * was cancelled or failed. It is NOT a logout: the session (and the encrypted
 * refresh token) remain intact and a retry may succeed.
 */
class NeedsBiometricUnlockException(
    message: String = "Biometric unlock required",
) : Exception(message)
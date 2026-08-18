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
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.DownloadLocation
import nopalito.app.ui.screens.cloud.model.*
import nopalito.app.ui.screens.cloud.network.*
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
import java.util.*
import java.util.concurrent.TimeUnit

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

class CloudRepository(private val context: Context) {
    private val apiClient = CloudApiClient.getInstance(context)
    private val fileApi: FileApi = apiClient.files
    private val authApi: AuthApi = apiClient.auth
    private val storageApi: StorageApi = apiClient.storage
    private val qrApi: QrApi = apiClient.qr
    private val scanApi: ScanApi = apiClient.scans
    private val maintenanceApi: MaintenanceApi = apiClient.maintenance
    private val tokenProvider = apiClient.tokenProviderInstance
    private val biometricSessionManager: BiometricSessionManager = apiClient.biometricSessionManagerInstance
    private val biometricRefreshBridge = BiometricRefreshBridge(biometricSessionManager)
    private val biometricSessionRefresher =
        BiometricSessionRefresher(biometricSessionManager.unlockSession) { refreshToken ->
            safeApiCall { authApi.refreshToken(RefreshRequest(refreshToken)) }
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
    fun setBiometricEnabled(enabled: Boolean, onResult: (BiometricUnlockOutcome) -> Unit) {
        val report: (BiometricUnlockOutcome) -> Unit = { outcome ->
            Log.d(TAG, "setBiometricEnabled: enabled=$enabled → $outcome")
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
        biometricSessionManager.enable(refreshToken) { outcome ->
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
     * Password login (step 1): validates the credentials and asks the server to
     * email a fresh single-use code. Tokens are only issued after the user enters
     * that code in the OTP screen (verifyLoginCode). 401 is generic: it never
     * reveals whether the email exists.
     */
    suspend fun loginWithPassword(email: String, password: String): Result<AuthCodeResponseData> {
        return safeApiCall {
            authApi.loginWithPassword(LoginPasswordRequest(email, password))
        }
    }

    suspend fun resendCode(email: String): Result<AuthCodeResponseData> {
        return safeApiCall { authApi.resendCode(AuthCodeRequest(email)) }
    }

    suspend fun verifyLoginCode(email: String, code: String): Result<TokenData> {
        val result = safeApiCall { authApi.loginVerifyCode(VerifyCodeRequest(email, code)) }
        if (result.isSuccess) {
            persistTokens(result.getOrThrow())
        }
        return result
    }

    suspend fun verifyRegisterCode(email: String, code: String): Result<TokenData> {
        val result = safeApiCall { authApi.registerVerifyCode(VerifyCodeRequest(email, code)) }
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
            authApi.resetPassword(NewPasswordWithCodeRequest(email, code, newPassword, confirmPassword))
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
            authApi.setPassword(NewPasswordWithCodeRequest(email, code, newPassword, confirmPassword))
        }
        if (result.isSuccess) {
            persistTokens(result.getOrThrow())
        }
        return result
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

    fun hasSession(): Boolean = tokenProvider.hasSession()

    fun isBiometricMode(): Boolean = biometricSessionManager.isEnabled

    fun getCurrentUserEmail(): String? = tokenProvider.getUserEmail()

    // ── TokenProvider delegates (for CloudSessionManager compatibility) ──

    fun getRefreshToken(): String? = tokenProvider.getRefreshToken()

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

    /** Lists the multiple exports (folder rows) for the folders view. */
    suspend fun listExportGroups(
        page: Int = 1,
        limit: Int = 50
    ): Result<List<CloudFile>> {
        return safeApiCall(
            call = { fileApi.listExportGroups(page, limit) },
            transform = { it.groups }
        )
    }

    /** Lists the children of a multiple export (cover first). */
    suspend fun listExportChildren(exportId: String): Result<List<CloudFile>> {
        return safeApiCall(
            call = { fileApi.listFiles(exportId = exportId) },
            transform = { it.files }
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
    ): Result<CloudFile> {
        return try {
            val inputStream = context.contentResolver.openInputStream(fileUri)
                ?: return Result.failure(Exception("Could not open file"))

            val tempFile = File(context.cacheDir, "cloud_upload_${System.currentTimeMillis()}")
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            withContext(Dispatchers.IO) {
                inputStream.close()
            }

            val mimeType = context.contentResolver.getType(fileUri) ?: "application/octet-stream"
            val requestBody = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())

            val originalName = queryFileName(context, fileUri) ?: tempFile.name
            val multipart = MultipartBody.Part.createFormData("file", originalName, requestBody)

            val categoryPart = category?.toRequestBody(null)
            val exportIdPart = exportId?.toRequestBody(null)
            val itemTypePart = itemType.toRequestBody(null)
            val outputFormatPart = outputFormat?.toRequestBody(null)
            val itemCountPart = itemCount.toString().toRequestBody(null)
            val isCoverPart = isCover.toString().toRequestBody(null)

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

            tempFile.delete()
            result
        } catch (e: Exception) {
            Result.failure(e)
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
            return listOf(uploadFile(fileUris.first(), category = "exported", outputFormat = formatName))
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
                return Result.failure(ApiException(null, "Download failed: ${response.code()} ${response.message()}"))
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

    suspend fun deleteFile(fileId: String): Result<Unit> {
        return safeApiCall { fileApi.deleteFile(fileId) }
    }

    // ── Trash ──

    suspend fun listDeletedFiles(
        page: Int = 1,
        limit: Int = 50
    ): Result<List<CloudFile>> {
        return safeApiCall(
            call = { fileApi.listDeletedFiles(page, limit) },
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

    suspend fun logoutWithToken(refreshToken: String): Result<Unit> {
        return safeApiCall(
            call = { authApi.logout(RefreshRequest(refreshToken)) }
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
                        error.toApiException { context.stringFor(R.string.error_unknown, AppLocaleOverride.locale) }
                    )
                }
            } else {
                val error = ErrorParser.parse(response.code(), response.errorBody()?.string())
                Result.failure(
                    error.toApiException { parseErrorMessage(error.statusCode) }
                )
            }
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
        else -> context.stringFor(R.string.cloud_error_unexpected, AppLocaleOverride.locale, httpCode)
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

    /** Legacy-compatible constructor: `(code, message)`. */
    constructor(code: String?, message: String) : this(code, null, 0, null, message)

    companion object {
        const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
        const val QUOTA_EXCEEDED_ON_RESTORE = "QUOTA_EXCEEDED_ON_RESTORE"
        const val EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED"
        const val ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND"
        const val INVALID_APP_SECRET = "INVALID_APP_SECRET"
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
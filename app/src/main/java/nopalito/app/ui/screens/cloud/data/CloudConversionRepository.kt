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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.model.ConversionItem
import nopalito.app.ui.screens.cloud.model.ConversionJobData
import nopalito.app.ui.screens.cloud.network.CloudApiClient
import nopalito.app.ui.screens.cloud.network.LibreOfficeApi
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import kotlin.time.Duration.Companion.milliseconds

/**
 * Repository for the server-side document → PDF conversion (LibreOffice).
 *
 * Flow: upload files → receive a job id → poll the job until it reaches a
 * terminal state → download the produced PDFs (or the ZIP with all of them).
 */
class CloudConversionRepository(private val context: Context) {
    private val apiClient = CloudApiClient.getInstance(context)
    private val libreOfficeApi: LibreOfficeApi = apiClient.libreOffice

    /** Uploads the picked files and returns the created job id. */
    suspend fun startConversion(files: List<Pair<Uri, String>>): Result<ConversionJobData> {
        val tempFiles = mutableListOf<File>()
        return try {
            val parts = files.map { (uri, fileName) ->
                val tempFile =
                    File(context.cacheDir, "conv_upload_${System.currentTimeMillis()}_${fileName.hashCode()}")
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw ApiException(
                        null,
                        context.stringFor(R.string.cloud_error_upload, AppLocaleOverride.locale)
                    )
                input.use { ins ->
                    tempFile.outputStream().use { out -> ins.copyTo(out) }
                }
                tempFiles += tempFile
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val part = MultipartBody.Part.createFormData(
                    "files",
                    fileName,
                    tempFile.asRequestBody(mime.toMediaTypeOrNull())
                )
                part
            }

            val response = libreOfficeApi.convertToPdf(parts)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success && body.data != null) {
                    Result.success(body.data)
                } else {
                    Result.failure(
                        ApiException(
                            body?.error?.code,
                            body?.message ?: context.stringFor(R.string.error_unknown, AppLocaleOverride.locale)
                        )
                    )
                }
            } else {
                Result.failure(
                    ApiException(
                        null,
                        context.stringFor(R.string.cloud_error_upload, AppLocaleOverride.locale)
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // The multipart body reads the files while the request is being
            // sent, so the temp copies must only be deleted afterwards.
            tempFiles.forEach { runCatching { it.delete() } }
        }
    }

    /** Returns the latest job state (poll this until the status is terminal). */
    suspend fun getJob(jobId: String): Result<ConversionJobData> {
        return try {
            val response = libreOfficeApi.getJob(jobId)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success && body.data != null) {
                    Result.success(body.data)
                } else {
                    Result.failure(
                        ApiException(
                            body?.error?.code,
                            body?.message ?: context.stringFor(R.string.error_unknown, AppLocaleOverride.locale)
                        )
                    )
                }
            } else {
                Result.failure(
                    ApiException(
                        null,
                        context.stringFor(R.string.cloud_error_404, AppLocaleOverride.locale)
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Polls the job until it reaches a terminal state (completed / partial /
     * failed) or [maxAttempts] is exceeded.
     * Invokes [onProgress] with the raw job on every successful poll.
     */
    suspend fun awaitJob(
        jobId: String,
        maxAttempts: Int = 120,
        pollDelayMs: Long = 2000,
        onProgress: (ConversionJobData) -> Unit = {},
    ): Result<ConversionJobData> {
        repeat(maxAttempts) {
            val result = getJob(jobId)
            if (result.isFailure) return result
            val job = result.getOrThrow()
            onProgress(job)
            when (job.status) {
                "completed", "partial", "failed" -> return Result.success(job)
                else -> delay(pollDelayMs.milliseconds)
            }
        }
        return Result.failure(
            ApiException(
                null,
                context.stringFor(R.string.cloud_error_timeout, AppLocaleOverride.locale)
            )
        )
    }

    /**
     * Downloads one converted PDF to a local cache file.
     * [fileId] is the backend id of the produced PDF (ConversionItem.fileId).
     */
    suspend fun downloadConverted(jobId: String, fileId: String, fileName: String): Result<File> {
        return try {
            val response = libreOfficeApi.downloadConvertedFile(jobId, fileId)
            if (!response.isSuccessful) {
                return Result.failure(
                    ApiException(
                        null,
                        context.stringFor(R.string.cloud_error_download, AppLocaleOverride.locale)
                    )
                )
            }
            val body = response.body() ?: return Result.failure(
                ApiException(null, context.stringFor(R.string.cloud_error_download, AppLocaleOverride.locale))
            )
            val target = File(context.cacheDir, "conv_download_${System.currentTimeMillis()}_$fileName")
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
            Result.success(target)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Convenience: only the completed items of a job. */
    fun completedItems(job: ConversionJobData): List<ConversionItem> =
        (job.items ?: emptyList()).filter { it.status == "completed" && !it.fileId.isNullOrBlank() }

    /**
     * Ephemeral synchronous conversion: uploads one local document file and
     * returns the converted PDF as a temporary local file. Nothing is stored
     * on the backend (no job, no persisted file); the returned file lives in
     * the app cache and must be deleted by the caller after rendering.
     */
    suspend fun previewToPdf(file: File, fileName: String): Result<File> {
        return try {
            val mime = "application/octet-stream"
            val part = MultipartBody.Part.createFormData(
                "file",
                fileName,
                file.asRequestBody(mime.toMediaTypeOrNull())
            )
            val response = libreOfficeApi.previewToPdf(part)
            if (!response.isSuccessful) {
                val error = ErrorParser.parse(response.code(), response.errorBody()?.string())
                return Result.failure(
                    error.toApiException {
                        context.stringFor(R.string.cloud_conversion_failed, AppLocaleOverride.locale)
                    }
                )
            }
            val body = response.body() ?: return Result.failure(
                ApiException(null, context.stringFor(R.string.cloud_error_download, AppLocaleOverride.locale))
            )
            val target = File(context.cacheDir, "preview_${System.currentTimeMillis()}.pdf")
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
            Result.success(target)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/** Thrown when the conversion pipeline hits an unexpected state. */
class ConversionException(message: String) : Exception(message)
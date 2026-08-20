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

package nopalito.app.ui.screens.qr

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import nopalito.app.R
import nopalito.app.ui.DownloadLocation
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.QrGenerateRequest
import nopalito.app.ui.uriForFile
import java.io.File

fun mimeFor(format: String): String = when (format.lowercase()) {
    "svg" -> "image/svg+xml"
    "pdf" -> "application/pdf"
    else -> "image/png"
}

/**
 * Re-generates a previously saved QR from its stored [designJson] recipe in the
 * given [format] and saves it to Downloads/Nopalito Scan. Returns true on success.
 * Shared by the local and the cloud QR history.
 *
 * No @RequiresApi(Q) here on purpose: [DownloadLocation.saveBytes] already
 * handles pre-Q devices (legacy public-directory path; WRITE_EXTERNAL_STORAGE
 * is declared with maxSdkVersion=28), so export must keep working on API 26+.
 */
suspend fun regenerateAndSaveQr(
    context: Context,
    repository: CloudRepository,
    designJson: String?,
    format: String,
): Boolean {
    if (designJson == null) return false
    val request = runCatching { Gson().fromJson(designJson, QrGenerateRequest::class.java) }.getOrNull()
        ?: return false
    val bytes = repository.generateQr(request.copy(format = format)).getOrNull()
        ?.let { repository.fetchQrBytes(it.url) }
        ?: return false
    return saveQrToDownloads(context, bytes, format) != null
}

/**
 * Saves an already-stored scan image (local history file or cloud thumbnail)
 * to the download folder, preserving its actual extension. Used to export scans
 * that have no generation recipe (designJson == null).
 */
suspend fun saveScanImageToDownloads(context: Context, imageFile: File?): Boolean {
    if (imageFile == null || !imageFile.exists()) return false
    return runCatching {
        val ext = imageFile.extension.lowercase()
            .takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "png"
        saveQrToDownloads(context, imageFile.readBytes(), ext) != null
    }.getOrDefault(false)
}

/** Persists the QR bytes into the chosen download folder (or the default). */
suspend fun saveQrToDownloads(context: Context, bytes: ByteArray, format: String): Uri? {
    val name = "nopalito_scan_qr_${System.currentTimeMillis()}.${format.lowercase()}"
    return DownloadLocation.saveBytes(
        context = context,
        bytes = bytes,
        displayName = name,
        mimeType = mimeFor(format),
    )
}

/** Shares the QR bytes through a FileProvider-backed content URI. */
fun shareQr(context: Context, bytes: ByteArray, format: String): Boolean {
    return try {
        val dir = File(context.cacheDir, "qr_generated").apply { mkdirs() }
        val file = File(dir, "nopalitoscan_qr.${format.lowercase()}")
        file.writeBytes(bytes)
        val uri = uriForFile(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(format)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("qr", uri)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
        true
    } catch (_: Exception) {
        false
    }
}
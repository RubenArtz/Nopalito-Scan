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
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream

/**
 * Manages local cache of cloud files (previews/thumbnails/full files).
 *
 * Principles:
 * - Cache-first: try local before network.
 * - Version-aware: if server updatedAt differs, re-download.
 * - Orphan cleanup: files that no longer exist on cloud are evicted.
 * - Lightweight: Room index + flat file store, no WorkManager dependency.
 */
class CloudCacheManager(
    context: Context,
    private val dao: CloudCacheDao
) {
    /** Dedicated directory for cached cloud files (not in cacheDir, survives cache clears) */
    private val storeDir: File = File(context.filesDir, "cloud_cache").also { it.mkdirs() }

    // ── Queries ──

    suspend fun getLocalFile(fileId: String): File? {
        val entry = dao.get(fileId) ?: return null
        val file = File(entry.localPath)
        return if (file.exists()) file else null.also { dao.deleteById(fileId) }
    }

    /** Returns true if a valid cache entry exists for this file */
    suspend fun isCached(fileId: String, serverUpdatedAt: String? = null): Boolean {
        val entry = dao.get(fileId) ?: return false
        if (!File(entry.localPath).exists()) {
            dao.deleteById(fileId)
            return false
        }
        // If server says it's newer, consider not cached (triggers re-download)
        if (serverUpdatedAt != null && serverUpdatedAt > entry.updatedAt) return false
        return true
    }

    // ── Write ──

    /**
     * Save a downloaded [ResponseBody] to local cache and index it.
     * Returns the local [File].
     */
    suspend fun save(
        fileId: String,
        fileName: String,
        mimeType: String?,
        updatedAt: String,
        body: ResponseBody,
        etag: String? = null
    ): File {
        val localFile = File(storeDir, "${fileId}_${sanitize(fileName)}")
        body.byteStream().use { input ->
            FileOutputStream(localFile).use { output ->
                input.copyTo(output)
            }
        }
        dao.upsert(
            CachedFileEntity(
                fileId = fileId,
                fileName = fileName,
                mimeType = mimeType,
                updatedAt = updatedAt,
                localPath = localFile.absolutePath,
                etag = etag
            )
        )
        return localFile
    }

    /** Save from an already-existing local file (e.g. moved from temp) */
    suspend fun saveFromFile(
        fileId: String,
        fileName: String,
        mimeType: String?,
        updatedAt: String,
        sourceFile: File,
        etag: String? = null
    ): File {
        val localFile = File(storeDir, "${fileId}_${sanitize(fileName)}")
        sourceFile.copyTo(localFile, overwrite = true)
        dao.upsert(
            CachedFileEntity(
                fileId = fileId,
                fileName = fileName,
                mimeType = mimeType,
                updatedAt = updatedAt,
                localPath = localFile.absolutePath,
                etag = etag
            )
        )
        return localFile
    }

    // ── Delete ──

    suspend fun evict(fileId: String) {
        val entry = dao.get(fileId)
        if (entry != null) {
            File(entry.localPath).delete()
            dao.deleteById(fileId)
        }
    }

    // ── Sync helpers ──

    /** Delete entire cache */
    suspend fun clear() {
        dao.deleteAll()
        storeDir.listFiles()?.forEach { it.delete() }
    }

    private fun sanitize(name: String): String = name.replace(Regex("[/\\\\:?\"<>|]"), "_")
}
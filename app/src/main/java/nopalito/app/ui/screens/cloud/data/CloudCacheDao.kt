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

import androidx.room.*

/**
 * Room DAO for local cloud file cache index.
 * Tracks which files have been cached, their version (updatedAt),
 * and whether they're still referenced by the cloud.
 */
@Dao
interface CloudCacheDao {
    @Query("SELECT * FROM cloud_cache WHERE fileId = :fileId")
    suspend fun get(fileId: String): CachedFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedFileEntity)

    @Delete
    suspend fun delete(entity: CachedFileEntity)

    @Query("DELETE FROM cloud_cache WHERE fileId = :fileId")
    suspend fun deleteById(fileId: String)

    @Query("DELETE FROM cloud_cache")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM cloud_cache")
    suspend fun count(): Int
}

@Entity(tableName = "cloud_cache")
data class CachedFileEntity(
    @PrimaryKey val fileId: String,
    val fileName: String,
    val mimeType: String?,
    /** Server-side last-modified timestamp from the moment we cached */
    val updatedAt: String,
    /** Local absolute path to the cached file */
    val localPath: String,
    /** When this cache entry was created/last-verified (epoch ms) */
    val cachedAtMillis: Long = System.currentTimeMillis(),
    /** ETag or content-hash if available from server */
    val etag: String? = null
)
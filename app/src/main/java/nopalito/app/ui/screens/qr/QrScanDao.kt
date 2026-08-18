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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QrScanDao {
    @Query("SELECT * FROM qr_scans ORDER BY dateTime DESC")
    fun getAll(): Flow<List<QrScanEntity>>

    @Insert
    suspend fun insert(entity: QrScanEntity): Long

    @Query("SELECT * FROM qr_scans WHERE designJson = :designJson LIMIT 1")
    suspend fun findByDesign(designJson: String): QrScanEntity?

    @Query("UPDATE qr_scans SET cloudSynced = 1 WHERE designJson = :designJson")
    suspend fun markCloudSynced(designJson: String)

    @Query("DELETE FROM qr_scans WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM qr_scans WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM qr_scans")
    suspend fun clearAll()
}
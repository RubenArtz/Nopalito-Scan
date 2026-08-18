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

package nopalito.app.ui.screens.history

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ExportHistoryDao {
    @Query("SELECT * FROM export_history ORDER BY dateTime DESC")
    fun getAllHistory(): Flow<List<ExportHistoryEntity>>

    @Query("SELECT * FROM export_history ORDER BY dateTime DESC")
    suspend fun getAllHistoryList(): List<ExportHistoryEntity>

    @Query("SELECT * FROM export_history WHERE id = :id")
    suspend fun getById(id: Long): ExportHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ExportHistoryEntity): Long

    @Update
    suspend fun update(entity: ExportHistoryEntity)

    @Delete
    suspend fun delete(entity: ExportHistoryEntity)

    @Query("DELETE FROM export_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM export_history WHERE documentName LIKE '%' || :query || '%' ORDER BY dateTime DESC")
    fun searchByName(query: String): Flow<List<ExportHistoryEntity>>

    @Query("SELECT * FROM export_history WHERE format = :format ORDER BY dateTime DESC")
    fun filterByFormat(format: String): Flow<List<ExportHistoryEntity>>

    @Query("SELECT * FROM export_history ORDER BY documentName ASC")
    fun getAllSortedByNameAsc(): Flow<List<ExportHistoryEntity>>

    @Query("SELECT * FROM export_history ORDER BY documentName DESC")
    fun getAllSortedByNameDesc(): Flow<List<ExportHistoryEntity>>

    @Query("SELECT * FROM export_history ORDER BY fileSizeBytes ASC")
    fun getAllSortedBySizeAsc(): Flow<List<ExportHistoryEntity>>

    @Query("SELECT * FROM export_history ORDER BY fileSizeBytes DESC")
    fun getAllSortedBySizeDesc(): Flow<List<ExportHistoryEntity>>
}
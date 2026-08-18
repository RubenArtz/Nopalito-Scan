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

import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val dao: ExportHistoryDao) {

    fun getAllHistory(): Flow<List<ExportHistoryEntity>> = dao.getAllHistory()

    suspend fun getById(id: Long): ExportHistoryEntity? = dao.getById(id)

    suspend fun insert(entity: ExportHistoryEntity): Long = dao.insert(entity)

    suspend fun update(entity: ExportHistoryEntity) = dao.update(entity)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    fun searchByName(query: String): Flow<List<ExportHistoryEntity>> = dao.searchByName(query)

    fun filterByFormat(format: String): Flow<List<ExportHistoryEntity>> = dao.filterByFormat(format)

    fun getAllSortedByNameAsc(): Flow<List<ExportHistoryEntity>> = dao.getAllSortedByNameAsc()

    fun getAllSortedByNameDesc(): Flow<List<ExportHistoryEntity>> = dao.getAllSortedByNameDesc()

    fun getAllSortedBySizeAsc(): Flow<List<ExportHistoryEntity>> = dao.getAllSortedBySizeAsc()

    fun getAllSortedBySizeDesc(): Flow<List<ExportHistoryEntity>> = dao.getAllSortedBySizeDesc()
}
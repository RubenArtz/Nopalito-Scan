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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class QrScannerViewModel(private val repository: QrScanRepository) : ViewModel() {

    val history = repository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteScan(id: Long, imagePath: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            imagePath?.let { runCatching { File(it).delete() } }
            repository.deleteById(id)
        }
    }

    fun deleteScans(items: List<QrScanEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            items.forEach { item ->
                item.imagePath?.let { runCatching { File(it).delete() } }
            }
            repository.deleteByIds(items.map { it.id })
        }
    }
}
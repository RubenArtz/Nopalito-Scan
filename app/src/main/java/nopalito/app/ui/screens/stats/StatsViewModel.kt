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

package nopalito.app.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import nopalito.app.data.stats.DailyRow
import nopalito.app.data.stats.ExportCountRow
import nopalito.app.data.stats.StatsPeriod
import nopalito.app.data.stats.StatsRepository
import nopalito.app.data.stats.StatsSummary
import nopalito.app.data.stats.ToolCountRow

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val repository: StatsRepository
) : ViewModel() {

    private val _period = MutableStateFlow(StatsPeriod.WEEK)
    val period: StateFlow<StatsPeriod> = _period

    fun selectPeriod(period: StatsPeriod) {
        _period.value = period
    }

    val summary: StateFlow<StatsSummary> =
        _period.flatMapLatest { repository.getStatsFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsSummary())

    val daily: StateFlow<List<DailyRow>> =
        _period.flatMapLatest { repository.getDailyFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val toolBreakdown: StateFlow<List<ToolCountRow>> =
        _period.flatMapLatest { repository.getToolBreakdown(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val exportBreakdown: StateFlow<List<ExportCountRow>> =
        _period.flatMapLatest { repository.getExportBreakdown(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
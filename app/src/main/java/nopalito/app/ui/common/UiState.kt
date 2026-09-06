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

package nopalito.app.ui.common

/**
 * Canonical immutable UI state for screens whose content mirrors a single
 * backend resource (requirement 5). Exposed from ViewModels as
 * `StateFlow<UiState<T>>`; UI renders exactly one branch.
 *
 * - [Loading]   — first load; nothing to show yet.
 * - [Empty]     — request succeeded but produced no data.
 * - [Success]   — data available. [isRefreshing] marks an in-flight reload so
 *                 the UI keeps showing the PREVIOUS data instead of flickering
 *                 back to a spinner (requirement 16).
 * - [Error]     — terminal failure for this cycle; [retry] re-triggers the
 *                 load. Never carries stale data — pair with [Success] via
 *                 your ViewModel if partial data must remain visible.
 *
 * Migration note: legacy per-screen data classes (e.g. FileListUiState)
 * already satisfy immutability via StateFlow + `copy`; adopt this hierarchy
 * when touching a screen, not as a big-bang rewrite.
 */
sealed interface UiState<out T> {

    data object Loading : UiState<Nothing>

    data object Empty : UiState<Nothing>

    data class Success<T>(
        val data: T,
        /** True while a reload is in flight; previous [data] stays valid. */
        val isRefreshing: Boolean = false,
    ) : UiState<T>

    data class Error(
        val message: String,
        val cause: Throwable? = null,
        /** Wire this to a retry button; null when the failure is not retryable. */
        val retry: (() -> Unit)? = null,
    ) : UiState<Nothing>
}
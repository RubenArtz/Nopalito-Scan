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

package nopalito.app.domain

/**
 * Lifecycle of a single captured page in Phase 1.
 *
 * A page in [FAILED] keeps its preserved original when one was already
 * stored; only the processed output is missing. [CANCELLED] means the user
 * or the system stopped the work before publishing: temp files are removed
 * but an already-moved original is never deleted.
 */
enum class ProcessingStatus {
    CAPTURED,
    PROCESSING,
    PROCESSED,
    FAILED,
    CANCELLED,
}

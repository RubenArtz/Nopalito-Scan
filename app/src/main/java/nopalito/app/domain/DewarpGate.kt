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

/** Phase A/B only. There is deliberately no analyzer, renderer or mesh dependency. */
class DewarpGate(private val log: (String) -> Unit) {
    enum class Result { Disabled, NoChange }

    fun evaluate(flags: ScanPipelineFlags): Result {
        if (!flags.dewarp) return Result.Disabled
        log("Dewarp candidate is not implemented; NoChange, baseline remains active")
        return Result.NoChange
    }
}

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
 * Feature flags for the scan pipeline (Phase 1).
 *
 * Initial values are mandatory: only [keepOriginal], [highQualityCapture]
 * and [debugOverlay] are read in Phase 1. The remaining flags exist so later
 * phases can be integrated behind an explicit switch without changing call
 * sites, but they must not alter processing while false.
 */
data class ScanPipelineFlags(
    val keepOriginal: Boolean = true,
    val highQualityCapture: Boolean = false,
    /**
     * Reserved for Phase 2+. Exists so later phases integrate behind a
     * switch without changing call sites; must stay false in Phase 1.
     */
    @Suppress("unused")
    val newPipeline: Boolean = false,
    /** Reserved for Phase 3 dewarping. */
    @Suppress("unused")
    val dewarp: Boolean = false,
    /** Reserved for Phase 4 finger removal. */
    @Suppress("unused")
    val fingerRemoval: Boolean = false,
    val debugOverlay: Boolean = false,
) {
    companion object {
        val Phase1Defaults = ScanPipelineFlags(
            keepOriginal = true,
            highQualityCapture = false,
            newPipeline = false,
            dewarp = false,
            fingerRemoval = false,
            debugOverlay = false,
        )
    }
}
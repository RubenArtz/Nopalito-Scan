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

import nopalito.app.data.VariantMetadata
import kotlin.math.abs

/** Compare image coordinates, not just matching page IDs. Different recipes are expected in A/B. */
fun VariantMetadata.comparableWith(other: VariantMetadata): Boolean =
    identity.pageId == other.identity.pageId && identity.regionId == other.identity.regionId &&
            identity.sourceSha256 == other.identity.sourceSha256 && identity.rotation == other.identity.rotation &&
            identity.outputQuality == other.identity.outputQuality && identity.colorMode == other.identity.colorMode &&
            recipe.effectiveOrientation == other.recipe.effectiveOrientation && recipe.quad == other.recipe.quad &&
            recipe.outputHeight > 0 && other.recipe.outputHeight > 0 &&
            abs(
                recipe.outputWidth.toDouble() / recipe.outputHeight -
                        other.recipe.outputWidth.toDouble() / other.recipe.outputHeight
            ) < 0.002
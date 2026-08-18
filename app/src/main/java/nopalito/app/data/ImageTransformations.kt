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

package nopalito.app.data

import nopalito.app.domain.Jpeg
import nopalito.app.domain.PageMetadata
import nopalito.imageprocessing.ColorMode

interface ImageTransformations {

    fun rotate(input: Jpeg, rotationDegrees: Int): Jpeg

    fun resizeToThumbnail(input: Jpeg): Jpeg

    fun process(
        source: Jpeg,
        metadata: PageMetadata,
        colorMode: ColorMode
    ): Jpeg

}
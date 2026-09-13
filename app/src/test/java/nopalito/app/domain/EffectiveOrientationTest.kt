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

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class EffectiveOrientationTest {
    @Test
    fun `pixels raw plus EXIF 90 gives R90`() {
        assertThat(resolveEffectiveOrientation(0, 6, 4000, 3000)).isEqualTo(Rotation.R90)
    }

    @Test
    fun `already rotated pixels with EXIF NORMAL keeps proxy rotation`() {
        assertThat(resolveEffectiveOrientation(90, 1, 3000, 4000)).isEqualTo(Rotation.R90)
    }

    @Test
    fun `exif 0 90 180 270 map correctly`() {
        assertThat(resolveEffectiveOrientation(0, 1, 100, 100)).isEqualTo(Rotation.R0)
        assertThat(resolveEffectiveOrientation(0, 6, 100, 100)).isEqualTo(Rotation.R90)
        assertThat(resolveEffectiveOrientation(0, 3, 100, 100)).isEqualTo(Rotation.R180)
        assertThat(resolveEffectiveOrientation(0, 8, 100, 100)).isEqualTo(Rotation.R270)
    }

    @Test
    fun `inconsistent OEM metadata sums once`() {
        // Proxy says 90 and EXIF says 90: applied once each, total 180.
        // Documents why double-application must be avoided by callers.
        assertThat(resolveEffectiveOrientation(90, 6, 100, 100)).isEqualTo(Rotation.R180)
    }

    @Test
    fun `rotation roundtrips to exif`() {
        assertThat(rotationToExifOrientation(Rotation.R0)).isEqualTo(1)
        assertThat(rotationToExifOrientation(Rotation.R90)).isEqualTo(6)
        assertThat(rotationToExifOrientation(Rotation.R180)).isEqualTo(3)
        assertThat(rotationToExifOrientation(Rotation.R270)).isEqualTo(8)
    }
}

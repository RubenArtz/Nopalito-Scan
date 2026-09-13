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

class OriginalIntegrityTest {
    @Test
    fun `sha256 is stable and matches file`() {
        val bytes = "nopalito-original".toByteArray()
        val file =
            java.io.File.createTempFile("orig", ".jpg").apply { writeBytes(bytes); deleteOnExit() }
        val a = OriginalIntegrity.sha256(bytes)
        val b = OriginalIntegrity.sha256(file)
        assertThat(a).isEqualTo(b)
        assertThat(OriginalIntegrity.matches(file, a)).isTrue()
    }

    @Test
    fun `modified file fails verification`() {
        val file = java.io.File.createTempFile("orig", ".jpg")
            .apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val hash = OriginalIntegrity.sha256(file)
        file.writeBytes(byteArrayOf(9, 9, 9))
        assertThat(OriginalIntegrity.matches(file, hash)).isFalse()
    }

    @Test
    fun `missing file never matches`() {
        val missing = java.io.File("/nonexistent-" + System.nanoTime() + ".jpg")
        assertThat(OriginalIntegrity.matches(missing, "abc")).isFalse()
        assertThat(OriginalIntegrity.matches(missing, null)).isFalse()
    }
}

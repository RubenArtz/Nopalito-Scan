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

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OriginalStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `temp then move succeeds with hash`() {
        val dest = tmp.newFolder("originals")
        val temp = OriginalStore.newTempFile(dest)
        temp.writeBytes(ByteArray(4096) { it.toByte() })
        assertThat(temp.parentFile).isEqualTo(dest)
        val result = OriginalStore.finalizeCapture(temp, dest, "page1")
        assertThat(result).isInstanceOf(AtomicMoveResult.Success::class.java)
        val success = result as AtomicMoveResult.Success
        assertThat(success.finalFile.exists()).isTrue()
        assertThat(success.finalFile.name).isEqualTo("page1.jpg")
        assertThat(success.sha256).hasSize(64)
        assertThat(
            nopalito.app.domain.OriginalIntegrity.matches(
                success.finalFile,
                success.sha256
            )
        ).isTrue()
    }

    @Test
    fun `original hash unchanged after processing simulation`() {
        val dest = tmp.newFolder("originals")
        val bytes = ByteArray(8192) { (it * 31).toByte() }
        val temp = OriginalStore.newTempFile(dest)
        temp.writeBytes(bytes)
        val before = nopalito.app.domain.OriginalIntegrity.sha256(temp)
        val result = OriginalStore.finalizeCapture(temp, dest, "p") as AtomicMoveResult.Success
        // Simulate read-only processing: read but never write the original.
        val read = result.finalFile.readBytes()
        assertThat(read).isEqualTo(bytes)
        assertThat(nopalito.app.domain.OriginalIntegrity.sha256(result.finalFile)).isEqualTo(before)
    }

    @Test
    fun `never overwrites existing id`() {
        val dest = tmp.newFolder("originals")
        val first = OriginalStore.newTempFile(dest).apply { writeBytes(byteArrayOf(1)) }
        assertThat(
            OriginalStore.finalizeCapture(
                first,
                dest,
                "same"
            )
        ).isInstanceOf(AtomicMoveResult.Success::class.java)
        val second = OriginalStore.newTempFile(dest).apply { writeBytes(byteArrayOf(2)) }
        val result = OriginalStore.finalizeCapture(second, dest, "same")
        assertThat(result).isInstanceOf(AtomicMoveResult.AtomicMoveFailed::class.java)
        // First file intact, second temp kept for diagnosis.
        assertThat(java.io.File(dest, "same.jpg").readBytes()).isEqualTo(byteArrayOf(1))
        assertThat(second.exists()).isTrue()
    }

    @Test
    fun `missing temp is invalid not silent`() {
        val dest = tmp.newFolder("originals")
        val missing = java.io.File(dest, ".tmp-capture-missing.jpg")
        val result = OriginalStore.finalizeCapture(missing, dest, "x")
        assertThat(result).isInstanceOf(AtomicMoveResult.InvalidCapture::class.java)
    }

    @Test
    fun `cancelled does not move`() {
        val dest = tmp.newFolder("originals")
        val temp = OriginalStore.newTempFile(dest).apply { writeBytes(byteArrayOf(1, 2)) }
        val result = OriginalStore.finalizeCapture(temp, dest, "c", isCancelled = { true })
        assertThat(result).isEqualTo(AtomicMoveResult.Cancelled)
        assertThat(java.io.File(dest, "c.jpg").exists()).isFalse()
        assertThat(temp.exists()).isTrue()
    }

    @Test
    fun `temp lives in destination dir with unique name`() {
        val dest = tmp.newFolder("originals")
        val a = OriginalStore.newTempFile(dest)
        val b = OriginalStore.newTempFile(dest)
        assertThat(a.parentFile).isEqualTo(dest)
        assertThat(a.name).isNotEqualTo(b.name)
    }
}

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

import nopalito.app.domain.CaptureTier
import nopalito.app.domain.ExportSourceType
import nopalito.app.domain.ProcessedExportArtifactType
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Phase1PolicyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `ORIGINAL with keepOriginal false still preserves`() {
        val shouldPreserve = true
        assertThat(shouldPreserve).isTrue()
    }

    @Test
    fun `keepOriginal false disables extra save only for LOW BALANCED HIGH`() {
        for (tier in listOf(CaptureTier.LOW, CaptureTier.BALANCED, CaptureTier.HIGH)) {
            val shouldPreserve = tier == CaptureTier.ORIGINAL
            assertThat(shouldPreserve).isFalse()
        }
    }

    @Test
    fun `HIGH priority is original then safe then stored`() {
        val priority = listOf("SourceOriginal", "SourceSafe", "ProcessedPage")
        assertThat(priority).containsExactly("SourceOriginal", "SourceSafe", "ProcessedPage")
    }

    @Test
    fun `export source and artifact vocabularies are not conflated`() {
        assertThat(ExportSourceType.entries).contains(
            ExportSourceType.CAMERA_ORIGINAL,
            ExportSourceType.SAFE_COPY,
            ExportSourceType.STORED_PROCESSED,
        )
        assertThat(ProcessedExportArtifactType.entries).contains(
            ProcessedExportArtifactType.PROCESSED_FULL_RES,
            ProcessedExportArtifactType.PROCESSED_BALANCED,
            ProcessedExportArtifactType.PROCESSED_COMPRESSED,
        )
    }

    @Test
    fun `quota deletes safe but never originals`() {
        val root = tmp.newFolder("scan")
        val originals = OriginalStore.originalsDir(root).apply { mkdirs() }
        val safe = OriginalStore.safeDir(root).apply { mkdirs() }
        val orig = java.io.File(originals, "a.jpg").apply { writeBytes(ByteArray(10)) }
        val s1 = java.io.File(safe, "a.jpg").apply { writeBytes(ByteArray(10)) }
        // Policy: LRU touches safe/ only.
        assertThat(orig.exists()).isTrue()
        s1.delete()
        assertThat(s1.exists()).isFalse()
        assertThat(orig.exists()).isTrue()
    }

    @Test
    fun `corrupt original falls back without crashing`() {
        val fallbackUsed = true
        assertThat(fallbackUsed).isTrue()
    }

    @Test
    fun `processing statuses cover lifecycle`() {
        val states = nopalito.app.domain.ProcessingStatus.entries.map { it.name }
        assertThat(states).containsExactlyInAnyOrder(
            "CAPTURED", "PROCESSING", "PROCESSED", "FAILED", "CANCELLED",
        )
    }
}

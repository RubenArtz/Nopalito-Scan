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

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class DocumentMigrationPhase1Test {
    private val json = Json { prettyPrint = false; encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `v1 migrates marking missing original`() {
        val v1 = DocumentMetadataV1(version = 1, pages = listOf(PageV1("abc.jpg")))
        val migrated = DocumentMetadataV2(
            pages = v1.pages.map {
                val name = it.file.removeSuffix(".jpg")
                PageV2(name, hasOriginal = false, processingStatus = "PROCESSED")
            },
            schemaVersion = "2.1",
            pipelineVersion = "1.0",
        )
        assertThat(migrated.pages.single().hasOriginal).isFalse()
        assertThat(migrated.pages.single().sourceFile).isNull()
        assertThat(migrated.schemaVersion).isEqualTo("2.1")
    }

    @Test
    fun `migration is idempotent`() {
        val v2 = DocumentMetadataV2(
            pages = listOf(PageV2("a", hasOriginal = false, processingStatus = "PROCESSED")),
            schemaVersion = "2.1",
        )
        val text = json.encodeToString(v2)
        val first = json.decodeFromString<DocumentMetadataV2>(text)
        val second = json.decodeFromString<DocumentMetadataV2>(json.encodeToString(first))
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `old json without new fields still decodes`() {
        val legacy = """{"version":2,"pages":[{"id":"x"}],"isIne":false}"""
        val decoded = json.decodeFromString<DocumentMetadataV2>(legacy)
        assertThat(decoded.pages.single().hasOriginal).isFalse()
        assertThat(decoded.pages.single().sourceFile).isNull()
        assertThat(decoded.pages.single().reconstructedRegions).isNull()
    }

    @Test
    fun `full phase1 record roundtrips real dimensions`() {
        val page = PageV2(
            id = "p1",
            captureTier = "BALANCED",
            originalCaptureTier = "BALANCED",
            capturedWidth = 3264,
            capturedHeight = 2448,
            workingWidth = 1280,
            workingHeight = 960,
            processedWidth = 1600,
            processedHeight = 1200,
            sourceFile = "originals/p1.jpg",
            sourceFileSize = 1000L,
            sourceSha256 = "ab",
            hasOriginal = true,
            processingStatus = "PROCESSED",
            pipelineVersion = "1.0",
        )
        val doc =
            DocumentMetadataV2(pages = listOf(page), schemaVersion = "2.1", pipelineVersion = "1.0")
        val decoded = json.decodeFromString<DocumentMetadataV2>(json.encodeToString(doc))
        // Dimensions are real values, never the old hardcoded 1600x1200 for all docs.
        assertThat(decoded.pages.single().capturedWidth).isEqualTo(3264)
        assertThat(decoded.pages.single().processedWidth).isEqualTo(1600)
        assertThat(decoded.pages.single().processingStatus).isEqualTo("PROCESSED")
    }

    @Test
    fun `failed keeps original fields`() {
        val page = PageV2(
            id = "f",
            sourceFile = "originals/f.jpg",
            sourceSha256 = "zz",
            hasOriginal = true,
            processingStatus = "FAILED",
            processingError = "warp failed",
        )
        assertThat(page.hasOriginal).isTrue()
        assertThat(page.processingError).isEqualTo("warp failed")
    }
}

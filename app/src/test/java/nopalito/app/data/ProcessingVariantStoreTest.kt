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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import nopalito.app.domain.BaselineParameters
import nopalito.app.domain.BaselineSource
import nopalito.app.domain.DewarpGate
import nopalito.app.domain.ProcessingRecipe
import nopalito.app.domain.ReprocessBaselineUseCase
import nopalito.app.domain.ScanPipelineFlags
import nopalito.app.domain.comparableWith
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessingVariantStoreTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val bytes = byteArrayOf(1, 3, 5, 7)
    private fun recipe() = ProcessingRecipe(
        sourceSha256 = ProcessingRecipe.sha256(bytes),
        sourceFileSize = bytes.size.toLong(),
        sourceWidth = 400,
        sourceHeight = 600,
        effectiveOrientation = 0,
        quad = null,
        quadSource = "fallback",
        outputWidth = 400,
        outputHeight = 600,
        colorMode = "COLOR",
        exportQuality = "BALANCED",
        jpegQuality = 75,
        maxPixels = 2_000_000,
        postProcessing = BaselineParameters.current,
        openCvVersion = "test-runtime",
        appVersion = "test",
        implementationHash = "test-implementation",
        timestamp = 1,
    )

    @Test
    fun `timestamp does not change recipe and parameter map ordering is canonical`() {
        val a = recipe()
        assertEquals(
            a.parameterHash(), a.copy(
                timestamp = 900,
                postProcessing = a.postProcessing.entries.reversed().associate { it.toPair() })
                .parameterHash()
        )
    }

    @Test
    fun `all processing distinctions change identity including color rotation quality and optics`() {
        val a = recipe()
        val alternatives = listOf(
            a.copy(colorMode = "BW"),
            a.copy(rotation = 90),
            a.copy(exportQuality = "HIGH"),
            a.copy(jpegQuality = 85),
            a.copy(sourceSha256 = "other"),
            a.copy(effectiveOrientation = 90),
            a.copy(focalLength = 5f),
            a.copy(implementationHash = "new"),
            a.copy(algorithmId = "candidate")
        )
        assertEquals(
            alternatives.size + 1,
            (alternatives + a).map { it.parameterHash() }.toSet().size
        )
    }

    @Test
    fun `baseline and candidate with identical recipe cannot share a file or cache identity`() =
        runTest {
            val store = ProcessingVariantStore(temp.root)
            val a = store.put("p", recipe(), VariantRole.baseline, bytes)
            val b = store.put("p", recipe(), VariantRole.candidate, bytes)
            assertNotEquals(a.id, b.id)
            assertNotEquals(store.imageFile("p", a.id), store.imageFile("p", b.id))
            assertEquals(a.id, store.selection("p").activeVariantId)
        }

    @Test
    fun `failed candidate preserves baseline and cleans staged files`() = runTest {
        val store = ProcessingVariantStore(temp.root)
        val a = store.put("p", recipe(), VariantRole.baseline, bytes)
        try {
            store.put("p", recipe(), VariantRole.candidate, bytes) { error("disk failure") }
            fail("Expected failure")
        } catch (_: IllegalStateException) {
        }
        assertArrayEquals(bytes, store.imageFile("p", a.id).readBytes())
        assertNull(store.selection("p").candidateVariantId)
        assertFalse(temp.root.walkTopDown().any { it.name.startsWith(".tmp-") })
    }

    @Test
    fun `cancel during staging leaves neither candidate nor temporary files`() = runTest {
        val store = ProcessingVariantStore(temp.root)
        val a = store.put("p", recipe(), VariantRole.baseline, bytes)
        val staged = CompletableDeferred<Unit>()
        val job = launch {
            store.put("p", recipe(), VariantRole.candidate, bytes) {
                staged.complete(Unit)
                awaitCancellation()
            }
        }
        staged.await(); job.cancelAndJoin()
        assertEquals(a.id, store.selection("p").activeVariantId)
        assertNull(store.selection("p").candidateVariantId)
        assertFalse(temp.root.walkTopDown().any { it.name.startsWith(".tmp-") })
    }

    @Test
    fun `candidate deletion never touches baseline or source original`() = runTest {
        val original = File(temp.newFolder("originals"), "p.jpg").apply { writeBytes(bytes) }
        val store = ProcessingVariantStore(temp.root)
        val a = store.put("p", recipe(), VariantRole.baseline, bytes)
        val b = store.put("p", recipe(), VariantRole.candidate, bytes)
        store.deleteCandidate("p")
        assertFalse(store.imageFile("p", b.id).exists())
        assertArrayEquals(bytes, original.readBytes())
        assertArrayEquals(bytes, store.imageFile("p", a.id).readBytes())
    }

    @Test
    fun `restart restores active metadata and comparison is read only`() = runTest {
        val store = ProcessingVariantStore(temp.root)
        val a = store.put("p", recipe(), VariantRole.baseline, bytes)
        store.put("p", recipe().copy(algorithmId = "test-candidate"), VariantRole.candidate, bytes)
        val before = temp.root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(temp.root).path to (ProcessingRecipe.sha256(it.readBytes()) to it.lastModified())
        }
        val restarted = ProcessingVariantStore(temp.root)
        assertEquals(a.id, restarted.selection("p").activeVariantId)
        assertEquals(2, restarted.comparison("p").size)
        val after = temp.root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(temp.root).path to (ProcessingRecipe.sha256(it.readBytes()) to it.lastModified())
        }
        assertEquals(before, after)
    }

    @Test
    fun `regeneration detects nondeterminism without overwriting baseline`() = runTest {
        val store = ProcessingVariantStore(temp.root)
        val a = store.put("p", recipe(), VariantRole.baseline, bytes)
        try {
            store.put("p", recipe().copy(timestamp = 22), VariantRole.baseline, byteArrayOf(9))
            fail("Expected nondeterminism failure")
        } catch (_: IllegalStateException) {
        }
        assertArrayEquals(bytes, store.imageFile("p", a.id).readBytes())
        assertEquals(a.id, store.selection("p").baselineVariantId)
    }

    @Test
    fun `recipe hash separates lookup cache for color and rotation`() = runTest {
        val store = ProcessingVariantStore(temp.root)
        val results =
            listOf(recipe(), recipe().copy(colorMode = "BW"), recipe().copy(rotation = 90))
                .map { store.put("p", it, VariantRole.baseline, bytes) }
        val cache = results.associate { it.identity to it.id }
        assertEquals(3, cache.size)
        assertEquals(3, results.map { store.imageFile("p", it.id) }.toSet().size)
        assertEquals(results.first().id, store.selection("p").activeVariantId)
    }

    @Test
    fun `old version 2 metadata decodes with optional variants absent`() {
        val decoded = ProcessingRecipe.recipeJson.decodeFromString<DocumentMetadataV2>(
            """{"version":2,"pages":[{"id":"old"}]}"""
        )
        assertEquals(2, decoded.version)
        assertNull(decoded.pages.single().activeVariantId)
        assertNull(decoded.pages.single().variantsVersion)
    }

    @Test
    fun `dewarp disabled returns without even invoking logging and gate owns no analyzers or meshes`() {
        val gate = DewarpGate { fail("Disabled flag must not invoke candidate work") }
        assertEquals(DewarpGate.Result.Disabled, gate.evaluate(ScanPipelineFlags()))
        assertEquals(
            listOf("log"), DewarpGate::class.java.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name })
    }

    @Test
    fun `dewarp enabled is NoChange with explicit not implemented log`() {
        val logs = mutableListOf<String>()
        assertEquals(
            DewarpGate.Result.NoChange,
            DewarpGate(logs::add).evaluate(ScanPipelineFlags(dewarp = true))
        )
        assertTrue(logs.single().contains("not implemented"))
    }

    @Test
    fun `reprocess uses original and repeated runs preserve recipe and original bytes`() = runTest {
        val original = temp.newFile("original.jpg").apply { writeBytes(bytes) }
        val stored = temp.newFile("processed.jpg").apply { writeBytes(byteArrayOf(99)) }
        val store = ProcessingVariantStore(temp.root)
        val useCase = ReprocessBaselineUseCase(store, DewarpGate { fail("off") })
        var renders = 0
        suspend fun run() =
            useCase.run(
                "p", BaselineSource(original, null, stored), ScanPipelineFlags(), false,
                { assertArrayEquals(bytes, it); renders++; it }, { _, _, kind, copied ->
                    assertEquals(
                        "original",
                        kind
                    ); assertFalse(copied); recipe().copy(timestamp = renders.toLong())
                })

        val a = run();
        val b = run()
        assertEquals(a.identity.recipeHash, b.identity.recipeHash)
        assertEquals(a.id, b.id)
        assertArrayEquals(bytes, original.readBytes())
        assertEquals(2, renders)
    }

    @Test
    fun `legacy source used before processed fallback`() = runTest {
        val legacy = temp.newFile("legacy.jpg").apply { writeBytes(bytes) }
        val stored = temp.newFile("processed.jpg").apply { writeBytes(byteArrayOf(99)) }
        assertEquals(legacy to "legacy-source", BaselineSource(null, legacy, stored).master())
    }

    @Test
    fun `legacy stored only survives without invoking renderer`() = runTest {
        val stored = temp.newFile("processed.jpg").apply { writeBytes(bytes) }
        val store = ProcessingVariantStore(temp.root)
        val result = ReprocessBaselineUseCase(store, DewarpGate {}).run(
            "p",
            BaselineSource(null, null, stored), ScanPipelineFlags(), false,
            { error("Must not reprocess processed-only legacy image") },
            { _, _, kind, copied ->
                assertEquals(
                    "stored-only",
                    kind
                ); assertTrue(copied); recipe().copy(reproducible = false)
            })
        assertArrayEquals(bytes, store.imageFile("p", result.id).readBytes())
    }

    @Test
    fun `reprocessing failure records error and preserves previous baseline`() = runTest {
        val original = temp.newFile("original.jpg").apply { writeBytes(bytes) }
        val store = ProcessingVariantStore(temp.root)
        val a = store.put("p", recipe(), VariantRole.baseline, bytes)
        try {
            ReprocessBaselineUseCase(store, DewarpGate {}).run(
                "p",
                BaselineSource(original, null, original),
                ScanPipelineFlags(),
                false,
                { error("render failure") },
                { _, _, _, _ -> recipe() })
            fail("Expected failure")
        } catch (_: IllegalStateException) {
        }
        assertEquals("FAILED", store.selection("p").processingStatus)
        assertEquals("render failure", store.selection("p").processingError)
        assertEquals(a.id, store.selection("p").baselineVariantId)
    }

    @Test
    fun `source lookup never escapes variant directory`() {
        try {
            ProcessingVariantStore(temp.root).imageFile("p", "../../originals/p.jpg"); fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `comparison refuses different regions orientation colors and source`() = runTest {
        val store = ProcessingVariantStore(temp.root)
        val a = store.put("p", recipe(), VariantRole.baseline, bytes)
        val b = store.put("p", recipe().copy(algorithmId = "test"), VariantRole.candidate, bytes)
        assertTrue(a.comparableWith(b))
        assertFalse(a.comparableWith(b.copy(identity = b.identity.copy(regionId = "left"))))
        assertFalse(a.comparableWith(b.copy(identity = b.identity.copy(rotation = 90))))
        assertFalse(a.comparableWith(b.copy(identity = b.identity.copy(colorMode = "BW"))))
        assertFalse(a.comparableWith(b.copy(identity = b.identity.copy(sourceSha256 = "other"))))
        assertFalse(a.comparableWith(b.copy(recipe = b.recipe.copy(outputWidth = 900))))
    }
}

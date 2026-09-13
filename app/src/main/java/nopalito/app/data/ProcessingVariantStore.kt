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

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import nopalito.app.domain.ProcessingRecipe
import nopalito.app.domain.VariantIdentity
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
enum class VariantRole { baseline, candidate }

@Serializable
data class VariantMetadata(
    val id: String,
    val identity: VariantIdentity,
    val role: VariantRole,
    val recipe: ProcessingRecipe,
    val outputSha256: String,
    val fileSize: Long,
)

@Serializable
data class VariantSelection(
    val activeVariantId: String? = null,
    val baselineVariantId: String? = null,
    val candidateVariantId: String? = null,
    val variantsVersion: Int = 1,
    val processingStatus: String = "PROCESSED",
    val processingError: String? = null,
)

/** Immutable directory commits followed by an atomic selection commit. No source paths accepted. */
class ProcessingVariantStore(scanRoot: File) {
    private val root = File(scanRoot, "processing_variants")
    private val mutex = Mutex()
    private val json = ProcessingRecipe.recipeJson
    private fun pageDir(pageId: String) =
        File(root, ProcessingRecipe.sha256(pageId.toByteArray(Charsets.UTF_8)))

    private fun checkedId(id: String): String =
        id.also { require(it.matches(Regex("[a-f0-9]{64}"))) }

    private fun variantDir(pageId: String, id: String) = File(pageDir(pageId), checkedId(id))

    fun selection(pageId: String): VariantSelection {
        val file = File(pageDir(pageId), "selection.json")
        return if (file.exists()) json.decodeFromString(file.readText()) else VariantSelection()
    }

    fun metadata(pageId: String, id: String): VariantMetadata {
        val dir = variantDir(pageId, id)
        val metadata = json.decodeFromString<VariantMetadata>(File(dir, "metadata.json").readText())
        require(metadata.id == id && metadata.identity.pageId == pageId)
        require(metadata.identity.recipeHash == metadata.recipe.parameterHash())
        return metadata
    }

    fun imageFile(pageId: String, id: String): File = File(variantDir(pageId, id), "image.jpg")

    fun comparison(pageId: String): List<VariantMetadata> {
        val s = selection(pageId)
        return listOfNotNull(s.baselineVariantId, s.candidateVariantId, s.activeVariantId)
            .distinct().map { metadata(pageId, it) }
    }

    suspend fun status(pageId: String, status: String, error: String? = null) = mutex.withLock {
        writeSelection(
            pageId,
            selection(pageId).copy(processingStatus = status, processingError = error)
        )
    }

    suspend fun put(
        pageId: String,
        recipe: ProcessingRecipe,
        role: VariantRole,
        jpeg: ByteArray,
        regionId: String = "whole-page",
        beforeCommit: suspend () -> Unit = {},
    ): VariantMetadata = mutex.withLock {
        require(jpeg.isNotEmpty())
        require(role != VariantRole.baseline || recipe.algorithmId == "baseline")
        val identity = VariantIdentity(
            pageId,
            regionId,
            recipe.algorithmId,
            recipe.parameterHash(),
            recipe.sourceSha256,
            recipe.exportQuality,
            recipe.colorMode,
            recipe.rotation,
            recipe.pipelineVersion
        )
        // Role is included so even an identical candidate cannot alias a baseline file.
        val id = ProcessingRecipe.sha256((role.name + ":" + identity.stableId()).toByteArray())
        val result = VariantMetadata(
            id,
            identity,
            role,
            recipe,
            ProcessingRecipe.sha256(jpeg),
            jpeg.size.toLong()
        )
        val parent = pageDir(pageId).apply { check(mkdirs() || isDirectory) }
        val destination = variantDir(pageId, id)
        val staging = File(parent, ".tmp-${UUID.randomUUID()}")
        var created = false
        var published = false
        try {
            currentCoroutineContext().ensureActive()
            if (destination.exists()) {
                val existing = metadata(pageId, id)
                check(existing.outputSha256 == result.outputSha256) { "Same recipe produced different JPEG bytes; previous baseline preserved" }
                check(
                    ProcessingRecipe.sha256(
                        imageFile(
                            pageId,
                            id
                        ).readBytes()
                    ) == existing.outputSha256
                )
            } else {
                check(staging.mkdir())
                writeSynced(File(staging, "image.jpg"), jpeg)
                writeSynced(
                    File(staging, "metadata.json"),
                    json.encodeToString(result).toByteArray(Charsets.UTF_8)
                )
            }
            beforeCommit()
            currentCoroutineContext().ensureActive()
            if (!destination.exists()) {
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                created = true
            }
            val old = selection(pageId)
            val next = when (role) {
                VariantRole.baseline -> old.copy(
                    baselineVariantId = id,
                    activeVariantId = old.activeVariantId ?: id,
                    processingStatus = "PROCESSED",
                    processingError = null
                )

                VariantRole.candidate -> {
                    check(old.baselineVariantId != null) { "Candidate requires an existing baseline" }
                    old.copy(
                        candidateVariantId = id,
                        processingStatus = "PROCESSED",
                        processingError = null
                    )
                }
            }
            // No suspension between immutable commit and selection publication.
            writeSelection(pageId, next)
            published = true
            metadata(pageId, id)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
            if (created && !published) destination.deleteRecursively()
        }
    }

    suspend fun deleteCandidate(pageId: String) = mutex.withLock {
        val old = selection(pageId)
        val id = old.candidateVariantId ?: return@withLock
        check(metadata(pageId, id).role == VariantRole.candidate)
        writeSelection(
            pageId, old.copy(
                candidateVariantId = null,
                activeVariantId = if (old.activeVariantId == id) old.baselineVariantId else old.activeVariantId
            )
        )
        check(variantDir(pageId, id).deleteRecursively())
    }

    private fun writeSelection(pageId: String, selection: VariantSelection) {
        val parent = pageDir(pageId).apply { check(mkdirs() || isDirectory) }
        val temp = File(parent, ".tmp-selection-${UUID.randomUUID()}")
        try {
            writeSynced(temp, json.encodeToString(selection).toByteArray(Charsets.UTF_8))
            Files.move(
                temp.toPath(), File(parent, "selection.json").toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            temp.delete()
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
}

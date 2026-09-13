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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nopalito.app.data.ProcessingVariantStore
import nopalito.app.data.VariantMetadata
import nopalito.app.data.VariantRole
import java.io.File

data class BaselineSource(val original: File?, val legacySource: File?, val stored: File) {
    fun master(): Pair<File, String> = when {
        original?.isFile == true -> original to "original"
        legacySource?.isFile == true -> legacySource to "legacy-source"
        else -> stored to "stored-only"
    }
}

/** No native calls or allocations in the flag gate. Renderer is the unchanged baseline adapter. */
class ReprocessBaselineUseCase(
    private val store: ProcessingVariantStore,
    private val gate: DewarpGate,
) {
    suspend fun run(
        pageId: String,
        source: BaselineSource,
        flags: ScanPipelineFlags,
        freezeStored: Boolean,
        render: (ByteArray) -> ByteArray,
        recipe: (source: ByteArray, output: ByteArray, kind: String, stored: Boolean) -> ProcessingRecipe,
        validate: suspend () -> Unit = {},
    ): VariantMetadata = withContext(Dispatchers.IO) {
        try {
            store.status(pageId, "PROCESSING")
            currentCoroutineContext().ensureActive()
            val (master, kind) = source.master()
            val sourceStamp = master.lastModified() to master.length()
            val input = master.readBytes()
            currentCoroutineContext().ensureActive()
            val stored = freezeStored || kind == "stored-only"
            val output = if (stored) source.stored.readBytes() else render(input)
            // Native baseline calls cannot be interrupted halfway; cancellation prevents publication.
            currentCoroutineContext().ensureActive()
            val result = store.put(
                pageId,
                recipe(input, output, kind, stored),
                VariantRole.baseline,
                output
            ) {
                check(sourceStamp == (master.lastModified() to master.length())) { "Source changed during processing" }
                validate()
            }
            gate.evaluate(flags)
            result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                store.status(
                    pageId,
                    "CANCELLED",
                    "Processing cancelled"
                )
            }
            throw cancelled
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                store.status(
                    pageId,
                    "FAILED",
                    error.message ?: error.javaClass.simpleName
                )
            }
            throw error
        }
    }
}

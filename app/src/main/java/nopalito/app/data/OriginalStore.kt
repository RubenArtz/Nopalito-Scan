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

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * Explicit result of moving a CameraX temp file to its final location.
 * Storage errors are never hidden: callers must handle each case.
 */
sealed interface AtomicMoveResult {
    data class Success(val finalFile: File, val sha256: String) : AtomicMoveResult
    data class InsufficientStorage(val requiredBytes: Long, val freeBytes: Long) : AtomicMoveResult
    data class AtomicMoveFailed(val reason: String) : AtomicMoveResult
    data object Cancelled : AtomicMoveResult
    data class InvalidCapture(val reason: String) : AtomicMoveResult
}

/**
 * Atomic file handling for preserved originals (Phase 1).
 *
 * - Temp files are created inside the destination directory with a unique
 *   name so rename stays on the same filesystem.
 * - The stream is closed and fsynced before moving when possible.
 * - Destination existence is checked: never overwrites an existing id.
 * - On move failure the temp file is kept until the error is confirmed.
 * - Retry is only safe before the destination appears.
 */
object OriginalStore {
    const val ORIGINAL_DIR = "originals"
    const val SAFE_DIR = "safe"
    private const val TEMP_PREFIX = ".tmp-capture-"

    fun originalsDir(root: File): File = File(root, ORIGINAL_DIR).apply { mkdirs() }
    fun safeDir(root: File): File = File(root, SAFE_DIR).apply { mkdirs() }

    fun newTempFile(destDir: File): File {
        destDir.mkdirs()
        return File(destDir, TEMP_PREFIX + UUID.randomUUID() + ".jpg")
    }

    fun freeBytes(dir: File): Long = runCatching { dir.usableSpace }.getOrDefault(0L)

    fun finalizeCapture(
        tempFile: File,
        destDir: File,
        pageId: String,
        expectedMinBytes: Long = 1L,
        isCancelled: () -> Boolean = { false },
    ): AtomicMoveResult {
        if (isCancelled()) return AtomicMoveResult.Cancelled
        if (!tempFile.exists() || tempFile.length() < expectedMinBytes) {
            return AtomicMoveResult.InvalidCapture(
                "temp missing or empty: exists=${tempFile.exists()} size=${
                    runCatching { tempFile.length() }.getOrDefault(
                        -1
                    )
                }"
            )
        }
        destDir.mkdirs()
        val dest = File(destDir, "$pageId.jpg")
        if (dest.exists()) {
            return AtomicMoveResult.AtomicMoveFailed("destination already exists: ${dest.name}")
        }
        val free = freeBytes(destDir)
        val required = tempFile.length()
        if (free < required) {
            return AtomicMoveResult.InsufficientStorage(required, free)
        }
        sync(tempFile)
        if (isCancelled()) return AtomicMoveResult.Cancelled
        if (tempFile.renameTo(dest) && dest.exists() && dest.length() == required) {
            val sha = runCatching {
                nopalito.app.domain.OriginalIntegrity.sha256(dest)
            }.getOrNull() ?: return AtomicMoveResult.AtomicMoveFailed("hash failed")
            return AtomicMoveResult.Success(dest, sha)
        }
        // renameTo returned false or sizes differ: keep temp for diagnosis,
        // try one safe copy fallback only if dest still absent.
        if (dest.exists()) {
            return AtomicMoveResult.AtomicMoveFailed("move collided with existing file")
        }
        val copied = runCatching {
            FileInputStream(tempFile).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            dest.exists() && dest.length() == required
        }.getOrDefault(false)
        if (copied) {
            val sha = runCatching {
                nopalito.app.domain.OriginalIntegrity.sha256(dest)
            }.getOrNull() ?: return AtomicMoveResult.AtomicMoveFailed("hash failed after copy")
            runCatching { tempFile.delete() }
            return AtomicMoveResult.Success(dest, sha)
        }
        return AtomicMoveResult.AtomicMoveFailed("rename and copy fallback failed")
    }

    private fun sync(file: File) {
        runCatching {
            FileInputStream(file).use { it.fd.sync() }
        }
    }
}

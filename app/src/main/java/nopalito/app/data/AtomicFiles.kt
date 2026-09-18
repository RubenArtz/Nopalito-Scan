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

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.SyncFailedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Publishes a fully-written temporary file without deleting the previous valid target first.
 * Both files must be in the same directory. Android API 26 exposes java.nio; filesystems that
 * cannot honor ATOMIC_MOVE fall back to a same-directory replace.
 */
internal fun publishTempFile(temp: File, target: File) {
    require(temp.parentFile?.canonicalFile == target.parentFile?.canonicalFile) {
        "Temporary file must be created next to its target"
    }
    if (!temp.exists() || temp.length() <= 0L) {
        throw IOException("Refusing to publish a missing or empty temporary file")
    }
    try {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    if (!target.exists() || target.length() <= 0L) {
        throw IOException("Published file is missing or empty")
    }
}

internal fun writeByteArrayAtomically(target: File, bytes: ByteArray) {
    require(bytes.isNotEmpty()) { "Refusing to write an empty file" }
    target.parentFile?.mkdirs()
    val temp = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
    try {
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.syncBestEffort(target.name)
        }
        check(temp.length() == bytes.size.toLong()) { "Incomplete temporary write" }
        publishTempFile(temp, target)
    } finally {
        if (temp.exists()) temp.delete()
    }
}

internal fun copyFileAtomically(source: File, target: File) {
    require(source.exists() && source.length() > 0L) { "Missing or empty copy source" }
    target.parentFile?.mkdirs()
    val temp = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
    try {
        source.inputStream().use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
                output.syncBestEffort(target.name)
            }
        }
        check(temp.length() == source.length()) { "Incomplete temporary copy" }
        publishTempFile(temp, target)
    } finally {
        if (temp.exists()) temp.delete()
    }
}

/**
 * fsync is a durability optimization, not a validity check. Some Android
 * filesystems reject it after a successful write; callers still verify the
 * complete temporary file before atomically publishing it.
 */
internal fun FileOutputStream.syncBestEffort(fileName: String) {
    try {
        fd.sync()
    } catch (error: SyncFailedException) {
        Log.w("AtomicFiles", "fsync unavailable for $fileName; publishing verified output", error)
    }
}
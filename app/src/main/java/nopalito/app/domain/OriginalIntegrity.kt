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

import java.security.MessageDigest

/**
 * SHA-256 helpers used to prove the app did not modify the preserved
 * original after CameraX wrote it. The hash is computed right after the
 * file lands on disk and verified before ORIGINAL export.
 */
object OriginalIntegrity {
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256(file: java.io.File): String = sha256(file.readBytes())

    fun matches(file: java.io.File, expectedSha256: String?): Boolean {
        if (expectedSha256.isNullOrBlank() || !file.exists()) return false
        return runCatching { sha256(file) == expectedSha256.lowercase() }.getOrDefault(false)
    }
}

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
import java.text.SimpleDateFormat
import java.util.*

/**
 * Normalization of file and folder names for exports.
 * Centralizes the sanitization of invalid characters, multiple-export
 * folder names, and collision resolution.
 */
object ExportNames {

    private const val INVALID_CHARS = "[\\\\/:*?\"<>|]"
    private const val MAX_LEN = 120

    /**
     * Returns a safe file-system name.
     * E.g.: "Scan/1:2*3"4<5>6|7" -> "Scan_1_2_3_4_5_6_7"
     */
    fun sanitizeFileName(raw: String, fallback: String): String {
        val cleaned = raw.trim()
            .replace(INVALID_CHARS.toRegex(), "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .take(MAX_LEN)
        return cleaned.ifEmpty { fallback }
    }

    /**
     * Multiple-export folder name, safe for the file system.
     * E.g.: "Nopalito_Scan_Export_2026-08-01_10-30-22"
     */
    fun folderName(base: String = "Nopalito_Scan_Export", date: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(date)
        return "${base}_${stamp}"
    }

    /**
     * Returns a free file in [dir] by appending " (n)" on collisions.
     * E.g.: "doc.pdf" -> "doc (1).pdf" if "doc.pdf" already exists.
     */
    fun availableFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var counter = 1
        while (candidate.exists()) {
            candidate = if (ext.isEmpty()) {
                File(dir, "$base ($counter)")
            } else {
                File(dir, "$base ($counter).$ext")
            }
            counter++
        }
        return candidate
    }

    /**
     * Returns a unique subfolder under [parent]. If the generated name already
     * exists (timestamp collision), appends a millis suffix.
     */
    fun uniqueSubfolder(parent: File, base: String = "Nopalito_Scan_Export"): File {
        val dir = File(parent, folderName(base))
        return if (!dir.exists()) {
            dir
        } else {
            File(parent, "${base}-${System.currentTimeMillis()}")
        }
    }
}

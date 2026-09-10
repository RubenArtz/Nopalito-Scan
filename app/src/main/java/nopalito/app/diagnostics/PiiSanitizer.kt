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

package nopalito.app.diagnostics

/**
 * Strips personally identifiable and sensitive content from free-text messages
 * before they leave the device towards Crashlytics or Analytics.
 *
 * Anything reaching Firebase goes through here first: log messages built at
 * call sites frequently interpolate user data (`"Failed to import file: $uri"`,
 * `"Failed to set export dir to $uri"`), so the reporter must never trust the
 * raw text. Pure JVM logic, covered by unit tests.
 */
object PiiSanitizer {

    private val labeledSecret = Regex(
        """(?i)\b(token|bearer|password|passwd|pwd|secret|api[_-]?key)\s*[:=]\s*\S+"""
    )
    private val email = Regex(
        """[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""
    )
    private val contentUri = Regex(
        """(content|file)://\S+"""
    )
    private val url = Regex(
        """https?://\S+"""
    )
    private val absolutePath = Regex(
        """/(storage|data|sdcard|mnt|emulated)/\S*"""
    )
    private val documentFileName = Regex(
        """\S+\.(pdf|jpe?g|png|docx?|odt|rtf|txt|zip)\b""",
        RegexOption.IGNORE_CASE
    )
    private val jwtLike = Regex(
        """\b[A-Za-z0-9\-_]{20,}\.[A-Za-z0-9\-_]{10,}\S*"""
    )
    private val whitespace = Regex("""\s+""")

    /**
     * Returns a privacy-safe version of [value], truncated to [maxLength].
     * Never throws: on any failure returns a fixed placeholder.
     */
    fun sanitize(value: String, maxLength: Int = 200): String {
        return try {
            var out = value
            out = labeledSecret.replace(out, "$1=[redacted]")
            out = email.replace(out, "[email]")
            out = contentUri.replace(out, "[uri]")
            out = url.replace(out, "[url]")
            out = absolutePath.replace(out, "[file]")
            out = documentFileName.replace(out, "[file]")
            out = jwtLike.replace(out, "[token]")
            out = whitespace.replace(out.trim(), " ")
            if (out.length > maxLength) out.take(maxLength) else out
        } catch (_: Exception) {
            "[unavailable]"
        }
    }
}
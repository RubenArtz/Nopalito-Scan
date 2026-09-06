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

package nopalito.app.push

/**
 * Sanitizer for the maintenance banner's admin-authored free text
 * (`status.title` / `status.message` / `status.reason`). Delegates to the
 * shared [sanitizeUntrustedText] core; kept as a thin wrapper so the
 * maintenance contract keeps its stable name and behavior.
 */
fun sanitizeMaintenanceText(value: String?): String = sanitizeUntrustedText(value)

/**
 * Shared core for any untrusted server text that will be displayed on the
 * device. Pure, deterministic and total (never throws):
 *
 *  - strips HTML tags and `<script>`/`<style>` blocks (Compose/Android would
 *    otherwise render the markup literally);
 *  - removes control characters but keeps `\n`, `\r`, `\t` (the UI renders
 *    newlines);
 *  - removes bearer tokens and obvious credentials (`password=`/`secret=`…);
 *  - removes absolute system paths and URLs — unless
 *    [preserveLinksAndPaths] is set (authenticated push channel: links in an
 *    admin-authored message are legitimate content, and stripping them used
 *    to blank out whole titles/bodies);
 *  - removes Java/Kotlin stack-trace frames (`at pkg.Cls.m(file:line)`) and
 *    `Caused by:` lines;
 *  - removes obvious SQL statements (only when they end with `;`, so a sentence
 *    containing "select" is never destroyed);
 *  - caps the result at 200 characters.
 *
 * Golden rule: the sanitizer NEVER turns non-empty text into empty text.
 * When aggressive stripping would leave nothing behind, a minimal-clean
 * version of the original is returned instead (script blocks, tags, control
 * characters and credentials still removed — secrets are never preserved).
 * Only genuinely hostile-only input (a bare SQL statement or stack trace,
 * which minimal cleaning strips again) can legitimately yield "".
 *
 * Uses bounded patterns on purpose: ordinary text containing words like
 * "select" or "error" is preserved unless it clearly looks like a full SQL
 * statement or stack frame. Unicode, accents and normal prose are preserved.
 */
internal fun sanitizeUntrustedText(
    value: String?,
    preserveLinksAndPaths: Boolean = false,
): String {
    if (value == null) return ""
    var s = value
    s = removeControlCharacters(s)
    s = stripScriptBlocks(s)
    s = stripHtmlTags(s)
    if (!preserveLinksAndPaths) {
        s = removeUrls(s)
        s = removeAbsolutePaths(s)
    }
    s = removeCredentials(s)
    s = removeStackTraces(s)
    s = removeSqlStatements(s)
    s = collapseWhitespaceKeepNewlines(s)
    s = s.take(200)

    // Golden rule: never turn non-empty into empty. Fall back to a minimal
    // clean-up that preserves legitimate content (links included) while
    // still removing scripts, markup, control characters and credentials.
    if (s.isBlank() && value.isNotBlank()) {
        return minimallyCleanText(value)
    }
    return s
}

/**
 * Last-resort cleanup used by the golden rule above: keeps everything except
 * script blocks, HTML tags, control characters, credentials, stack frames and
 * SQL statements. If even this yields blank (the input was hostile-only),
 * returns "" — there was no legitimate content to preserve.
 */
private fun minimallyCleanText(value: String): String {
    var s = value
    s = removeControlCharacters(s)
    s = stripScriptBlocks(s)
    s = stripHtmlTags(s)
    s = removeCredentials(s)
    s = removeStackTraces(s)
    s = removeSqlStatements(s)
    s = collapseWhitespaceKeepNewlines(s)
    return s.take(200)
}

/**
 * Localization pipeline for one maintenance field:
 *
 * `raw → sanitize → translate → sanitize final → string`
 *
 * The text is sanitized BEFORE translation (so a URL/token is never sent to the
 * translator) and the translator output is sanitized AGAIN before display. When
 * translation fails (or returns the original text), the result is still the
 * sanitized text, never the raw original.
 */
suspend fun localizeMaintenanceText(value: String?, translate: suspend (String) -> String): String {
    val safe = sanitizeMaintenanceText(value)
    if (safe.isEmpty()) return ""
    return sanitizeMaintenanceText(translate(safe))
}

/**
 * Render-time fallback. [localized] is expected to already be sanitized; when it
 * is blank we fall back to the sanitized raw field — never to the raw original.
 */
fun renderMaintenanceText(localized: String, raw: String?): String {
    val safeLocal = sanitizeMaintenanceText(localized)
    return if (safeLocal.isNotBlank()) safeLocal else sanitizeMaintenanceText(raw)
}

private fun removeControlCharacters(v: String): String {
    val sb = StringBuilder(v.length)
    for (ch in v) {
        if (ch == '\n' || ch == '\r' || ch == '\t') {
            sb.append(ch)
        } else if (!Character.isISOControl(ch)) {
            sb.append(ch)
        }
    }
    return sb.toString()
}

private fun stripScriptBlocks(v: String): String =
    v.replace(Regex("""(?is)<(script|style)\b[^>]*>.*?</\1>"""), " ")

private fun stripHtmlTags(v: String): String =
    v.replace(Regex("""</?[a-zA-Z][a-zA-Z0-9]*[^>]*>"""), " ")

private fun removeUrls(v: String): String =
    v.replace(Regex("""\b(?:https?|ftp)://[^\s]+"""), " ")
        .replace(Regex("""\bwww\.\S+"""), " ")

private fun removeCredentials(v: String): String =
    v.replace(Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+"""), " ")
        .replace(Regex("""(?i)\b(password|passwd|secret|apikey|api[_-]?key)\b\s*[:=]\s*\S+"""), " ")

private fun removeAbsolutePaths(v: String): String =
    v.replace(Regex("""[A-Za-z]:[\\/][^\s\n]*"""), " ")
        .replace(
            Regex("""(?:^|[\s(])/(?:usr|home|etc|var|opt|tmp|root|srv|app|proc|dev)[^\s\n]*"""),
            " "
        )

private fun removeStackTraces(v: String): String =
    v.replace(Regex("""(?m)^[ \t]*at\s+[A-Za-z_$][\w.$]*(?:<[^>]+>)?\([^)]*:[0-9]+\)"""), "")
        .replace(Regex("""(?m)^[ \t]*Caused by:.*"""), "")

private fun removeSqlStatements(v: String): String =
    v.replace(
        Regex("""(?i)\b(?:select|insert\s+into|update|delete\s+from|drop\s+table|alter\s+table|create\s+table|truncate\s+table)\b[^;]*;"""),
        " "
    )

private fun collapseWhitespaceKeepNewlines(v: String): String {
    var t = v.replace("\r\n", "\n").replace('\r', '\n')
    t = t.replace(Regex("""[ \t]+"""), " ")
    t = t.replace(Regex("""\n{3,}"""), "\n\n")
    return t.trim('\n', ' ', '\t')
}
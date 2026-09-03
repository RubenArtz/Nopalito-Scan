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

package nopalito.app.ui.screens.tools.extract

/**
 * Result of parsing a page-range expression.
 *
 * - [Empty]: no input typed yet.
 * - [Valid]: [pages] is the deduplicated, ascending, 1-based page list
 *   validated against the document total ([totalPages] is baked into the
 *   validation at parse time). [warnings] reports `a/b` expressions whose
 *   stated total does not match the real document.
 * - [Invalid]: a structured [PageRangeError] the UI maps to a localized
 *   message (this parser is Android-free so it stays unit-testable).
 */
sealed interface PageRangeResult {
    data object Empty : PageRangeResult
    data class Valid(
        val pages: List<Int>,
        val warnings: List<PageRangeWarning>,
    ) : PageRangeResult

    data class Invalid(val error: PageRangeError) : PageRangeResult
}

data class PageRangeError(
    val kind: PageRangeErrorKind,
    /** The offending token, for syntax errors. */
    val token: String = "",
    /** First offending number, for NOT_POSITIVE / DESCENDING / OUT_OF_BOUNDS. */
    val a: Int = 0,
    /** Second offending number, for DESCENDING ranges. */
    val b: Int = 0,
    /** Real page count of the document, for OUT_OF_BOUNDS. */
    val totalPages: Int = 0,
)

enum class PageRangeErrorKind {
    /** Token did not match `n`, `n-m` or `n/m`. */
    SYNTAX,

    /** A page number is not positive (0 or negative). */
    NOT_POSITIVE,

    /** A range is written descending, e.g. `5-1`. */
    DESCENDING,

    /** A selected page does not exist in the document. */
    OUT_OF_BOUNDS,
}

/** An `a/b` expression whose stated total differs from the real document. */
data class PageRangeWarning(val statedTotal: Int, val actualTotal: Int)

/**
 * Parses page-range expressions into a flat, sorted, deduplicated page list.
 *
 * Accepted formats (tokens separated by commas or whitespace):
 * - `1,2`      → pages 1 and 2.
 * - `1-5`      → pages 1 to 5 (inclusive).
 * - `10/50`    → page 10 of a document assumed to have 50 pages; validated
 *                against the real total (a mismatch becomes a [PageRangeWarning],
 *                an out-of-range page becomes an [PageRangeError]).
 * - `10/50, 20-25`, `1, 3, 5-8` → combinations of the above.
 *
 * Rejects `0`/negatives, descending ranges (`5-1`) and pages beyond the real
 * total. Duplicates are removed and the result is sorted ascending.
 */
object PageRangeParser {

    private val SINGLE = Regex("""\d+""")
    private val NEGATIVE = Regex("""-\d+""")
    private val RANGE = Regex("""(\d+)\s*-\s*(\d+)""")
    private val EXPLICIT = Regex("""(\d+)\s*/\s*(\d+)""")

    fun parse(input: String, totalPages: Int): PageRangeResult {
        val tokens = input.trim().split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return PageRangeResult.Empty

        val pages = sortedSetOf<Int>()
        val warnings = mutableListOf<PageRangeWarning>()

        for (token in tokens) {
            val explicit = EXPLICIT.matchEntire(token)
            if (explicit != null) {
                val a = explicit.groupValues[1].toInt()
                val b = explicit.groupValues[2].toInt()
                if (a < 1) return invalid(PageRangeErrorKind.NOT_POSITIVE, token, a = a)
                if (a > b) return invalid(PageRangeErrorKind.DESCENDING, token, a = a, b = b)
                if (b != totalPages) warnings += PageRangeWarning(
                    statedTotal = b,
                    actualTotal = totalPages
                )
                if (a > totalPages) return invalid(
                    PageRangeErrorKind.OUT_OF_BOUNDS,
                    token,
                    a = a,
                    totalPages = totalPages
                )
                pages += a
                continue
            }

            val range = RANGE.matchEntire(token)
            if (range != null) {
                val start = range.groupValues[1].toInt()
                val end = range.groupValues[2].toInt()
                if (start < 1) return invalid(PageRangeErrorKind.NOT_POSITIVE, token, a = start)
                if (start > end) return invalid(
                    PageRangeErrorKind.DESCENDING,
                    token,
                    a = start,
                    b = end
                )
                pages += start..end
                continue
            }

            if (NEGATIVE.matchEntire(token) != null) {
                return invalid(PageRangeErrorKind.NOT_POSITIVE, token, a = token.toInt())
            }

            if (SINGLE.matchEntire(token) != null) {
                val n = token.toInt()
                if (n < 1) return invalid(PageRangeErrorKind.NOT_POSITIVE, token, a = n)
                pages += n
                continue
            }

            return invalid(PageRangeErrorKind.SYNTAX, token)
        }

        val outOfRange = pages.firstOrNull { it > totalPages }
        if (outOfRange != null) {
            return invalid(
                PageRangeErrorKind.OUT_OF_BOUNDS,
                token = "",
                a = outOfRange,
                totalPages = totalPages,
            )
        }
        return PageRangeResult.Valid(pages.toList(), warnings)
    }

    private fun invalid(
        kind: PageRangeErrorKind,
        token: String,
        a: Int = 0,
        b: Int = 0,
        totalPages: Int = 0,
    ): PageRangeResult.Invalid = PageRangeResult.Invalid(
        PageRangeError(kind, token, a, b, totalPages),
    )
}
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangeParserTest {

    private fun valid(input: String, total: Int): List<Int> {
        val result = PageRangeParser.parse(input, total)
        assertTrue("expected valid, got $result", result is PageRangeResult.Valid)
        return (result as PageRangeResult.Valid).pages
    }

    private fun invalidKind(input: String, total: Int): PageRangeErrorKind {
        val result = PageRangeParser.parse(input, total)
        assertTrue("expected invalid, got $result", result is PageRangeResult.Invalid)
        return (result as PageRangeResult.Invalid).error.kind
    }

    @Test
    fun `single pages are returned sorted`() {
        assertEquals(listOf(1, 2), valid("1,2", 10))
        assertEquals(listOf(1, 2, 10), valid("10, 2, 1", 10))
    }

    @Test
    fun `range is expanded inclusively`() {
        assertEquals(listOf(1, 2, 3, 4, 5), valid("1-5", 10))
    }

    @Test
    fun `explicit page over total`() {
        assertEquals(listOf(10), valid("10/50", 50))
    }

    @Test
    fun `explicit page over mismatched total keeps page and warns`() {
        val result = PageRangeParser.parse("10/50", 40)
        assertTrue(result is PageRangeResult.Valid)
        result as PageRangeResult.Valid
        assertEquals(listOf(10), result.pages)
        assertEquals(1, result.warnings.size)
        assertEquals(50, result.warnings[0].statedTotal)
        assertEquals(40, result.warnings[0].actualTotal)
    }

    @Test
    fun `explicit page beyond real total is out of bounds`() {
        assertEquals(PageRangeErrorKind.OUT_OF_BOUNDS, invalidKind("10/50", 5))
        assertEquals(PageRangeErrorKind.OUT_OF_BOUNDS, invalidKind("10/50", 4))
    }

    @Test
    fun `explicit page larger than its own stated total is rejected`() {
        assertEquals(PageRangeErrorKind.DESCENDING, invalidKind("10/5", 50))
    }

    @Test
    fun `combined tokens work`() {
        assertEquals(listOf(2, 4, 5, 6, 10), valid("2,4-6,10", 10))
        assertEquals(listOf(10, 20, 21, 22, 23, 24, 25), valid("10/50, 20-25", 50))
        assertEquals(listOf(1, 3, 5, 6, 7, 8), valid("1, 3, 5-8", 10))
    }

    @Test
    fun `duplicates are removed`() {
        assertEquals(listOf(1), valid("1,1,1", 10))
        assertEquals(listOf(2, 3), valid("2,2-3,3", 10))
    }

    @Test
    fun `descending ranges are rejected`() {
        assertEquals(PageRangeErrorKind.DESCENDING, invalidKind("5-1", 10))
        assertEquals(PageRangeErrorKind.DESCENDING, invalidKind("10-5", 10))
    }

    @Test
    fun `zero and negatives are rejected`() {
        assertEquals(PageRangeErrorKind.NOT_POSITIVE, invalidKind("0", 10))
        assertEquals(PageRangeErrorKind.DESCENDING, invalidKind("1-0", 10))
        assertEquals(PageRangeErrorKind.NOT_POSITIVE, invalidKind("-3", 10))
    }

    @Test
    fun `pages beyond the document total are rejected`() {
        assertEquals(PageRangeErrorKind.OUT_OF_BOUNDS, invalidKind("7", 5))
        assertEquals(PageRangeErrorKind.OUT_OF_BOUNDS, invalidKind("4-8", 5))
    }

    @Test
    fun `syntax errors are rejected`() {
        assertEquals(PageRangeErrorKind.SYNTAX, invalidKind("abc", 10))
        assertEquals(PageRangeErrorKind.SYNTAX, invalidKind("1,x", 10))
        assertEquals(PageRangeErrorKind.SYNTAX, invalidKind("1--5", 10))
        assertEquals(PageRangeErrorKind.SYNTAX, invalidKind("1.5", 10))
    }

    @Test
    fun `empty input produces empty result`() {
        assertTrue(PageRangeParser.parse("", 10) is PageRangeResult.Empty)
        assertTrue(PageRangeParser.parse("   ", 10) is PageRangeResult.Empty)
    }

    @Test
    fun `whitespace can separate tokens`() {
        assertEquals(listOf(1, 2, 3), valid("1 2 3", 10))
    }
}
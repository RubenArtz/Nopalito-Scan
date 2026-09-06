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

import nopalito.imageprocessing.ColorMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the "Reordenar" flow.
 *
 * Rule under test: the ID identifies the page, the position only tells where
 * it is shown. Reordering must move whole [PageV2] objects (all per-page
 * fields follow the id) and the visible counters are always index + 1,
 * never a stored identity.
 */
class PageStoreTest {

    private fun page(
        id: String,
        colorMode: ColorMode? = null,
        manualRotationDegrees: Int = 0,
        quadVersion: Int = 0,
    ) = PageV2(
        id = id,
        manualRotationDegrees = manualRotationDegrees,
        colorMode = colorMode,
        quadVersion = quadVersion,
    )

    private fun sixPages() = PageStore(
        listOf(
            page("a", ColorMode.COLOR, 0, 0),
            page("b", ColorMode.GRAYSCALE, 90, 1),
            page("c", ColorMode.LIGHTEN, 180, 2),
            page("d", ColorMode.BW, 270, 0),
            page("e", ColorMode.ORIGINAL, 0, 3),
            page("f", null, 0, 0),
        )
    )

    private fun ids(store: PageStore) = store.pages().map { it.id }

    /** Visible counters are always derived from the visual position. */
    private fun counters(store: PageStore) =
        store.pages().mapIndexed { index, _ -> index + 1 }

    @Test
    fun `move first page to second position keeps every field attached to its id`() {
        val store = sixPages()

        store.move("a", 1)

        assertEquals(listOf("b", "a", "c", "d", "e", "f"), ids(store))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), counters(store))
        // The moved page keeps its exact image/state descriptor…
        assertEquals(page("a", ColorMode.COLOR, 0, 0), store.get("a"))
        // …and so does the page that was already in second position.
        assertEquals(page("b", ColorMode.GRAYSCALE, 90, 1), store.get("b"))
    }

    @Test
    fun `move is reversible without touching page fields`() {
        val store = sixPages()
        val before = store.pages()

        store.move("a", 1)
        store.move("a", 0)

        assertEquals(before, store.pages())
        assertEquals(listOf(1, 2, 3, 4, 5, 6), counters(store))
    }

    @Test
    fun `move last page to first position`() {
        val store = sixPages()

        store.move("f", 0)

        assertEquals(listOf("f", "a", "b", "c", "d", "e"), ids(store))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), counters(store))
    }

    @Test
    fun `move with out of bounds index is coerced`() {
        val store = sixPages()

        store.move("a", 99)

        assertEquals(listOf("b", "c", "d", "e", "f", "a"), ids(store))
    }

    @Test
    fun `move unknown id is a no-op`() {
        val store = sixPages()
        val before = store.pages()

        store.move("unknown", 0)

        assertEquals(before, store.pages())
    }

    @Test
    fun `setOrder applies a full reorder moving whole objects`() {
        val store = sixPages()

        store.setOrder(listOf("b", "a", "c", "d", "e", "f"))

        assertEquals(listOf("b", "a", "c", "d", "e", "f"), ids(store))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), counters(store))
        assertEquals(page("a", ColorMode.COLOR, 0, 0), store.get("a"))
        assertEquals(page("b", ColorMode.GRAYSCALE, 90, 1), store.get("b"))
    }

    @Test
    fun `setOrder ignores unknown ids and never drops pages`() {
        val store = sixPages()

        store.setOrder(listOf("f", "unknown", "a"))

        val result = ids(store)
        assertEquals(listOf("f", "a"), result.take(2))
        // Every existing page survives exactly once; nothing is duplicated.
        assertEquals(listOf("a", "b", "c", "d", "e", "f").sorted(), result.sorted())
        assertEquals(6, result.distinct().size)
    }

    @Test
    fun `setOrder is idempotent`() {
        val store = sixPages()
        val target = listOf("c", "a", "f", "b", "e", "d")

        store.setOrder(target)
        store.setOrder(target)

        assertEquals(target, ids(store))
    }
}
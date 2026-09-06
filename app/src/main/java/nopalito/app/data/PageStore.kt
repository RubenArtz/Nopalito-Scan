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

class PageStore(pages: List<PageV2>) {

    private val pages = LinkedHashMap<String, PageV2>()
        .also { map -> pages.forEach { map[it.id] = it } }

    fun pages(): List<PageV2> =
        pages.values.toList()

    fun get(id: String): PageV2? =
        pages[id]

    fun addOrReplace(page: PageV2) =
        pages.put(page.id, page)

    fun update(id: String, transform: (PageV2) -> PageV2) {
        val page = pages[id] ?: return
        pages[id] = transform(page)
    }

    fun delete(id: String) = pages.remove(id)

    fun clear() = pages.clear()

    fun move(id: String, newIndex: Int) {
        val page = pages[id] ?: return

        val entries = pages.entries.toList()
            .filterNot { it.key == id }

        pages.clear()

        val safeIndex = newIndex.coerceIn(0, entries.size)
        entries.take(safeIndex).forEach { pages[it.key] = it.value }
        pages[id] = page
        entries.drop(safeIndex).forEach { pages[it.key] = it.value }
    }

    /**
     * Reorders the store to match [ids] exactly: whole [PageV2] objects are
     * moved, never copied or reprocessed, so every per-page field (rotation,
     * color mode, quad, crop, source size, …) stays attached to its stable id.
     *
     * The visual position of a page is always its index in this order + 1;
     * no counter is ever stored as identity. Unknown ids are ignored and
     * pages missing from [ids] (e.g. added concurrently) keep their relative
     * order appended at the end, so this operation can never lose or
     * resurrect a page.
     */
    fun setOrder(ids: List<String>) {
        if (ids.isEmpty()) return
        val current = pages.toMap()
        val seen = LinkedHashSet<String>()
        val reordered = LinkedHashMap<String, PageV2>()
        ids.forEach { id ->
            current[id]?.let { page ->
                if (seen.add(id)) reordered[id] = page
            }
        }
        current.forEach { (id, page) ->
            if (seen.add(id)) reordered[id] = page
        }
        pages.clear()
        pages.putAll(reordered)
    }
}
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

package nopalito.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Process-wide, memory-bounded cache for list thumbnails.
 *
 * History screens can hold thousands of entries. Keeping every decoded bitmap
 * alive in a per-screen map (the previous behaviour) made memory grow with each
 * row scrolled, so the app became slower the further the user went. This cache
 * is measured in bytes and evicts the least recently used thumbnails instead.
 */
object ThumbnailCache {

    private const val MIN_BYTES = 4L * 1024 * 1024
    private const val MAX_BYTES = 24L * 1024 * 1024

    private val maxBytes: Int = (Runtime.getRuntime().maxMemory() / 16L)
        .coerceIn(MIN_BYTES, MAX_BYTES)
        .toInt()

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

/**
 * Decodes at most two thumbnails at a time: a fast fling composes many rows and
 * must not flood the IO pool with decodes that keep the CPU busy after the
 * scroll stops.
 */
private val thumbnailDispatcher = Dispatchers.IO.limitedParallelism(2)

/**
 * Returns the cached thumbnail for [key], decoding it with [load] on a bounded
 * IO dispatcher when missing. The bitmap is cached process-wide, so scrolling
 * back to a row does not decode it again.
 */
@Composable
fun rememberThumbnail(key: String?, load: suspend () -> Bitmap?): Bitmap? {
    var thumb by remember(key) { mutableStateOf(key?.let(ThumbnailCache::get)) }
    LaunchedEffect(key) {
        if (key == null || thumb != null) return@LaunchedEffect
        val loaded = withContext(thumbnailDispatcher) {
            runCatching { load() }.getOrNull()
        }
        if (loaded != null) {
            ThumbnailCache.put(key, loaded)
            thumb = loaded
        }
    }
    return thumb
}

/**
 * Decodes [path] downsampled so its longest side is at most [maxSizePx].
 * History cards are 64dp; decoding the original 10+ MP scan for each of them
 * was the main cause of the scroll jank.
 */
fun decodeThumbnail(path: String, maxSizePx: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val longest = max(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return@runCatching null
    BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize(longest, maxSizePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    )
}.getOrNull()

/** Decodes a thumbnail from a `content://` or `file://` [uri]. */
fun decodeThumbnail(context: Context, uri: Uri, maxSizePx: Int): Bitmap? = runCatching {
    if (uri.scheme == "file") {
        return@runCatching uri.path?.let { decodeThumbnail(it, maxSizePx) }
    }
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longest = max(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return@runCatching null
    resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(
            stream,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(longest, maxSizePx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }
}.getOrNull()

private fun sampleSize(longestSide: Int, maxSizePx: Int): Int {
    if (maxSizePx <= 0) return 1
    var sample = 1
    while (longestSide / sample > maxSizePx) sample *= 2
    return sample
}
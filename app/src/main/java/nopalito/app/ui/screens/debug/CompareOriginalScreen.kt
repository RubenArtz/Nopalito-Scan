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

package nopalito.app.ui.screens.debug

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import nopalito.app.R

/**
 * Debug-only Original vs Processed comparator (Phase 1).
 *
 * - Never modifies images.
 * - Handles different resolutions (each side reports its own WxH).
 * - Shows which side is visible.
 * - Loads only the visible side at display size (sampled) and recycles on
 *   switch/unmount so two full-res bitmaps are never held together.
 * - Gated by [enabled]: callers render it only when debugOverlay is true.
 */
@Composable
fun CompareOriginalScreen(
    pageId: String,
    originalBytes: suspend () -> ByteArray?,
    processedBytes: suspend () -> ByteArray?,
    infoLine: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    var showOriginal by remember { mutableStateOf(true) }
    var bitmap by remember(pageId, showOriginal) { mutableStateOf<Bitmap?>(null) }
    var dims by remember(pageId, showOriginal) { mutableStateOf<String>("") }
    val zoom = rememberZoomState()

    androidx.compose.runtime.LaunchedEffect(pageId, showOriginal) {
        bitmap?.recycle()
        bitmap = null
        val bytes = withContext(Dispatchers.IO) {
            if (showOriginal) originalBytes() else processedBytes()
        } ?: return@LaunchedEffect
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        dims = "${bounds.outWidth}x${bounds.outHeight}"
        // Sample to ~2048 long side for display; full bytes stay on disk.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
    DisposableEffect(pageId) {
        onDispose { runCatching { bitmap?.recycle() } }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = showOriginal,
                onClick = { showOriginal = true },
                label = { Text(stringResource(R.string.compare_original)) },
            )
            FilterChip(
                selected = !showOriginal,
                onClick = { showOriginal = false },
                label = { Text(stringResource(R.string.compare_processed)) },
            )
        }
        Text(
            text = infoLine + " · $dims",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = if (showOriginal) "original" else "processed",
                modifier = Modifier
                    .fillMaxSize()
                    .zoomable(zoom),
            )
        }
    }
}

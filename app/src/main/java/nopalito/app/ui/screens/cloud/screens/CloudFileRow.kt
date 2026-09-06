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

package nopalito.app.ui.screens.cloud.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nopalito.app.R
import nopalito.app.ui.components.FileTypeBadge
import nopalito.app.ui.decodeDocxMedia
import nopalito.app.ui.screens.cloud.model.CloudFile
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.material.icons.filled.Image as ImageIcon

/**
 * Process-wide thumbnail cache: the same file scrolls in/out many times, so
 * decoded thumbnails survive recomposition (keyed by id + version). Shared by
 * the cloud file list and the camera import browser.
 */
private val thumbnailCache = LruCache<String, Bitmap>(96)

/** Budget for one thumbnail decode (download + render). */
private const val THUMB_DECODE_TIMEOUT_MS = 15_000L

/**
 * Shared cloud file row: 44dp thumbnail (real image/PDF/DOCX preview with
 * stable fallback), [FileTypeBadge] overlay, name plus type/size line.
 * Used by the cloud file list and the camera import browser with identical
 * appearance; only the interactions differ and are injected via slots:
 *
 * @param onClick row tap.
 * @param onLongClick when non-null, the row uses combinedClickable.
 * @param leadingContent e.g. selection checkbox (file list) or nothing.
 * @param trailingContent e.g. overflow menu (file list) or nothing.
 * @param footerContent e.g. download/import progress bar.
 */
@Composable
internal fun CloudFileRow(
    file: CloudFile,
    downloadForCache: suspend (CloudFile) -> Result<File>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    footerContent: (@Composable () -> Unit)? = null,
) {
    // Thumbnail bitmap (memory-cached across scrolls and recompositions).
    val cacheKey = remember(file.id, file.updatedAt, file.createdAt) {
        "${file.id}|${file.updatedAt ?: file.createdAt}"
    }
    var thumb by remember { mutableStateOf(thumbnailCache.get(cacheKey)) }
    var loadingThumb by remember { mutableStateOf(thumb == null) }

    val isDocx = file.originalName.endsWith(".docx") || file.originalName.endsWith(".doc")
    val canThumb = file.mimeType?.startsWith("image/") == true ||
            file.mimeType == "application/pdf" ||
            file.originalName.endsWith(".pdf") ||
            isDocx

    LaunchedEffect(file.id) {
        if (!canThumb) {
            loadingThumb = false
            return@LaunchedEffect
        }
        thumbnailCache.get(cacheKey)?.let { cached ->
            thumb = cached
            loadingThumb = false
            return@LaunchedEffect
        }
        loadingThumb = true
        try {
            val decoded = withContext(Dispatchers.IO) {
                // Bounded budget: a stuck download or render falls back to the
                // type icon instead of spinning forever.
                withTimeout(THUMB_DECODE_TIMEOUT_MS.milliseconds) {
                    decodeThumb(
                        file,
                        isDocx,
                        downloadForCache
                    )
                }
            }
            if (decoded != null) {
                thumbnailCache.put(cacheKey, decoded)
                thumb = decoded
            }
        } catch (e: TimeoutCancellationException) {
            // Budget exhausted: stable fallback icon below, never endless spin.
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Decode failure: stable fallback icon below.
        } finally {
            loadingThumb = false
        }
    }

    // Fallback icon
    val fallbackIcon: ImageVector = when {
        file.originalName.endsWith(".pdf") -> Icons.Default.PictureAsPdf
        file.originalName.endsWith(".png") || file.originalName.endsWith(".jpg") ||
                file.originalName.endsWith(".jpeg") || file.originalName.endsWith(".webp") ||
                file.originalName.endsWith(".bmp") -> Icons.Default.ImageIcon

        file.originalName.endsWith(".doc") || file.originalName.endsWith(".docx") ->
            Icons.Default.Description

        file.originalName.endsWith(".xls") || file.originalName.endsWith(".xlsx") ->
            Icons.Default.TableChart

        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(clickModifier)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surface
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingContent?.let { it() }

            // Thumbnail
            Box(
                Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        loadingThumb -> CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )

                        thumb != null -> Image(
                            bitmap = thumb!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )

                        else -> Icon(
                            fallbackIcon, null, Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                FileTypeBadge(
                    fileName = file.originalName,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

            Spacer(Modifier.width(12.dp))

            // File info
            Column(Modifier.weight(1f)) {
                Text(
                    file.originalName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.mimeType?.substringBefore("/")?.replaceFirstChar { it.uppercase() }
                            ?: file.originalName.substringAfterLast('.', "").uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (file.size != null) {
                        Text(
                            " · ${
                                formatCloudFileSize(
                                    file.size,
                                    stringResource(R.string.cloud_size_unknown)
                                )
                            }",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            trailingContent?.let { it() }
        }

        footerContent?.let { it() }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        )
    }
}

/** Decodes a small thumbnail for [file] (image/pdf/docx) from the local cache. */
private suspend fun decodeThumb(
    file: CloudFile,
    isDocx: Boolean,
    downloadForCache: suspend (CloudFile) -> Result<File>,
): Bitmap? = try {
    val cached = downloadForCache(file).getOrNull() ?: return null
    when {
        file.mimeType?.startsWith("image/") == true -> {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(cached.absolutePath, opts)
        }

        file.mimeType == "application/pdf" || file.originalName.endsWith(".pdf") -> {
            val pfd = ParcelFileDescriptor.open(
                cached,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val w = 120
                val h = (page.height.toFloat() / page.width.toFloat() * w).toInt()
                val bmp = createBitmap(w, h)
                bmp.eraseColor(android.graphics.Color.WHITE)
                val mtx = android.graphics.Matrix().apply {
                    postScale(
                        w.toFloat() / page.width,
                        h.toFloat() / page.height
                    )
                }
                page.render(bmp, null, mtx, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close(); renderer.close(); pfd.close()
                bmp
            } else {
                renderer.close(); pfd.close(); null
            }
        }

        isDocx -> decodeDocxMedia(cached, maxDim = 256).firstOrNull()

        else -> null
    }
} catch (_: Exception) {
    null
}

/** Breadcrumb path of the open folder with an optional "new folder" shortcut. */
@Composable
internal fun FolderBreadcrumbs(
    path: List<CloudFile>,
    onNavigate: (Int) -> Unit,
    onNewFolder: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BreadcrumbCrumb(
            label = stringResource(R.string.cloud_root_crumb),
            highlighted = path.isEmpty(),
            onClick = { onNavigate(-1) }
        )
        path.forEachIndexed { index, folder ->
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            BreadcrumbCrumb(
                label = folder.originalName,
                highlighted = index == path.lastIndex,
                onClick = {
                    if (index != path.lastIndex) onNavigate(index)
                }
            )
        }
        if (onNewFolder != null) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onNewFolder) {
                Icon(
                    Icons.Default.CreateNewFolder,
                    contentDescription = stringResource(R.string.cloud_new_folder),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
internal fun BreadcrumbCrumb(
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
        color = if (highlighted) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
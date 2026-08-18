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
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nopalito.app.R
import nopalito.app.ui.components.GradientHeroAction
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.QrScan
import nopalito.app.ui.screens.cloud.viewmodel.CloudQrHistoryViewModel
import nopalito.app.ui.screens.qr.regenerateAndSaveQr
import nopalito.app.ui.screens.qr.saveScanImageToDownloads
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CloudQrHistoryScreen(
    viewModel: CloudQrHistoryViewModel,
    onBack: () -> Unit,
    onNavigateToTrash: () -> Unit = {},
) {
    val context = LocalContext.current
    val repository = remember { CloudRepository(context) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteItem by remember { mutableStateOf<QrScan?>(null) }
    var exportItem by remember { mutableStateOf<QrScan?>(null) }
    var downloadingFormat by remember { mutableStateOf<String?>(null) }
    var batchTrashConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val inSelection = state.selectedIds.isNotEmpty()
    val allSelected = state.scans.isNotEmpty() && state.scans.all { it.id in state.selectedIds }

    Column(modifier = Modifier.fillMaxSize()) {
        if (inSelection) {
            GradientHeroHeader(
                title = stringResource(R.string.cloud_n_selected, state.selectedIds.size),
                onBack = viewModel::clearSelection,
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GradientHeroAction(
                            icon = Icons.Default.SelectAll,
                            contentDescription = stringResource(R.string.cloud_select_all),
                            onClick = { if (allSelected) viewModel.clearSelection() else viewModel.selectAll() },
                        )
                    }
                },
            )
        } else {
            GradientHeroHeader(
                title = stringResource(R.string.qr_cloud_history),
                subtitle = stringResource(R.string.qr_cloud_history_subtitle),
                onBack = onBack,
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GradientHeroAction(
                            icon = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.cloud_trash),
                            onClick = onNavigateToTrash,
                        )
                        GradientHeroAction(
                            icon = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                            onClick = { viewModel.refresh() },
                        )
                    }
                },
            )
        }
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.isLoading && state.scans.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null && state.scans.isEmpty() -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error!!, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = viewModel::refresh) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }

                state.scans.isEmpty() -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.QrCode,
                                null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.qr_cloud_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 16.dp,
                            end = 16.dp,
                            bottom = if (inSelection) 88.dp else 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.scans, key = { it.id }) { scan ->
                            CloudScanCard(
                                scan = scan,
                                thumbnailLoader = { repository.getScanThumbnail(scan.imageFileId!!) },
                                selected = scan.id in state.selectedIds,
                                selectionMode = inSelection,
                                onClick = {
                                    if (inSelection) viewModel.toggleSelection(scan.id)
                                    else exportItem = scan
                                },
                                onLongClick = {
                                    if (!inSelection) viewModel.select(scan.id)
                                },
                                onDelete = { deleteItem = scan },
                            )
                        }
                    }
                }
            }
            if (inSelection) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Button(
                            onClick = { batchTrashConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.qr_move_trash_n, state.selectedIds.size))
                        }
                    }
                }
            }
        }
    }

    deleteItem?.let { scan ->
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text(stringResource(R.string.qr_delete_title)) },
            text = { Text(stringResource(R.string.qr_cloud_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(scan.id)
                    deleteItem = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteItem = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (batchTrashConfirm) {
        AlertDialog(
            onDismissRequest = { batchTrashConfirm = false },
            title = { Text(stringResource(R.string.qr_move_trash)) },
            text = { Text(stringResource(R.string.qr_move_trash_confirm, state.selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    batchTrashConfirm = false
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { batchTrashConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    exportItem?.let { scan ->
        CloudQrExportDialog(
            scan = scan,
            downloadingFormat = downloadingFormat,
            onDownload = { fmt ->
                if (downloadingFormat != null) return@CloudQrExportDialog
                downloadingFormat = fmt
                scope.launch(Dispatchers.IO) {
                    val ok = if (scan.design != null) {
                        regenerateAndSaveQr(context, repository, scan.design, fmt)
                    } else {
                        val image = scan.imageFileId?.let { id ->
                            runCatching { repository.getScanThumbnail(id).getOrNull() }.getOrNull()
                        }
                        saveScanImageToDownloads(context, image)
                    }
                    withContext(Dispatchers.Main) {
                        downloadingFormat = null
                        Toast.makeText(
                            context,
                            if (ok) R.string.qr_saved else R.string.qr_save_error,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismiss = { exportItem = null },
        )
    }
}

@Composable
private fun CloudScanCard(
    scan: QrScan,
    thumbnailLoader: suspend () -> Result<File>,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumbnail by produceState<Bitmap?>(null, scan.id) {
        value = runCatching { thumbnailLoader().getOrNull() }
            .getOrNull()
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
    val date = remember(scan.scannedAt) { formatDate(scan.scannedAt) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = scan.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(scan.format, typeLabel(scan.type), date).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
    }
}

@Composable
private fun typeLabel(type: String?): String? = when (type?.lowercase()) {
    "text" -> stringResource(R.string.qr_type_text)
    "url" -> stringResource(R.string.qr_type_url)
    "wifi" -> stringResource(R.string.qr_type_wifi)
    "email" -> stringResource(R.string.qr_type_email)
    "phone" -> stringResource(R.string.qr_type_phone)
    "sms" -> stringResource(R.string.qr_type_sms)
    "geo" -> stringResource(R.string.qr_type_geo)
    else -> null
}

private fun formatDate(iso: String?): String {
    if (iso == null) return ""
    return try {
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso)
            ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).parse(iso)
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(parsed ?: Date())
    } catch (_: Exception) {
        iso
    }
}

/** Detail dialog for a generated QR stored in the cloud: preview + PNG/SVG/PDF export. */
@Composable
private fun CloudQrExportDialog(
    scan: QrScan,
    downloadingFormat: String?,
    onDownload: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { CloudRepository(context) }
    val preview by produceState<Bitmap?>(null, scan.id) {
        value = scan.imageFileId
            ?.let { runCatching { repository.getScanThumbnail(it).getOrNull() }.getOrNull() }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_redownload_title)) },
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                preview?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = scan.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.qr_redownload_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val formats = if (scan.design != null) listOf("png", "svg", "pdf") else listOf("png")
                    formats.forEach { fmt ->
                        Button(
                            onClick = { onDownload(fmt) },
                            enabled = downloadingFormat == null || downloadingFormat == fmt,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (downloadingFormat == fmt) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(fmt.uppercase())
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.qr_close)) }
        },
    )
}

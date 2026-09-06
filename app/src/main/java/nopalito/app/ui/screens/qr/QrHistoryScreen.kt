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

package nopalito.app.ui.screens.qr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nopalito.app.R
import nopalito.app.ui.components.GradientHeroAction
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.screens.cloud.data.CloudRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun QrHistoryScreen(
    viewModel: QrScannerViewModel,
    onBack: () -> Unit,
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var deleteItem by remember { mutableStateOf<QrScanEntity?>(null) }
    var detailItem by remember { mutableStateOf<QrScanEntity?>(null) }
    var redownloadItem by remember { mutableStateOf<QrScanEntity?>(null) }
    var downloadingFormat by remember { mutableStateOf<String?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var deleteBatchConfirm by remember { mutableStateOf(false) }
    val onConnectWifi = rememberWifiConnect()
    val scope = rememberCoroutineScope()
    val qrRepository = remember { CloudRepository(context) }

    val exitSelection = {
        selectionMode = false
        selectedIds = emptySet()
    }
    val allSelected = history.isNotEmpty() && history.all { it.id in selectedIds }
    val toggleSelectAll = {
        selectedIds = if (allSelected) emptySet() else history.map { it.id }.toSet()
    }
    val deleteSelected = {
        val toDelete = history.filter { it.id in selectedIds }
        viewModel.deleteScans(toDelete)
        exitSelection()
    }

    // System back: selection → detail dialog → redownload dialog → leave the screen.
    BackHandler {
        when {
            selectionMode -> exitSelection()
            detailItem != null -> detailItem = null
            redownloadItem != null -> redownloadItem = null
            else -> onBack()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectionMode) {
            GradientHeroHeader(
                title = stringResource(R.string.qr_n_selected, selectedIds.size),
                onBack = exitSelection,
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GradientHeroAction(
                            icon = Icons.Default.SelectAll,
                            contentDescription = stringResource(
                                if (allSelected) R.string.qr_deselect_all else R.string.qr_select_all
                            ),
                            onClick = toggleSelectAll,
                        )
                        GradientHeroAction(
                            icon = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            enabled = selectedIds.isNotEmpty(),
                            onClick = { if (selectedIds.isNotEmpty()) deleteBatchConfirm = true },
                        )
                    }
                },
            )
        } else {
            GradientHeroHeader(
                title = stringResource(R.string.qr_history),
                subtitle = stringResource(R.string.qr_history_subtitle),
                onBack = onBack,
                actions = {
                    GradientHeroAction(
                        icon = Icons.Default.Checklist,
                        contentDescription = stringResource(R.string.qr_select),
                        onClick = { selectionMode = true },
                    )
                },
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (history.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.QrCode,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.qr_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(history, key = { it.id }) { item ->
                        val selected = item.id in selectedIds
                        QrHistoryCard(
                            item = item,
                            selected = selected,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds =
                                        if (selected) selectedIds - item.id else selectedIds + item.id
                                } else {
                                    // Generated QRs carry a recipe and can be re-downloaded.
                                    if (item.designJson != null) redownloadItem = item
                                    else detailItem = item
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIds = setOf(item.id)
                                }
                            },
                            onDelete = { deleteItem = item }
                        )
                    }
                }
            }
        }
    }

    // Detail dialog: reopens the scan with all its actions (copy, share, open, connect).
    detailItem?.let { item ->
        val bitmap by produceState<Bitmap?>(null, item.imagePath) {
            value = withContext(Dispatchers.IO) {
                item.imagePath?.let { path ->
                    File(path).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(path) }
                }
            }
        }
        bitmap?.let { bmp ->
            val detected = QrDetected(
                content = item.content,
                format = item.format,
                bitmap = bmp,
                type = decodeQrType(item.typeData),
            )
            QrResultDialog(
                detected = detected,
                onCopy = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("QR", item.content))
                    Toast.makeText(context, R.string.qr_copied, Toast.LENGTH_SHORT).show()
                },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, item.content)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                },
                onOpen = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, item.content.toUri()))
                    }
                },
                onOpenMap = {
                    val geo = (detected.type as? QrDetected.Type.Geo) ?: return@QrResultDialog
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                "geo:${geo.lat},${geo.lng}?q=${geo.lat},${geo.lng}".toUri()
                            )
                        )
                    }
                },
                onConnect = onConnectWifi,
                onClose = { detailItem = null },
                onSaveImage = {
                    scope.launch(Dispatchers.IO) {
                        val ok = item.imagePath?.let { File(it) }
                            ?.let { saveScanImageToDownloads(context, it) } == true
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                if (ok) R.string.qr_saved else R.string.qr_save_error,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        }
    }

    deleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text(stringResource(R.string.qr_delete_title)) },
            text = { Text(stringResource(R.string.qr_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteScan(item.id, item.imagePath)
                    deleteItem = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteItem = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (deleteBatchConfirm) {
        AlertDialog(
            onDismissRequest = { deleteBatchConfirm = false },
            title = { Text(stringResource(R.string.qr_delete_title)) },
            text = { Text(stringResource(R.string.qr_delete_batch_message, selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteBatchConfirm = false
                    deleteSelected()
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteBatchConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    redownloadItem?.let { item ->
        QrRedownloadDialog(
            item = item,
            downloadingFormat = downloadingFormat,
            onDownload = { fmt ->
                if (downloadingFormat != null) return@QrRedownloadDialog
                downloadingFormat = fmt
                scope.launch(Dispatchers.IO) {
                    val ok = regenerateAndSaveQr(context, qrRepository, item.designJson, fmt)
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
            onDismiss = { redownloadItem = null },
        )
    }
}

/** Re-generates a previously generated QR and saves it to Downloads in [format]. */

@Composable
private fun QrRedownloadDialog(
    item: QrScanEntity,
    downloadingFormat: String?,
    onDownload: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val preview by produceState<Bitmap?>(null, item.imagePath) {
        value = item.imagePath
            ?.let { File(it).takeIf { f -> f.exists() } }
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
                    text = item.content,
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
                    listOf("png", "svg", "pdf").forEach { fmt ->
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

private fun qrTypeIcon(type: QrDetected.Type): ImageVector = when (type) {
    is QrDetected.Type.Wifi -> Icons.Default.Wifi
    is QrDetected.Type.Url -> Icons.Default.Link
    is QrDetected.Type.Email -> Icons.Default.Email
    is QrDetected.Type.Phone -> Icons.Default.Phone
    is QrDetected.Type.Sms -> Icons.AutoMirrored.Filled.Message
    is QrDetected.Type.Geo -> Icons.Default.LocationOn
    is QrDetected.Type.Text -> Icons.Default.QrCode
}

@Composable
private fun QrHistoryCard(
    item: QrScanEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.imagePath) {
        value = withContext(Dispatchers.IO) {
            item.imagePath?.let { path ->
                File(path).takeIf { it.exists() }?.let {
                    BitmapFactory.decodeFile(path)?.asImageBitmap()
                }
            }
        }
    }
    val context = LocalContext.current
    val date = remember(item.dateTime) {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(item.dateTime))
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = qrTypeIcon(decodeQrType(item.typeData)),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = item.format?.let { "$it · $date" } ?: date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                )
            } else {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.cloud_more_actions),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qr_copy)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                menuOpen = false
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("QR", item.content))
                                Toast.makeText(context, R.string.qr_copied, Toast.LENGTH_SHORT)
                                    .show()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share)) },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = {
                                menuOpen = false
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, item.content)
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(send, null))
                                }
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete, null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}
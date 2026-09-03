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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nopalito.app.R
import nopalito.app.ui.components.GradientHeroAction
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.QrScan
import nopalito.app.ui.screens.cloud.viewmodel.CloudQrTrashViewModel
import java.io.File

@Composable
fun CloudQrTrashScreen(
    viewModel: CloudQrTrashViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { CloudRepository(context) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var permanentItem by remember { mutableStateOf<QrScan?>(null) }
    var batchPermanentConfirm by remember { mutableStateOf(false) }
    val inSelection = state.selectedIds.isNotEmpty()
    val allSelected = state.scans.isNotEmpty() && state.scans.all { it.id in state.selectedIds }

    // Sync when the screen opens and every time the app returns to the
    // foreground (LifecycleResumeEffect also runs on first composition).
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // Single adaptive countdown shared by all scan cards. When a local
    // deadline crosses zero we just re-sync; only the API deletes permanently.
    val deadlines = remember(state.scans) {
        state.scans.mapNotNull { scan ->
            isoToEpochMillis(scan.scheduledDeletionAt)?.let { scan.id to it }
        }.toMap()
    }
    val now by rememberTrashNow(deadlines, onDeadlineReached = { viewModel.refresh() })

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
                title = stringResource(R.string.qr_trash_title),
                subtitle = stringResource(R.string.qr_trash_subtitle),
                onBack = onBack,
                actions = {
                    GradientHeroAction(
                        icon = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.refresh),
                        onClick = { viewModel.refresh() },
                    )
                },
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.DeleteForever,
                                null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.qr_trash_empty),
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
                            TrashScanCard(
                                scan = scan,
                                now = now,
                                thumbnailLoader = { repository.getScanThumbnail(scan.imageFileId!!) },
                                selected = scan.id in state.selectedIds,
                                selectionMode = inSelection,
                                onClick = {
                                    if (inSelection) viewModel.toggleSelection(scan.id)
                                },
                                onLongClick = {
                                    if (!inSelection) viewModel.select(scan.id)
                                },
                                onRestore = { viewModel.restore(scan.id) },
                                onPermanentDelete = { permanentItem = scan },
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
                        Button(onClick = { viewModel.restoreSelected() }) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cloud_restore_n, state.selectedIds.size))
                        }
                        Button(
                            onClick = { batchPermanentConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    R.string.cloud_delete_permanent_n,
                                    state.selectedIds.size
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    permanentItem?.let { scan ->
        AlertDialog(
            onDismissRequest = { permanentItem = null },
            title = { Text(stringResource(R.string.qr_delete_title)) },
            text = { Text(stringResource(R.string.qr_permanent_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.permanentlyDelete(scan.id)
                    permanentItem = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { permanentItem = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (batchPermanentConfirm) {
        AlertDialog(
            onDismissRequest = { batchPermanentConfirm = false },
            title = { Text(stringResource(R.string.qr_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.qr_permanent_delete_batch,
                        state.selectedIds.size
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.permanentlyDeleteSelected()
                    batchPermanentConfirm = false
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { batchPermanentConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun TrashScanCard(
    scan: QrScan,
    now: Long,
    thumbnailLoader: suspend () -> Result<File>,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    val thumbnail by produceState<Bitmap?>(null, scan.id) {
        value = runCatching { thumbnailLoader().getOrNull() }
            .getOrNull()
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

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
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp)),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = listOfNotNull(scan.format, typeLabel(scan.type)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ── Trash metadata: type badge + info button (popup) ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrashTypeBadge(
                        label = if (scan.trashType == "CLOUD_NORMAL") {
                            stringResource(R.string.cloud_trash_type_cloud)
                        } else {
                            stringResource(R.string.cloud_trash_type_qr)
                        }
                    )
                    TrashInfoButton(
                        now = now,
                        deadlineMillis = isoToEpochMillis(scan.scheduledDeletionAt),
                        trashedAtMillis = isoToEpochMillis(scan.trashedAt ?: scan.deletedAt),
                        trashSource = scan.trashSource,
                    )
                }
            }
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
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
                            text = { Text(stringResource(R.string.cloud_restore)) },
                            leadingIcon = { Icon(Icons.Default.Restore, null) },
                            onClick = { menuOpen = false; onRestore() },
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.cloud_delete_permanent),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DeleteForever, null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { menuOpen = false; onPermanentDelete() },
                        )
                    }
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

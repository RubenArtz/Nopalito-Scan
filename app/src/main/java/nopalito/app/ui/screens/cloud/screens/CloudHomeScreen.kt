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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nopalito.app.R
import nopalito.app.ui.components.GradientHeroAction
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.screens.cloud.model.StorageUsage
import nopalito.app.ui.screens.cloud.viewmodel.CloudFileListViewModel
import nopalito.app.ui.screens.cloud.viewmodel.CloudHomeViewModel

/**
 * Cloud home: a premium, files-first screen. The account header sits on top and the
 * "Mis archivos" list (files + export groups) is embedded directly below, so your
 * documents are visible from the moment you open the cloud. Subir archivo is the
 * floating action button; Papelera lives in the top bar.
 */
@Composable
fun CloudHomeScreen(
    viewModel: CloudHomeViewModel,
    fileListViewModel: CloudFileListViewModel,
    onNavigateToUpload: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToQrHistory: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val homeState by viewModel.state.collectAsState()
    val listState by fileListViewModel.state.collectAsState()
    val fileCount = listState.files.size + listState.exportGroups.size
    val totalBytes = (listState.files + listState.exportGroups).sumOf { it.size ?: 0L }
    val unknown = stringResource(R.string.cloud_size_unknown)

    // Server-authoritative usage; falls back to the visible list sum only while
    // the first fetch is pending (the backend remains the source of truth).
    val usage = homeState.storageUsage
    val storageLabel = usage?.let {
        stringResource(
            R.string.cloud_storage_used_of,
            formatCloudFileSize(it.usedBytes, unknown),
            formatCloudFileSize(it.limitBytes, unknown)
        )
    } ?: formatCloudFileSize(totalBytes, unknown)
    // Always re-fetch usage from the backend whenever the cloud Home is shown,
    // so the plan bar reflects EVERY cloud interaction (uploads, deletes,
    // restore, trash, QR history saves/deletes, sync) — including changes that
    // do not alter the visible file list (e.g. QR thumbnails).
    LaunchedEffect(Unit) {
        viewModel.refreshStorageUsage()
    }

    // Keep the header in sync too when the visible list changes while staying
    // on Home (delete/restore from the embedded file list).
    LaunchedEffect(fileCount, totalBytes) {
        if (fileCount > 0) {
            viewModel.refreshStorageUsage()
        }
    }

    CloudFileListView(
        viewModel = fileListViewModel,
        onUpload = onNavigateToUpload,
        onNavigateToTrash = onNavigateToTrash,
        onRefreshStorageUsage = { viewModel.refreshStorageUsage() },
        topBar = { inSelection ->
            if (inSelection) {
                GradientHeroHeader(
                    title = stringResource(
                        R.string.cloud_n_selected,
                        fileListViewModel.state.collectAsState().value.selectedIds.size
                    ),
                    onBack = { fileListViewModel.clearSelection() },
                    actions = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GradientHeroAction(
                                icon = Icons.Default.SelectAll,
                                contentDescription = stringResource(R.string.cloud_select_all),
                                onClick = { fileListViewModel.selectAll() },
                            )
                        }
                    },
                )
            } else {
                CloudHeroHeader(
                    fileCount = fileCount,
                    storageLabel = storageLabel,
                    storageUsage = usage,
                    onBack = onBack,
                    onNavigateToTrash = onNavigateToTrash,
                    onNavigateToQrHistory = onNavigateToQrHistory,
                    onNavigateToStorage = onNavigateToStorage,
                    onLogout = onLogout
                )
            }
        }
    )
}

/** Gradient hero banner (avatar + welcome + actions) with an overlapping stats card. */
@Composable
private fun CloudHeroHeader(
    fileCount: Int,
    storageLabel: String,
    storageUsage: StorageUsage?,
    onBack: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToQrHistory: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onLogout: () -> Unit
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                        )
                    )
                )
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.back),
                    onClick = onBack,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeaderAction(
                        icon = Icons.Default.Person,
                        description = stringResource(R.string.cloud_storage_title),
                        onClick = onNavigateToStorage,
                    )
                    HeaderAction(
                        icon = Icons.Default.QrCode,
                        description = stringResource(R.string.qr_cloud_history),
                        onClick = onNavigateToQrHistory,
                    )
                    HeaderAction(
                        icon = Icons.Default.Delete,
                        description = stringResource(R.string.cloud_trash),
                        onClick = onNavigateToTrash,
                    )
                    HeaderAction(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        description = stringResource(R.string.cloud_logout),
                        onClick = onLogout,
                    )
                }
            }
        }

        // Stats card overlapping the bottom of the banner.
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .offset(y = (-18).dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatItem(stringResource(R.string.cloud_files_label), "$fileCount")
                StatItem(stringResource(R.string.cloud_storage_label), storageLabel)
            }
            if (storageUsage != null) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                CloudStorageSummary(
                    usage = storageUsage,
                    showPercent = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun HeaderAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

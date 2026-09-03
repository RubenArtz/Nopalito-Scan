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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nopalito.app.R
import nopalito.app.ui.screens.cloud.model.SessionData
import nopalito.app.ui.screens.cloud.viewmodel.CloudSessionsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSessionsScreen(
    viewModel: CloudSessionsViewModel,
    onBack: () -> Unit,
    onCurrentRevoked: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var toRevoke by remember { mutableStateOf<SessionData?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Auto-refresh when screen opens — ensures fresh data even if ViewModel was cached
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    LaunchedEffect(state.revokeSuccess) {
        state.revokeSuccess?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeRevokeSuccess()
        }
    }
    LaunchedEffect(state.currentRevoked) {
        if (state.currentRevoked) {
            onCurrentRevoked()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_sessions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.cloud_refresh)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading && state.sessions.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    state.sessions.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.cloud_sessions_empty),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    else -> {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Text(
                                stringResource(R.string.cloud_sessions_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${state.activeCount} / ${state.maxConcurrent}",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(Modifier.height(12.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(state.sessions) { session ->
                                    SessionRow(
                                        session = session,
                                        onRevoke = { toRevoke = session }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    toRevoke?.let { s ->
        AlertDialog(
            onDismissRequest = { toRevoke = null },
            title = { Text(stringResource(R.string.cloud_sessions_revoke)) },
            text = { Text(stringResource(R.string.cloud_sessions_revoke_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revoke(s.id, s.isCurrent)
                    toRevoke = null
                }) { Text(stringResource(R.string.cloud_sessions_revoke)) }
            },
            dismissButton = {
                TextButton(onClick = { toRevoke = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionData,
    onRevoke: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Filled.Devices,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            session.deviceName ?: session.deviceId
                            ?: stringResource(R.string.cloud_sessions_unknown_device),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        session.ipAddress?.let {
                            // Mask for privacy: show first 2 octets
                            val masked = it.split(".").let { parts ->
                                if (parts.size == 4) "${parts[0]}.${parts[1]}.***.***" else it
                            }
                            Text(
                                masked,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        (session.lastSeenAt ?: session.createdAt)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (session.isCurrent) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.cloud_sessions_current)) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRevoke) {
                    Text(stringResource(R.string.cloud_sessions_revoke))
                }
            }
        }
    }
}
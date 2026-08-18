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

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.FragmentActivity
import nopalito.app.R
import nopalito.app.ui.screens.cloud.model.StorageUsage
import nopalito.app.ui.screens.cloud.security.AndroidBiometricPromptController
import nopalito.app.ui.screens.cloud.security.BiometricPromptHost
import nopalito.app.ui.screens.cloud.security.buildBiometricPromptInfo
import nopalito.app.ui.screens.cloud.viewmodel.CloudStorageViewModel

/**
 * Storage screen: the plan, used/free/limit come exclusively from
 * GET /api/storage/usage (server-authoritative). Bytes are handled internally
 * and formatted to MB/GB only for display. The upgrade button does not grant
 * anything — premium is awarded by the backend admin API.
 */
@Composable
fun CloudStorageScreen(
    viewModel: CloudStorageViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val usage = state.usage
    var showPremiumInfo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Host the biometric prompt while this screen is attached: the gate screen
    // is not composed here, but the enable prompt must still resolve a
    // controller bound to this activity.
    val context = LocalContext.current
    val activity = remember { context as FragmentActivity }
    val promptInfo = remember { buildBiometricPromptInfo(context) }
    val controller = remember(activity, promptInfo) {
        AndroidBiometricPromptController(activity, promptInfo)
    }
    DisposableEffect(controller) {
        BiometricPromptHost.register(controller)
        onDispose { BiometricPromptHost.unregister(controller) }
    }

    // Download folder picker: the chosen SAF tree applies to every download
    // destination in the app (cloud, QR exports, export history).
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
            viewModel.setDownloadDir(treeUri.toString())
        }
    }

    if (showPremiumInfo) {
        AlertDialog(
            onDismissRequest = { showPremiumInfo = false },
            title = { Text(stringResource(R.string.cloud_premium_info_title)) },
            text = { Text(stringResource(R.string.cloud_premium_info_body)) },
            confirmButton = {
                TextButton(onClick = { showPremiumInfo = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        MaterialTheme.colorScheme.background,
                    )
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onBack,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                shadowElevation = 2.dp,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.padding(12.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cloud_storage_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.cloud_storage_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.usage != null) {
                IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cloud_retry))
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when {
                        state.isLoading && usage == null -> {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = 60.dp)
                            )
                        }

                        state.errorMessage != null && usage == null -> {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = state.errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = viewModel::refresh) {
                                    Text(stringResource(R.string.cloud_retry))
                                }
                            }
                        }

                        usage != null -> {
                            StorageUsageCard(usage, onUpgrade = { showPremiumInfo = true })
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    BiometricUnlockCard(
                        enabled = state.biometricEnabled,
                        busy = state.biometricBusy,
                        message = state.biometricMessage,
                        onToggle = viewModel::toggleBiometric,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                    )

                    Spacer(Modifier.height(16.dp))

                    DownloadFolderCard(
                        uri = state.downloadDirUri,
                        onPick = { folderPicker.launch(null) },
                        onReset = { viewModel.setDownloadDir(null) },
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                    )

                    Spacer(Modifier.height(16.dp))

                    // Change password INSIDE this view: email code → new password.
                    // No additional screens.
                    ChangePasswordCard(
                        viewModel = viewModel,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 24.dp)
                    )
                }
            }
        }
    }
}

/**
 * Inline "Change password" section of the Storage view. Sends a single-use
 * verification code by email, asks for it here and stores the new password —
 * all within the same screen.
 */
@Composable
private fun ChangePasswordCard(
    viewModel: CloudStorageViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        if (!state.changeOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleChangePassword() }
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.cloud_change_password),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.cloud_change_password_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                if (state.changeSuccess) {
                    CloudSuccessAnimation(
                        message = stringResource(R.string.cloud_password_changed_success),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = viewModel::toggleChangePassword,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cloud_quota_got_it),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.cloud_change_password),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))

                    if (state.changeError != null) {
                        state.changeError?.let { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (!state.changeCodeSent) {
                        Text(
                            text = stringResource(R.string.cloud_change_password_instruction),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = viewModel::requestChangePasswordCode,
                            enabled = !state.changeSending,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (state.changeSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.cloud_send_verification_code),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = state.changeCode,
                            onValueChange = viewModel::updateChangeCode,
                            label = { Text(stringResource(R.string.cloud_otp_label)) },
                            placeholder = { Text(stringResource(R.string.cloud_otp_placeholder)) },
                            singleLine = true,
                            enabled = !state.changeSubmitting,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next
                            ),
                            shape = RoundedCornerShape(16.dp),
                            isError = state.changeError != null,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.headlineSmall.copy(
                                textAlign = TextAlign.Center,
                                letterSpacing = 8.sp
                            )
                        )

                        Spacer(Modifier.height(12.dp))

                        CloudPasswordTextField(
                            value = state.changeNewPassword,
                            onValueChange = viewModel::updateChangeNewPassword,
                            label = { Text(stringResource(R.string.cloud_new_password_label)) },
                            placeholder = { Text(stringResource(R.string.cloud_password_placeholder)) },
                            enabled = !state.changeSubmitting,
                            isError = state.changeError != null,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Next
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(12.dp))

                        CloudPasswordTextField(
                            value = state.changeConfirmPassword,
                            onValueChange = viewModel::updateChangeConfirmPassword,
                            label = { Text(stringResource(R.string.cloud_confirm_password_label)) },
                            placeholder = { Text(stringResource(R.string.cloud_confirm_password_placeholder)) },
                            enabled = !state.changeSubmitting,
                            isError = state.changeError != null,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            onDone = { viewModel.submitChangePassword() },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(16.dp))

                        Button(
                            onClick = viewModel::submitChangePassword,
                            enabled = !state.changeSubmitting,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                        ) {
                            if (state.changeSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.cloud_save_password),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        TextButton(
                            onClick = viewModel::resendChangePasswordCode,
                            enabled = !state.changeSubmitting
                        ) {
                            Text(
                                text = stringResource(R.string.cloud_resend_code),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageUsageCard(usage: StorageUsage, onUpgrade: () -> Unit) {
    val isPremium = usage.isPremiumPlan

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary,
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.cloud_storage_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            CloudStorageSummary(usage = usage, showPercent = true)

            if (isPremium) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.cloud_premium_active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            } else {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onUpgrade,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cloud_upgrade_premium),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * Download folder: shows the SAF tree picked by the user (or the default
 * Downloads/Nopalito Scan) and opens the system folder picker on tap. The
 * trailing clear button restores the default destination.
 */
@Composable
private fun DownloadFolderCard(
    uri: String?,
    onPick: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val defaultLabel = stringResource(R.string.download_dir_default)
    val folderName = remember(uri, defaultLabel) {
        uri?.let { DocumentFile.fromTreeUri(context, it.toUri())?.name } ?: defaultLabel
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.download_dir_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (uri != null) {
                IconButton(onClick = onReset, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.download_dir_reset),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = stringResource(R.string.change_directory),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}

/**
 * Biometric unlock toggle: ON migrates the refresh token into the auth-bound
 * blob (one OS prompt), OFF moves it back to the normal prefs. The prompt is
 * hosted by the storage screen itself ([BiometricPromptHost] registration
 * above), so no gate screen needs to be composed.
 */
@Composable
private fun BiometricUnlockCard(
    enabled: Boolean,
    busy: Boolean,
    message: String?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cloud_biometric_toggle_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.cloud_biometric_toggle_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = { onToggle() },
            )
        }
    }
}

/**
 * Shared, animated storage summary: plan pill + "used of limit" + an animated
 * progress bar + free space. Values come from the backend only; the bar
 * re-animates whenever the usage changes (uploads, deletes, restore, purge,
 * sync). Bytes stay internal — MB/GB are applied only for display.
 */
@Composable
fun CloudStorageSummary(
    usage: StorageUsage?,
    modifier: Modifier = Modifier,
    showPercent: Boolean = true,
) {
    val usage = usage ?: return
    val unknown = stringResource(R.string.cloud_size_unknown)
    val usedLabel = formatCloudFileSize(usage.usedBytes, unknown)
    val limitLabel = formatCloudFileSize(usage.limitBytes, unknown)
    val freeLabel = formatCloudFileSize(usage.freeBytes, unknown)

    val animatedProgress by animateFloatAsState(
        targetValue = usage.progressRatio,
        animationSpec = tween(durationMillis = 700),
        label = "storageProgress",
    )

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlanBadge(usage.isPremiumPlan)
            Text(
                text = stringResource(R.string.cloud_storage_used_of, usedLabel, limitLabel),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = if (usage.isPremiumPlan) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.primary
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (showPercent) {
                Text(
                    text = stringResource(R.string.cloud_storage_percent_used, usage.usedPercent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.cloud_storage_free, freeLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlanBadge(isPremium: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (isPremium) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = stringResource(if (isPremium) R.string.cloud_plan_premium else R.string.cloud_plan_free),
            style = MaterialTheme.typography.labelSmall,
            color = if (isPremium) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
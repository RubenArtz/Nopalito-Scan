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

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import nopalito.app.BuildConfig
import nopalito.app.R
import nopalito.app.ui.screens.cloud.data.GoogleSignInHelper
import nopalito.app.ui.screens.cloud.model.StorageUsage
import nopalito.app.ui.screens.cloud.security.AndroidBiometricPromptController
import nopalito.app.ui.screens.cloud.security.BiometricPromptHost
import nopalito.app.ui.screens.cloud.security.buildBiometricPromptInfo
import nopalito.app.ui.screens.cloud.viewmodel.AccountLinkGoogleViewModel
import nopalito.app.ui.screens.cloud.viewmodel.CloudStorageViewModel
import nopalito.app.ui.screens.cloud.viewmodel.CloudViewModelFactory
import nopalito.app.ui.screens.cloud.viewmodel.ProfileAvatarViewModel

/**
 * Storage screen: the plan, used/free/limit come exclusively from
 * GET /api/storage/usage (server-authoritative). Bytes are handled internally
 * and formatted to MB/GB only for display. The upgrade button does not grant
 * anything — premium is awarded by the backend admin API.
 */
@Composable
fun CloudStorageScreen(
    viewModel: CloudStorageViewModel,
    onBack: () -> Unit,
    onLogout: () -> Unit = {},
    onManageDevices: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val usage = state.usage
    var showPremiumInfo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Host the biometric prompt while this screen is attached: the gate screen
    // is not composed here, but the enable prompt must still resolve a
    // controller bound to this activity.
    // Prompt must be WEAK-aware: if STRONG unavailable but WEAK available and
    // user has accepted WEAK, the prompt must allow WEAK (see BiometricCapability).
    val context = LocalContext.current
    val activity = remember { context as FragmentActivity }
    // Avatar for ProfileCard header (shows Google photo or custom, else person icon)
    val avatarViewModel: ProfileAvatarViewModel = viewModel(
        factory = CloudViewModelFactory(context.applicationContext as Application)
    )
    val avatarState by avatarViewModel.state.collectAsState()
    LaunchedEffect(Unit) { avatarViewModel.load() }

    val weakPref =
        remember { nopalito.app.ui.screens.cloud.security.BiometricWeakPreference.open(context) }
    // Recompute when dialog state or preference changes.
    val allowWeakForPrompt = remember(state.showWeakDialog, state.biometricEnabled) {
        val checker =
            nopalito.app.ui.screens.cloud.security.AndroidBiometricCapabilityChecker(context)
        nopalito.app.ui.screens.cloud.security.BiometricCapability.shouldOfferWeakFallback(checker) &&
                (weakPref.isWeakAccepted || state.showWeakDialog)
    }
    val promptInfo =
        remember(allowWeakForPrompt) { buildBiometricPromptInfo(context, allowWeakForPrompt) }
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
        SubscriptionPlansDialog(
            onDismiss = { showPremiumInfo = false },
            currentPlan = usage?.plan,
            onPurchaseCompleted = {
                // Backend has already applied the plan; refresh usage immediately so
                // StorageUsageCard shows the new plan/quota when the dialog closes without pull-to-refresh
                viewModel.refresh()
            }
        )
    }

    if (state.showWeakDialog) {
        AlertDialog(
            onDismissRequest = viewModel::onWeakDialogDismiss,
            title = { Text(stringResource(R.string.cloud_biometric_weak_title)) },
            text = { Text(stringResource(R.string.cloud_biometric_weak_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::onWeakDialogAccept) {
                    Text(stringResource(R.string.cloud_biometric_weak_use_face))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onWeakDialogUsePin) {
                    Text(stringResource(R.string.cloud_biometric_weak_use_pin))
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
                // Top + horizontal only: the IME bottom inset must never pad
                // this header, otherwise it grows when the keyboard opens and
                // pushes/collapses everything below it.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
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
                    tint = Color.White
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cloud_storage_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = stringResource(R.string.cloud_storage_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.usage != null) {
                IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cloud_retry),
                        tint = Color.White
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // The form area (not the header) yields to the keyboard; the
                // nav bar padding keeps content clear of gesture navigation.
                .navigationBarsPadding()
                .imePadding()
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

                    ProfileCard(
                        email = state.profileEmail,
                        displayName = state.profileDisplayName,
                        avatarUrl = avatarState.avatarUrl,
                        loading = state.profileLoading,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onLogout = onLogout
                    )

                    Spacer(Modifier.height(16.dp))

                    LinkGoogleCard(
                        storageViewModel = viewModel,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

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

                    LoginCodeCard(
                        enabled = state.loginCodeEnabled,
                        busy = state.loginCodeBusy,
                        message = state.loginCodeMessage,
                        loaded = state.loginCodeLoaded,
                        onToggle = viewModel::toggleLoginCode,
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

                    DevicesCard(
                        onManage = onManageDevices,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(Modifier.height(16.dp))

                    // Change password INSIDE this view: email code → new password.
                    // No additional screens.
                    ChangePasswordCard(
                        viewModel = viewModel,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                    )

                    Spacer(Modifier.height(16.dp))

                    ChangeEmailCard(
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
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
                        state.changeInfoMessage?.let { info ->
                            Text(
                                text = info,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
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
                        state.changeInfoMessage?.let { info ->
                            Text(
                                text = info,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                        }

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
                            onClick = { viewModel.resendChangePasswordCode() },
                            enabled = !state.changeSending && !state.changeSubmitting &&
                                    state.changeResendCooldownSeconds == 0
                        ) {
                            if (state.changeSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = if (state.changeResendCooldownSeconds > 0) {
                                    stringResource(
                                        R.string.cloud_resend_in_seconds,
                                        state.changeResendCooldownSeconds
                                    )
                                } else {
                                    stringResource(R.string.cloud_resend_code)
                                },
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
private fun ChangeEmailCard(
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
        if (!state.emailChangeOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleChangeEmail() }
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.cloud_change_email),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.cloud_change_email_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Default.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                if (state.emailSuccess) {
                    CloudSuccessAnimation(
                        message = stringResource(R.string.cloud_email_changed_success),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = viewModel::toggleChangeEmail,
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
                        text = stringResource(R.string.cloud_change_email),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))

                    if (state.emailError != null) {
                        state.emailError?.let { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (!state.emailCodeSent) {
                        state.emailInfoMessage?.let { info ->
                            Text(
                                text = info,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            text = stringResource(R.string.cloud_change_email_instruction),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))

                        OutlinedTextField(
                            value = state.emailNewEmail,
                            onValueChange = viewModel::updateEmailNewEmail,
                            label = { Text(stringResource(R.string.cloud_new_email_label)) },
                            placeholder = { Text(stringResource(R.string.cloud_new_email_placeholder)) },
                            singleLine = true,
                            enabled = !state.emailSending,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            shape = RoundedCornerShape(16.dp),
                            isError = state.emailError != null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = viewModel::requestEmailChangeCode,
                            enabled = !state.emailSending && state.emailNewEmail.isNotBlank(),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (state.emailSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.cloud_send_code_to_new_email),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    } else {
                        state.emailInfoMessage?.let { info ->
                            Text(
                                text = info,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        OutlinedTextField(
                            value = state.emailCode,
                            onValueChange = viewModel::updateEmailCode,
                            label = { Text(stringResource(R.string.cloud_otp_label)) },
                            placeholder = { Text(stringResource(R.string.cloud_otp_placeholder)) },
                            singleLine = true,
                            enabled = !state.emailSubmitting,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            shape = RoundedCornerShape(16.dp),
                            isError = state.emailError != null,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.headlineSmall.copy(
                                textAlign = TextAlign.Center,
                                letterSpacing = 8.sp
                            )
                        )

                        Spacer(Modifier.height(16.dp))

                        Button(
                            onClick = viewModel::submitEmailChange,
                            enabled = !state.emailSubmitting,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                        ) {
                            if (state.emailSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.cloud_verify_and_change_email),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        TextButton(
                            onClick = { viewModel.resendEmailChangeCode() },
                            enabled = !state.emailSending && !state.emailSubmitting &&
                                    state.emailResendCooldownSeconds == 0
                        ) {
                            if (state.emailSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = if (state.emailResendCooldownSeconds > 0) {
                                    stringResource(
                                        R.string.cloud_resend_in_seconds,
                                        state.emailResendCooldownSeconds
                                    )
                                } else {
                                    stringResource(R.string.cloud_resend_code)
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Card to link the current authenticated account to a Google identity.
 * Uses Credential Manager to obtain an ID token and calls
 * POST /api/account/link-google. No session rotation.
 */
@Composable
private fun LinkGoogleCard(
    storageViewModel: CloudStorageViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val factory = androidx.compose.runtime.remember { CloudViewModelFactory(app) }
    val linkVm: AccountLinkGoogleViewModel = viewModel(factory = factory)
    val linkState by linkVm.state.collectAsState()
    val storageState by storageViewModel.state.collectAsState()
    val scope = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycleScope

    val isLinked = !storageState.profileGoogleId.isNullOrBlank() ||
            storageState.profileAuthProvider == "hybrid" ||
            storageState.profileAuthProvider == "google"

    // Refresh profile after successful link
    androidx.compose.runtime.LaunchedEffect(linkState.isSuccess) {
        if (linkState.isSuccess) {
            storageViewModel.loadProfile()
        }
    }

    fun launchLink() {
        val trimmedClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID.trim()
        if (trimmedClientId.isBlank()) {
            Log.e("GoogleSignIn", "LinkGoogleCard: GOOGLE_SERVER_CLIENT_ID is blank")
            linkVm.onGoogleNotConfigured()
            return
        }
        Log.d("GoogleSignIn", "LinkGoogleCard: Google link requested")
        scope.launch {
            try {
                val idToken = GoogleSignInHelper.requestGoogleIdToken(context)
                Log.d(
                    "GoogleSignIn",
                    "LinkGoogleCard: idTokenReceived=true - calling ViewModel linkGoogle"
                )
                linkVm.linkGoogle(idToken)
            } catch (e: GetCredentialCancellationException) {
                val msgLower = e.message?.lowercase() ?: ""
                if (msgLower.contains("reauth")) {
                    Log.w(
                        "GoogleSignIn",
                        "LinkGoogleCard: GetCredentialCancellationException reauth required type=${e.type} message=${e.message}",
                        e
                    )
                    linkVm.onGoogleReauthRequired()
                } else {
                    Log.d(
                        "GoogleSignIn",
                        "LinkGoogleCard: GetCredentialCancellationException type=${e.type} message=${e.message}",
                        e
                    )
                    linkVm.onGoogleCancelled()
                }
            } catch (e: NoCredentialException) {
                Log.w(
                    "GoogleSignIn",
                    "LinkGoogleCard: NoCredentialException type=${e.type} message=${e.message}",
                    e
                )
                linkVm.onGoogleNoAccount()
            } catch (e: GoogleIdTokenParsingException) {
                Log.e(
                    "GoogleSignIn",
                    "LinkGoogleCard: GoogleIdTokenParsingException message=${e.message}",
                    e
                )
                linkVm.onGoogleGenericError()
            } catch (e: GetCredentialException) {
                val classification = GoogleSignInHelper.classifyException(e)
                Log.e(
                    "GoogleSignIn",
                    "LinkGoogleCard: GetCredentialException classification=$classification class=${e::class.java.simpleName} type=${e.type} message=${e.message} cause=${e.cause?.javaClass?.simpleName}:${e.cause?.message}",
                    e
                )
                when (classification) {
                    "NO_CREDENTIAL" -> linkVm.onGoogleNoAccount()
                    "NETWORK" -> linkVm.onGoogleNetworkError()
                    "CONFIG", "CONFIG_BLANK" -> linkVm.onGoogleConfigError()
                    else -> linkVm.onGoogleGenericError()
                }
            } catch (e: IllegalStateException) {
                val msg = e.message?.lowercase() ?: ""
                Log.e(
                    "GoogleSignIn",
                    "LinkGoogleCard: IllegalStateException message=${e.message}",
                    e
                )
                when {
                    msg.contains("blank") -> linkVm.onGoogleNotConfigured()
                    msg.contains("configuration") || msg.contains("client") -> linkVm.onGoogleConfigError()
                    else -> linkVm.onGoogleGenericError()
                }
            } catch (e: Exception) {
                Log.e(
                    "GoogleSignIn",
                    "LinkGoogleCard: Unexpected exception class=${e::class.java.simpleName} message=${e.message}",
                    e
                )
                val msg = e.message?.lowercase() ?: ""
                when {
                    msg.contains("reauth") -> linkVm.onGoogleReauthRequired()
                    msg.contains("cancel") -> linkVm.onGoogleCancelled()
                    else -> linkVm.onGoogleGenericError()
                }
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.account_link_google_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (isLinked) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.account_google_linked_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (isLinked) {
                    Icon(
                        painter = painterResource(R.drawable.ic_google),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.Unspecified
                    )
                }
            }

            if (isLinked) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.account_google_linked_success),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { launchLink() },
                    enabled = !linkState.isLoading,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    if (linkState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_google),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.account_link_google_button),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                if (linkState.showDialog && linkState.errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = linkState.errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (linkState.showDialog && linkState.isSuccess) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = linkState.successMessage
                            ?: stringResource(R.string.account_google_linked_success),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (linkState.showDialog && (linkState.isSuccess || linkState.errorMessage != null)) {
        // Auto-dismiss handled via button; also show dialog for important errors like email mismatch
        // We reuse the inline text above, but also ensure the dialog is dismissible by tapping
        // No extra AlertDialog needed — inline message is enough; keep for accessibility
    }
}

@Composable
private fun StorageUsageCard(usage: StorageUsage, onUpgrade: () -> Unit) {
    val normalizedPlan = usage.normalizedPlan

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
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
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

            if (!usage.isFreePlan) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = when (normalizedPlan) {
                        "PERSONAL" -> stringResource(R.string.cloud_premium_active_personal)
                        "PLUS" -> stringResource(R.string.cloud_premium_active_plus)
                        else -> stringResource(R.string.cloud_premium_active_plus)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onUpgrade,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Text(
                    text = stringResource(R.string.cloud_view_plans),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Devices card: entry point to active sessions (up to 5 devices).
 * Uses translation key billing_feature_devices_5 via string resource.
 */
@Composable
private fun DevicesCard(
    onManage: () -> Unit,
    modifier: Modifier = Modifier
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
                .clickable(onClick = onManage)
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cloud_sessions_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.billing_feature_devices_5),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.Devices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
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
 * Email login-code toggle: when ON (default) password login sends a 6-digit
 * code to the email and the OTP screen is required; when OFF the password
 * alone is enough and the code step is skipped. The super admin can flip the
 * same preference from the panel (PUT /admin/users/{id}).
 */
@Composable
private fun LoginCodeCard(
    enabled: Boolean,
    busy: Boolean,
    message: String?,
    loaded: Boolean,
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
                    text = stringResource(R.string.cloud_login_code_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.cloud_login_code_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Switch(
                checked = enabled,
                enabled = !busy && loaded,
                onCheckedChange = { onToggle() },
            )
        }
    }
}

/**
 * Profile header shown at the top of cloud settings.
 * Displays the authenticated user's display name (or email prefix) and email,
 * refreshed from GET /api/auth/me. Name may be null/blank for accounts without
 * display_name — falls back to email prefix.
 */
@Composable
private fun ProfileCard(
    email: String?,
    displayName: String?,
    avatarUrl: String? = null,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onLogout: (() -> Unit)? = null,
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(avatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val name =
                    displayName?.takeIf { it.isNotBlank() } ?: email?.substringBefore("@") ?: ""
                if (name.isNotBlank()) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!email.isNullOrBlank()) {
                    Text(
                        text = email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (loading) {
                    Text(
                        text = stringResource(R.string.cloud_checking_session),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.cloud_no_session),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        if (onLogout != null && !email.isNullOrBlank()) {
            OutlinedButton(
                onClick = onLogout,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(text = stringResource(R.string.cloud_logout))
            }
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
            PlanBadge(usage.normalizedPlan)
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
private fun PlanBadge(plan: String) {
    val normalized = plan.uppercase().trim()
    val labelRes = when (normalized) {
        "PERSONAL" -> R.string.cloud_plan_personal
        "PLUS" -> R.string.cloud_plan_plus
        else -> R.string.cloud_plan_free
    }
    val bgColor = when (normalized) {
        "PERSONAL" -> Color(0xFF00C853).copy(alpha = 0.15f)
        "PLUS" -> Color(0xFF7C4DFF).copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when (normalized) {
        "PERSONAL" -> Color(0xFF00C853)
        "PLUS" -> Color(0xFF7C4DFF)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bgColor,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
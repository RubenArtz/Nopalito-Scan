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

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nopalito.app.R
import nopalito.app.ui.screens.cloud.data.PcLinkErrorStrings
import nopalito.app.ui.screens.cloud.model.CloudLinkSession
import nopalito.app.ui.screens.cloud.model.PcLinkState
import nopalito.app.ui.screens.cloud.viewmodel.PcLinkBadge
import nopalito.app.ui.screens.cloud.viewmodel.PcLinkTab
import nopalito.app.ui.screens.cloud.viewmodel.PcLinkViewModel
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

private const val CLOUD_URL = "https://nopalitoscan.org/app/cloud"

/**
 * Cloud Link pairing screen (Receive scans on your PC).
 *
 * Reuses the exact Cloud visual language from [CloudStorageScreen]:
 * gradient header, circular back surface, typography, card shape and
 * MaterialTheme color scheme. Camera and PIN tabs are always visible.
 * A newly linked device shows an animated success card with the device
 * and a live countdown until session expiry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcLinkScreen(
    viewModel: PcLinkViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cloudLinkOpenErrorText = stringResource(R.string.cloud_link_open_error)

    val approveErrorText = state.approveError?.let { stringResource(PcLinkErrorStrings.resId(it)) }
    LaunchedEffect(approveErrorText) {
        if (approveErrorText != null) {
            snackbar.showSnackbar(approveErrorText)
            viewModel.clearApproveError()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshDevices()
    }

    var showHelpSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun openCloudInBrowser() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, CLOUD_URL.toUri()))
        } catch (_: ActivityNotFoundException) {
            scope.launch {
                snackbar.showSnackbar(cloudLinkOpenErrorText)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0)
    ) { scaffoldPadding ->
        // Edge-to-edge by design (the header draws behind the status bar and
        // each section applies its own insets below). scaffoldPadding is zero
        // today (no bars, zero content insets) but consuming it here keeps the
        // content clear if a top/bottom bar is ever added.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Cloud hero header — identical to CloudHomeScreen (green + purple gradient)
            // Edge-to-edge: background draws behind status bar / camera cutout
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.cloud_link_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = stringResource(R.string.cloud_link_instructions_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }

            // Compact secondary help button — keeps the screen clean and focused
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(
                    onClick = { showHelpSheet = true },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.cloud_link_help_button),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                    Tab(
                        selected = state.tab == PcLinkTab.SCAN_QR,
                        onClick = { viewModel.selectTab(PcLinkTab.SCAN_QR) },
                        text = { Text(stringResource(R.string.cloud_link_scan_qr)) }
                    )
                    Tab(
                        selected = state.tab == PcLinkTab.ENTER_PIN,
                        onClick = { viewModel.selectTab(PcLinkTab.ENTER_PIN) },
                        text = { Text(stringResource(R.string.cloud_link_enter_pin)) }
                    )
                }

                when (state.tab) {
                    PcLinkTab.SCAN_QR -> {
                        Text(
                            text = stringResource(R.string.cloud_link_qr_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            PcLinkQrScanner(
                                paused = state.isApproving,
                                onQrDetected = viewModel::onQrDetected
                            )
                        }
                        if (state.isApproving) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.cloud_link_approving),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    PcLinkTab.ENTER_PIN -> {
                        PinEntry(
                            pin = state.pin,
                            isApproving = state.isApproving,
                            lockedUntilMs = state.pinLockedUntilMs,
                            onPinChanged = viewModel::onPinChanged,
                            onApprove = viewModel::approvePin
                        )
                    }
                }

                AnimatedVisibility(
                    visible = state.approvedIntentId != null,
                    enter = scaleIn(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(200))
                ) {
                    val latestDevice = state.devices.firstOrNull()
                    LinkSuccessAnimatedCard(
                        device = latestDevice,
                        onDone = { viewModel.dismissApproved() },
                        onUnlink = { viewModel.unlinkLocal() }
                    )
                }

                LinkedDevicesCard(
                    devices = state.devices,
                    loading = state.devicesLoading,
                    devicesError = state.devicesError?.let {
                        stringResource(
                            PcLinkErrorStrings.resId(
                                it
                            )
                        )
                    },
                    revokingId = state.revokingId,
                    badge = state.badge,
                    pcState = state.pcState,
                    onRefresh = viewModel::refreshDevices,
                    onRevoke = viewModel::revokeDevice
                )
            }
        }
    }

    if (showHelpSheet) {
        ModalBottomSheet(
            onDismissRequest = { showHelpSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            CloudLinkHelpSheetContent(
                onOpenBrowser = {
                    showHelpSheet = false
                    openCloudInBrowser()
                }
            )
        }
    }
}

@Composable
private fun CloudLinkHelpSheetContent(
    onOpenBrowser: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with icon and title — matches Cloud modal style
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cloud_link_help_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.cloud_link_help_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // URL pill — visually anchored, not a full card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = CLOUD_URL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Steps — polished, minimal
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HelpStepRow(
                number = "1",
                title = stringResource(R.string.cloud_link_help_step1),
                description = stringResource(R.string.cloud_link_instructions)
            )
            HelpStepRow(
                number = "2",
                title = stringResource(R.string.cloud_link_help_step2),
                description = stringResource(R.string.cloud_link_help_detail),
                icon = {
                    Icon(
                        Icons.Filled.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }

        // Primary action — opens browser
        Button(
            onClick = onOpenBrowser,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.cloud_link_open_browser),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun HelpStepRow(
    number: String,
    title: String,
    description: String,
    icon: @Composable (() -> Unit)? = null
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        ) {
            androidx.compose.foundation.layout.Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = number,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (icon != null) icon()
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PinEntry(
    pin: String,
    isApproving: Boolean,
    lockedUntilMs: Long,
    onPinChanged: (String) -> Unit,
    onApprove: () -> Unit
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val locked = nowMs < lockedUntilMs
    LaunchedEffect(locked) {
        while (System.currentTimeMillis() < lockedUntilMs) {
            delay(1000.milliseconds)
            nowMs = System.currentTimeMillis()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.cloud_link_pin_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        OutlinedTextField(
            value = pin,
            onValueChange = onPinChanged,
            label = { Text(stringResource(R.string.cloud_link_pin_label)) },
            placeholder = { Text(stringResource(R.string.cloud_link_pin_hint)) },
            singleLine = true,
            enabled = !isApproving && !locked,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 28.sp,
                letterSpacing = 8.sp,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (locked) {
            val remainingSec =
                TimeUnit.MILLISECONDS.toSeconds((lockedUntilMs - nowMs).coerceAtLeast(0))
            val clock = "%02d:%02d".format(remainingSec / 60, remainingSec % 60)
            Text(
                text = stringResource(R.string.cloud_link_pin_cooldown, clock),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Button(
            onClick = onApprove,
            enabled = pin.length == PcLinkViewModel.PIN_LENGTH && !isApproving && !locked,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isApproving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.cloud_link_pin_approve))
        }
    }
}

@Composable
private fun LinkSuccessAnimatedCard(
    device: CloudLinkSession?,
    onDone: () -> Unit,
    onUnlink: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.cloud_link_success_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.cloud_link_success_expires),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            if (device != null) {
                Spacer(Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = device.deviceLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        val detail =
                            listOfNotNull(device.lastSeenLabel, device.ipLabel).joinToString(" · ")
                        if (detail.isNotEmpty()) {
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SessionCountdown(expiresAt = device.effectiveExpiresAt)
                    }
                }
            } else {
                // No server expiry known yet (the PC has not exchanged the
                // intent, or the row carries no timestamp): show active state
                // instead of inventing a local 24h countdown that would
                // diverge from the panel.
                SessionCountdown(expiresAt = null)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDone) {
                    Text(stringResource(R.string.cloud_link_done))
                }
                TextButton(onClick = onUnlink) {
                    Text(stringResource(R.string.cloud_link_unlink))
                }
            }
        }
    }
}

@Composable
private fun SessionCountdown(expiresAt: String?) {
    // No server timestamp: the session is alive but its sliding window is
    // unknown — label it instead of guessing a countdown.
    if (expiresAt == null) {
        Text(
            text = stringResource(R.string.cloud_link_active_extends),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        return
    }
    var remaining by remember(expiresAt) { mutableStateOf(formatRemaining(expiresAt)) }
    LaunchedEffect(expiresAt) {
        while (true) {
            remaining = formatRemaining(expiresAt)
            if (remaining == null || remaining == "00:00:00") break
            delay(1000.milliseconds)
        }
    }
    remaining?.let { r ->
        Text(
            text = stringResource(R.string.cloud_link_expires_in, r),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatRemaining(expiresAt: String?): String? {
    val targetMs = parseExpiresAt(expiresAt ?: return null) ?: return null
    val diff = targetMs - System.currentTimeMillis()
    if (diff <= 0) return "00:00:00"
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff) % 60
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun parseExpiresAt(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val patterns = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX"
    )
    for (pat in patterns) {
        try {
            val sdf = SimpleDateFormat(pat, Locale.ROOT)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val d = sdf.parse(trimmed)
            if (d != null) return d.time
        } catch (_: Exception) {
        }
    }
    return try {
        trimmed.toLong()
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun LinkedDevicesCard(
    devices: List<CloudLinkSession>,
    loading: Boolean,
    devicesError: String?,
    revokingId: String?,
    badge: PcLinkBadge,
    pcState: PcLinkState,
    onRefresh: () -> Unit,
    onRevoke: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.cloud_link_linked_devices),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.cloud_link_retry))
                }
            }
            val badgeText = when (badge) {
                PcLinkBadge.LINKED -> stringResource(R.string.cloud_link_status_linked)
                PcLinkBadge.STALE -> stringResource(R.string.cloud_link_status_stale)
                PcLinkBadge.NOT_LINKED -> stringResource(R.string.cloud_link_status_not_linked)
            }
            Text(
                text = badgeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Server-derived note: Pending while the PC has not exchanged the
            // approved intent, Expired when sessions vanished server-side,
            // Offline when the last refresh failed on transport.
            val stateNote = when (pcState) {
                PcLinkState.Pending -> stringResource(R.string.cloud_link_state_pending)
                PcLinkState.Expired -> stringResource(R.string.cloud_link_state_expired)
                PcLinkState.Offline -> stringResource(R.string.cloud_link_state_offline)
                PcLinkState.Linked, PcLinkState.NotLinked -> null
            }
            if (stateNote != null) {
                Text(
                    text = stateNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pcState == PcLinkState.Expired) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }

                devicesError != null -> {
                    Text(
                        text = devicesError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                devices.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.cloud_link_no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    devices.forEach { device ->
                        LinkedDeviceRow(
                            device = device,
                            revoking = revokingId == device.id,
                            onRevoke = { onRevoke(device.id) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkedDeviceRow(
    device: CloudLinkSession,
    revoking: Boolean,
    onRevoke: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.deviceLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            val detail = listOfNotNull(device.lastSeenLabel, device.ipLabel).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SessionCountdown(expiresAt = device.effectiveExpiresAt)
        }
        if (revoking) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            IconButton(onClick = onRevoke) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cloud_link_unlink),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

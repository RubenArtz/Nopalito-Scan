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

package nopalito.app.ui.components

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nopalito.app.R
import nopalito.app.ui.Navigation
import nopalito.app.ui.screens.settings.CaptureMode

// Translucent dark "glass" used for floating controls over the camera preview.
private val GlassOverlay = Color.Black.copy(alpha = 0.35f)

// Near-black container so the camera area blends edge-to-edge in both themes.
private val CameraContainer = Color(0xFF0B0D0C)

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MyScaffold(
    navigation: Navigation,
    autoDetect: Boolean? = null,
    onAutoDetectChanged: ((Boolean) -> Unit)? = null,
    captureMode: CaptureMode? = null,
    onCaptureModeChanged: ((CaptureMode) -> Unit)? = null,
    pageListState: CommonPageListState? = null,
    bottomBar: @Composable () -> Unit,
    cameraMode: Boolean = false,
    cameraControls: (@Composable () -> Unit)? = null,
    /** Extra control rendered directly below the AutoDetect toggle in the top-left cluster. */
    topStartUnderToggle: (@Composable () -> Unit)? = null,
    /** Gradient hero header shown at the top of the screen. When set, the floating
     *  top overlay (back + action buttons) is hidden and the hero handles the
     *  status bar inset by itself. */
    heroHeader: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    val containerColor = if (cameraMode) CameraContainer else MaterialTheme.colorScheme.background
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        heroHeader?.let { it() }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (!isLandscape(LocalConfiguration.current)) {
                if (cameraMode) {
                    // Camera is the base layer, full screen (behind status/navigation bars).
                    Scaffold(
                        containerColor = containerColor,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        content(Modifier.fillMaxSize())
                    }
                    // Thumbnails + bottom actions float over the camera.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        cameraControls?.let {
                            it()
                            Spacer(Modifier.height(16.dp))
                        }
                        if (pageListState == null) {
                            bottomBar()
                        } else {
                            DocumentBar(pageListState, bottomBar, Modifier.fillMaxWidth(), cameraMode)
                        }
                    }
                } else {
                    Scaffold(
                        containerColor = containerColor,
                        contentWindowInsets =
                            if (heroHeader != null) {
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                                )
                            } else {
                                WindowInsets.safeDrawing
                            },
                        bottomBar = {
                            if (pageListState == null) {
                                bottomBar()
                            } else {
                                DocumentBar(pageListState, bottomBar, Modifier.fillMaxWidth(), cameraMode)
                            }
                        }
                    ) { innerPadding ->
                        content(
                            Modifier
                                .padding(innerPadding)
                                .fillMaxSize()
                        )
                    }
                }
            } else {
                Scaffold(
                    containerColor = containerColor,
                    contentWindowInsets = when {
                        cameraMode -> WindowInsets(0, 0, 0, 0)
                        heroHeader != null ->
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                            )

                        else -> WindowInsets.safeDrawing
                    },
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    ) {
                        content(Modifier.weight(2f))
                        if (pageListState == null) {
                            bottomBar()
                        } else {
                            val documentBarModifier =
                                if (cameraMode) Modifier.weight(1f)
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.safeDrawing)
                                else Modifier.weight(1f)
                                    .fillMaxWidth()
                            DocumentBar(pageListState, bottomBar, documentBarModifier, cameraMode)
                        }
                    }
                }
            }
            // --- Top overlay: same geometry as the gradient hero headers so the back
            // button and the action icons stay in the same position when switching
            // between the camera and the rest of the app. ---
            if (heroHeader == null) {
                HeroHeaderLayout(
                    onBack = if (navigation.shouldDisplayBackButton()) navigation.back else null,
                    backCircleColor = if (cameraMode) GlassOverlay else Color.Transparent,
                    backTint = if (cameraMode) Color.White
                    else MaterialTheme.colorScheme.onSurface,
                    leading = {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (autoDetect != null && onAutoDetectChanged != null) {
                                    AutoDetectToggle(autoDetect, onAutoDetectChanged)
                                }
                            }
                            topStartUnderToggle?.let {
                                Spacer(Modifier.height(8.dp))
                                it()
                            }
                        }
                    },
                    actions = {
                        TopActionButtons(
                            navigation = navigation,
                            tint = if (cameraMode) Color.White else MaterialTheme.colorScheme.primary,
                            circleColor = if (cameraMode) GlassOverlay
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        )
                    },
                    underActions = {
                        if (captureMode != null && onCaptureModeChanged != null) {
                            Spacer(Modifier.height(8.dp))
                            CaptureModeSelector(
                                captureMode = captureMode,
                                onCaptureModeChanged = onCaptureModeChanged,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun DocumentBar(
    pageListState: CommonPageListState,
    buttonBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    cameraMode: Boolean = false,
) {
    val isLandscape = isLandscape(LocalConfiguration.current)
    Surface(
        tonalElevation = if (cameraMode) 0.dp else 3.dp,
        shadowElevation = if (isLandscape) 0.dp else if (cameraMode) 0.dp else 12.dp,
        shape = if (isLandscape) RoundedCornerShape(0) else RoundedCornerShape(
            topStart = 28.dp,
            topEnd = 28.dp
        ),
        // On the camera the container is transparent, so the thumbnails and the action
        // row float over the preview with no dark panel behind them.
        color = if (cameraMode) Color.Transparent
        else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!pageListState.document.isEmpty()) {
                Box(
                    modifier = if (isLandscape)
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    else
                        Modifier.fillMaxWidth()
                ) {
                    CommonPageList(
                        pageListState,
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
            BottomAppBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                contentPadding = PaddingValues(0.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        buttonBar()
                    }
                }
            }
        }
    }
}

fun isLandscape(configuration: Configuration): Boolean {
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
}

@Composable
fun CaptureModeSelector(
    captureMode: CaptureMode,
    onCaptureModeChanged: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        CaptureMode.INDIVIDUAL to R.string.capture_mode_individual,
        CaptureMode.BATCH to R.string.capture_mode_batch,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .padding(4.dp)
    ) {
        options.forEach { (mode, labelRes) ->
            val selected = captureMode == mode
            Surface(
                onClick = { onCaptureModeChanged(mode) },
                shape = RoundedCornerShape(20.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                modifier = Modifier.heightIn(min = 30.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 14.dp)
                ) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
fun TopActionButtons(
    navigation: Navigation,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    /** Circle background behind each icon. Same geometry everywhere so the icons
     *  stay in the same position when switching screens. */
    circleColor: Color? = null,
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    val buttonModifier = if (circleColor != null) {
        Modifier.background(circleColor, CircleShape)
    } else {
        Modifier
    }.size(36.dp)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (circleColor != null) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tools button - opens the Tools section
        IconButton(onClick = { navigation.toToolsScreen() }, modifier = buttonModifier) {
            Icon(
                Icons.Default.Handyman,
                contentDescription = stringResource(R.string.tools),
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }

        // Cloud button (cloud icon) - opens FairScan Cloud
        IconButton(onClick = { navigation.toCloudScreen() }, modifier = buttonModifier) {
            Icon(
                Icons.Default.CloudQueue,
                contentDescription = stringResource(R.string.cloud_upload_to_cloud),
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }

        // History button (folder icon)
        IconButton(onClick = { navigation.toHistoryScreen() }, modifier = buttonModifier) {
            Icon(
                Icons.Default.Folder,
                contentDescription = stringResource(R.string.export_history),
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }

        // QR history button
        IconButton(onClick = { navigation.toQrHistoryScreen() }, modifier = buttonModifier) {
            Icon(
                Icons.Default.QrCode,
                contentDescription = stringResource(R.string.qr_history),
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }

        // Settings gear button - if available, else show overflow
        if (navigation.toSettingsScreen != null) {
            IconButton(onClick = { navigation.toSettingsScreen() }, modifier = buttonModifier) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            // Overflow menu with About only (when settings is null, e.g. external scan mode)
            Box {
                IconButton(onClick = { showOverflowMenu = true }, modifier = buttonModifier) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.menu),
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false },
                    shape = MaterialTheme.shapes.large,
                ) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        text = { Text(stringResource(R.string.about)) },
                        onClick = {
                            showOverflowMenu = false
                            navigation.toAboutScreen()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AutoDetectToggle(
    autoDetect: Boolean,
    onAutoDetectChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Icon-only toggle (same visual language as the tool buttons): the filled
    // state marks when automatic detection is active.
    Surface(
        onClick = { onAutoDetectChanged(!autoDetect) },
        shape = CircleShape,
        color = if (autoDetect) MaterialTheme.colorScheme.primary else GlassOverlay,
        contentColor = if (autoDetect) MaterialTheme.colorScheme.onPrimary else Color.White,
        modifier = modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = stringResource(R.string.auto_detect),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Shared header geometry used by both the gradient heroes and the camera overlay so
 * that the back button and the action icons stay in the same position when switching
 * screens. The status bar inset is handled here, so it must be placed at the top of
 * the screen (outside any Scaffold topBar).
 *
 * @param background gradient (e.g. green -> purple) or null for a transparent header.
 * @param leading content rendered next to the back button (title column or AutoDetect toggle).
 * @param underActions content rendered below the actions, aligned to the end (capture mode selector).
 */
@Composable
fun HeroHeaderLayout(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    background: Brush? = null,
    backCircleColor: Color = Color.White.copy(alpha = 0.22f),
    backTint: Color = Color.White,
    leading: @Composable RowScope.() -> Unit = {},
    actions: @Composable () -> Unit = {},
    underActions: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (background != null) Modifier.background(background) else Modifier
            )
            // Status bar + cutout only: safeDrawing also includes the IME inset,
            // which would push the header (and its gradient) down over the whole
            // screen when the keyboard opens.
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(backCircleColor),
                            contentAlignment = Alignment.Center,
                        ) {
                            BackButton(
                                onClick = onBack,
                                tint = backTint,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    leading()
                }
                actions()
            }
            underActions?.let {
                Box(modifier = Modifier.align(Alignment.End)) { it() }
            }
        }
    }
}

/**
 * Gradient hero banner (green -> purple) used as the header of the tools and
 * history screens: back button in a frosted circle, white title + subtitle and
 * white action buttons. It handles the status bar insets by itself, so it must
 * be placed at the top of the screen (outside any Scaffold topBar).
 */
@Composable
fun GradientHeroHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    HeroHeaderLayout(
        onBack = onBack,
        modifier = modifier,
        background = Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.tertiary,
            )
        ),
        leading = {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = actions,
    )
}

/** White frosted circle used for header actions over gradient heroes. */
@Composable
fun GradientHeroAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.22f else 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
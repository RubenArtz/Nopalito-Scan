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

package nopalito.app.ui.screens.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import nopalito.app.R

/**
 * First-run permission onboarding shown once after the language selection.
 * Walks through the permissions the app really uses one by one, explaining
 * what each one is for, and triggers the system dialog on demand.
 */
@Composable
fun PermissionsOnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Resolve the localized texts at composition time (lint-clean: never query
    // resources through LocalContext.current).
    val cameraTitle = stringResource(R.string.permission_camera_title)
    val cameraDescription = stringResource(R.string.permission_camera_description)
    val wifiTitle = stringResource(R.string.permission_wifi_title)
    val wifiDescription = stringResource(R.string.permission_wifi_description)
    val notificationsTitle = stringResource(R.string.permission_notifications_title)
    val notificationsDescription = stringResource(R.string.permission_notifications_description)
    // Push notifications need the explicit runtime permission on Android 13+.
    val notificationsPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
    // QR Wi-Fi connect needs the nearby-devices permission on Android 12+ and
    // a location permission on older versions (see QrResultDialog).
    val wifiPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    val steps = remember(wifiPermission, notificationsPermission) {
        buildList {
            add(
                PermissionStep(
                    permission = Manifest.permission.CAMERA,
                    title = cameraTitle,
                    description = cameraDescription,
                    icon = Icons.Default.PhotoCamera,
                )
            )
            add(
                PermissionStep(
                    permission = wifiPermission,
                    title = wifiTitle,
                    description = wifiDescription,
                    icon = Icons.Default.Wifi,
                )
            )
            if (notificationsPermission != null) {
                add(
                    PermissionStep(
                        permission = notificationsPermission,
                        title = notificationsTitle,
                        description = notificationsDescription,
                        icon = Icons.Default.Notifications,
                    )
                )
            }
        }
    }

    var current by remember { mutableIntStateOf(0) }
    var lastGranted by remember { mutableStateOf(true) }
    var lastDenied by remember { mutableStateOf(false) }
    val onDone = rememberUpdatedState(onComplete)

    val advance: () -> Unit = {
        if (current + 1 >= steps.size) {
            onDone.value()
        } else {
            lastDenied = false
            current += 1
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        lastGranted = granted
        lastDenied = !granted
        advance()
    }

    // Skip permissions already granted (e.g. restored by the OS on reinstall).
    LaunchedEffect(current) {
        if (current < steps.size &&
            ContextCompat.checkSelfPermission(context, steps[current].permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            lastGranted = true
            advance()
        }
    }

    val step = steps[current]
    val allow = {
        if (ContextCompat.checkSelfPermission(context, step.permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            lastGranted = true
            advance()
        } else {
            launcher.launch(step.permission)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary,
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.permissions_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = stringResource(
                        R.string.permission_step_counter,
                        current + 1,
                        steps.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.permissions_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                steps.forEachIndexed { index, _ ->
                    val active = index == current
                    val done = index < current
                    Box(
                        modifier = Modifier
                            .size(if (active) 10.dp else 8.dp)
                            .background(
                                color = when {
                                    done -> Color.White
                                    active -> Color.White
                                    else -> Color.White.copy(alpha = 0.35f)
                                },
                                shape = CircleShape,
                            )
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Permission icon in a frosted circle (same language as the rest of the app).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .background(Color.White.copy(alpha = 0.14f), CircleShape),
            ) {
                Icon(
                    imageVector = step.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = step.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = step.description,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.85f),
            )

            if (lastGranted) {
                Spacer(Modifier.height(18.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    contentColor = Color.White,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.permission_granted),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            if (lastDenied) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.permission_denied_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = allow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.permission_allow),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            TextButton(
                onClick = advance,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.permission_not_now),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}

private data class PermissionStep(
    val permission: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
)
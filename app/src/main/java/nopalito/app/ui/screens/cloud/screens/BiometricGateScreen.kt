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
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import nopalito.app.R
import nopalito.app.ui.screens.cloud.security.AndroidBiometricPromptController
import nopalito.app.ui.screens.cloud.security.BiometricPromptHost
import nopalito.app.ui.screens.cloud.security.buildBiometricPromptInfo
import nopalito.app.ui.screens.cloud.viewmodel.BiometricGateUiState
import nopalito.app.ui.screens.cloud.viewmodel.BiometricGateViewModel

/**
 * Biometric gate: the user must unlock with the OS prompt before the cloud
 * session can be used. It hosts the [BiometricPrompt] for the whole process:
 * while this screen is attached the controller is registered on
 * [BiometricPromptHost], so any component resolving a prompt (repository
 * refresh, gate) gets the real controller bound to this activity.
 */
@Composable
fun BiometricGateScreen(
    viewModel: BiometricGateViewModel,
    onUnlocked: () -> Unit,
    onUseAnotherAccount: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember { context as FragmentActivity }

    val promptInfo = remember {
        buildBiometricPromptInfo(context)
    }
    val controller = remember(activity, promptInfo) {
        AndroidBiometricPromptController(activity, promptInfo)
    }
    DisposableEffect(controller) {
        BiometricPromptHost.register(controller)
        onDispose { BiometricPromptHost.unregister(controller) }
    }

    // Navigate only on terminal states (not Idle/Prompting/Message).
    LaunchedEffect(state) {
        when (state) {
            is BiometricGateUiState.Unlocked -> onUnlocked()
            is BiometricGateUiState.KeyInvalidated -> onUseAnotherAccount()
            else -> { /* stay; UI handles the rest */
            }
        }
    }

    Box(
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
        // Top bar: circular back button, matching the other cloud screens.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 96.dp)
        ) {
            // Fingerprint emblem: replaced by the OS-prompt spinner while the
            // biometric prompt is on screen.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(128.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    )
            ) {
                if (state is BiometricGateUiState.Prompting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(56.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 5.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = stringResource(R.string.cloud_biometric_unlock_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                is BiometricGateUiState.Message -> {
                    Text(
                        text = (state as BiometricGateUiState.Message).text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                is BiometricGateUiState.Prompting -> {
                    Text(
                        text = stringResource(R.string.cloud_biometric_unlock_message),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                else -> {
                    Text(
                        text = stringResource(R.string.cloud_biometric_unlock_message),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (state !is BiometricGateUiState.Prompting) {
                Spacer(modifier = Modifier.height(36.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Button(
                            onClick = {
                                if (state is BiometricGateUiState.Message) {
                                    viewModel.dismissMessage()
                                }
                                viewModel.unlock()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.cloud_biometric_unlock_button),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = onUseAnotherAccount,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(stringResource(R.string.cloud_biometric_unlock_use_another_account))
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        TextButton(
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.back))
                        }
                    }
                }
            }
        }
    }
}
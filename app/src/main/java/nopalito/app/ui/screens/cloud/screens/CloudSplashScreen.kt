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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nopalito.app.R
import nopalito.app.ui.screens.cloud.viewmodel.CloudSplashViewModel
import nopalito.app.ui.screens.cloud.viewmodel.SplashState

@Composable
fun CloudSplashScreen(
    viewModel: CloudSplashViewModel,
    onAuthenticated: () -> Unit,
    onUnauthenticated: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    // Navigate only on terminal states (not Loading, not Error)
    // ponytail: splash -> directing navigation
    LaunchedEffect(state) {
        when (state) {
            is SplashState.Authenticated -> onAuthenticated()
            is SplashState.Unauthenticated -> onUnauthenticated()
            else -> { /* Loading and Error are handled in the UI */
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            is SplashState.Loading -> {
                LoadingContent()
            }

            is SplashState.Error -> {
                ErrorContent(
                    message = (state as SplashState.Error).message,
                    onRetry = { viewModel.checkSession() },
                    onBack = onNavigateBack
                )
            }

            is SplashState.Unauthenticated -> {
                UnauthContent(onLogin = onUnauthenticated, onBack = onNavigateBack)
            }

            // NeedsUnlock shows the same spinner until the gate takes over.
            is SplashState.NeedsUnlock -> {
                LoadingContent()
            }

            // Authenticated is handled by LaunchedEffect above
            is SplashState.Authenticated -> { /* Navigation already triggered */
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.cloud_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.cloud_checking_session),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        Text(
            text = stringResource(R.string.cloud_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier.size(width = 200.dp, height = 48.dp)
        ) {
            Text(stringResource(R.string.cloud_retry))
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.size(width = 200.dp, height = 48.dp)
        ) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
private fun UnauthContent(
    onLogin: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        Text(
            text = stringResource(R.string.cloud_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.cloud_need_login),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onLogin,
            modifier = Modifier.size(width = 200.dp, height = 48.dp)
        ) {
            Text(stringResource(R.string.cloud_login))
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.size(width = 200.dp, height = 48.dp)
        ) {
            Text(stringResource(R.string.back))
        }
    }
}
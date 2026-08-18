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

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nopalito.app.R
import nopalito.app.ui.screens.cloud.viewmodel.CloudEmailViewModel

@Composable
fun CloudEmailScreen(
    viewModel: CloudEmailViewModel,
    onLoginSuccess: (email: String) -> Unit,
    onCreateAccount: () -> Unit,
    onRecoverPassword: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isSuccess) {
        if (state.isSuccess) {
            onLoginSuccess(state.email)
            viewModel.resetState()
        }
    }

    CloudAuthScaffold(onBack = onBack) {
        CloudAuthIcon()

        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.cloud_email_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.cloud_login_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::updateEmail,
            label = { Text(stringResource(R.string.cloud_email_label)) },
            placeholder = { Text(stringResource(R.string.cloud_email_placeholder)) },
            singleLine = true,
            enabled = !state.isLoading,
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
            shape = RoundedCornerShape(16.dp),
            isError = state.errorMessage != null,
            supportingText = state.errorMessage?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        CloudPasswordTextField(
            value = state.password,
            onValueChange = viewModel::updatePassword,
            label = { Text(stringResource(R.string.cloud_password_label)) },
            placeholder = { Text(stringResource(R.string.cloud_password_placeholder)) },
            enabled = !state.isLoading,
            isError = state.errorMessage != null,
            supportingText = state.errorMessage?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            onDone = { viewModel.login() },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = viewModel::login,
            enabled = !state.isLoading,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = stringResource(R.string.cloud_login),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onRecoverPassword,
            enabled = !state.isLoading
        ) {
            Text(
                text = stringResource(R.string.cloud_forgot_password),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        TextButton(
            onClick = onCreateAccount,
            enabled = !state.isLoading
        ) {
            Text(
                text = stringResource(R.string.cloud_create_account),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    state.authDialog?.let { dialog ->
        CloudErrorDialog(
            title = dialog.title,
            message = dialog.message,
            onDismiss = viewModel::dismissDialog
        )
    }
}
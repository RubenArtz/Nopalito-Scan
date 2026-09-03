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

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import nopalito.app.ui.screens.cloud.viewmodel.SessionRecoveryViewModel

@Composable
fun SessionRecoveryScreen(
    viewModel: SessionRecoveryViewModel,
    onSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    CloudAuthScaffold(onBack = onBack) {
        CloudAuthIcon()

        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.cloud_recovery_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.cloud_recovery_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        if (!state.codeSent) {
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::updateEmail,
                label = { Text(stringResource(R.string.cloud_email_label)) },
                placeholder = { Text(stringResource(R.string.cloud_recovery_email_hint)) },
                singleLine = true,
                enabled = !state.isRequestLoading,
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                shape = RoundedCornerShape(16.dp),
                isError = state.errorMessage != null,
                supportingText = state.errorMessage?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = viewModel::requestCode,
                enabled = !state.isRequestLoading && state.email.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
            ) {
                if (state.isRequestLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = stringResource(R.string.cloud_recovery_request),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.cloud_recovery_code_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.code,
                onValueChange = viewModel::updateCode,
                label = { Text(stringResource(R.string.cloud_otp_label)) },
                placeholder = { Text(stringResource(R.string.cloud_otp_placeholder)) },
                singleLine = true,
                enabled = !state.isVerifyLoading,
                shape = RoundedCornerShape(16.dp),
                isError = state.errorMessage != null,
                supportingText = state.errorMessage?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = viewModel::verifyCode,
                enabled = !state.isVerifyLoading && state.code.length == 6,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
            ) {
                if (state.isVerifyLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = stringResource(R.string.cloud_recovery_verify),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = viewModel::resendCode,
                enabled = !state.isResending && state.resendCooldownSeconds == 0
            ) {
                val text = if (state.resendCooldownSeconds > 0) {
                    stringResource(R.string.cloud_resend_in_seconds, state.resendCooldownSeconds)
                } else {
                    stringResource(R.string.cloud_resend_code)
                }
                if (state.isResending) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    state.authDialog?.let { dialog ->
        CloudErrorDialog(
            title = dialog.title,
            message = dialog.message,
            onDismiss = viewModel::dismissDialog
        )
    }

    if (state.isSuccess) {
        CloudErrorDialog(
            title = stringResource(R.string.cloud_recovery_success_title),
            message = stringResource(R.string.cloud_recovery_success_body),
            onDismiss = {
                viewModel.consumeSuccess()
                viewModel.clear()
                onSuccess()
            }
        )
    }
}
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

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import nopalito.app.BuildConfig
import nopalito.app.R
import nopalito.app.ui.screens.cloud.data.GoogleSignInHelper
import nopalito.app.ui.screens.cloud.viewmodel.CloudEmailViewModel
import nopalito.app.ui.screens.cloud.viewmodel.CloudGoogleAuthViewModel

@Composable
fun CloudEmailScreen(
    viewModel: CloudEmailViewModel,
    onLoginSuccess: (email: String) -> Unit,
    onDirectLogin: () -> Unit = {},
    onCreateAccount: () -> Unit,
    onRecoverPassword: () -> Unit,
    onSessionRecovery: () -> Unit = {},
    onBack: () -> Unit,
    googleViewModel: CloudGoogleAuthViewModel? = null,
    onGoogleSuccess: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val googleState by (googleViewModel?.state?.collectAsState()
        ?: remember { androidx.compose.runtime.mutableStateOf(nopalito.app.ui.screens.cloud.viewmodel.GoogleAuthUiState()) })

    LaunchedEffect(state.isSuccess, state.isDirectLogin) {
        if (state.isSuccess) {
            if (state.isDirectLogin) {
                onDirectLogin()
            } else {
                onLoginSuccess(state.email)
            }
            viewModel.consumeSuccess()
        }
    }

    LaunchedEffect(googleState.isSuccess) {
        if (googleState.isSuccess) {
            onGoogleSuccess()
            googleViewModel?.consumeSuccess()
        }
    }

    val context = LocalContext.current
    val scope = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycleScope

    fun launchGoogleSignIn() {
        if (googleViewModel == null) return
        // Validate runtime configuration before Credential Manager (trimmed, non-blank)
        val trimmedClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID.trim()
        if (trimmedClientId.isBlank()) {
            Log.e("GoogleSignIn", "Google sign-in requested but GOOGLE_SERVER_CLIENT_ID is blank")
            googleViewModel.onGoogleNotConfigured()
            return
        }
        Log.d(
            "GoogleSignIn",
            "Google sign-in requested from CloudEmailScreen: clientIdConfigured=true"
        )
        scope.launch {
            try {
                val idToken = GoogleSignInHelper.requestGoogleIdToken(context)
                Log.d(
                    "GoogleSignIn",
                    "CloudEmailScreen: idTokenReceived=true - calling ViewModel signInWithGoogle"
                )
                googleViewModel.signInWithGoogle(idToken)
            } catch (e: GetCredentialCancellationException) {
                val msgLower = e.message?.lowercase() ?: ""
                if (msgLower.contains("reauth")) {
                    Log.w(
                        "GoogleSignIn",
                        "CloudEmailScreen: GetCredentialCancellationException reauth required type=${e.type} message=${e.message}",
                        e
                    )
                    googleViewModel.onGoogleReauthRequired()
                } else {
                    Log.d(
                        "GoogleSignIn",
                        "CloudEmailScreen: GetCredentialCancellationException type=${e.type} message=${e.message}",
                        e
                    )
                    googleViewModel.onGoogleCancelled()
                }
            } catch (e: NoCredentialException) {
                Log.w(
                    "GoogleSignIn",
                    "CloudEmailScreen: NoCredentialException type=${e.type} message=${e.message}",
                    e
                )
                googleViewModel.onGoogleNoAccount()
            } catch (e: GoogleIdTokenParsingException) {
                Log.e(
                    "GoogleSignIn",
                    "CloudEmailScreen: GoogleIdTokenParsingException class=${e::class.java.simpleName} message=${e.message}",
                    e
                )
                googleViewModel.onGoogleGenericError()
            } catch (e: GetCredentialException) {
                val classification = GoogleSignInHelper.classifyException(e)
                Log.e(
                    "GoogleSignIn",
                    "CloudEmailScreen: GetCredentialException classification=$classification class=${e::class.java.simpleName} type=${e.type} message=${e.message} cause=${e.cause?.javaClass?.simpleName}:${e.cause?.message}",
                    e
                )
                when (classification) {
                    "NO_CREDENTIAL" -> googleViewModel.onGoogleNoAccount()
                    "NETWORK" -> googleViewModel.onGoogleNetworkError()
                    "CONFIG", "CONFIG_BLANK" -> googleViewModel.onGoogleConfigError()
                    else -> googleViewModel.onGoogleGenericError()
                }
            } catch (e: IllegalStateException) {
                val msg = e.message?.lowercase() ?: ""
                Log.e(
                    "GoogleSignIn",
                    "CloudEmailScreen: IllegalStateException message=${e.message}",
                    e
                )
                when {
                    msg.contains("blank") -> googleViewModel.onGoogleNotConfigured()
                    msg.contains("configuration") || msg.contains("client") -> googleViewModel.onGoogleConfigError()
                    else -> googleViewModel.onGoogleGenericError()
                }
            } catch (e: Exception) {
                Log.e(
                    "GoogleSignIn",
                    "CloudEmailScreen: Unexpected exception class=${e::class.java.simpleName} message=${e.message}",
                    e
                )
                val msg = e.message?.lowercase() ?: ""
                if (msg.contains("cancel") && !msg.contains("reauth")) {
                    googleViewModel.onGoogleCancelled()
                } else if (msg.contains("reauth")) {
                    googleViewModel.onGoogleReauthRequired()
                } else {
                    googleViewModel.onGoogleGenericError()
                }
            }
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
            enabled = !state.isLoading && !googleState.isLoading,
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
            enabled = !state.isLoading && !googleState.isLoading,
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
            enabled = !state.isLoading && !googleState.isLoading,
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

        // Google Sign-In section (only when VM is provided)
        if (googleViewModel != null) {
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    thickness = DividerDefaults.Thickness,
                    color = DividerDefaults.color
                )
                Text(
                    text = stringResource(R.string.cloud_or),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    thickness = DividerDefaults.Thickness,
                    color = DividerDefaults.color
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = { launchGoogleSignIn() },
                enabled = !state.isLoading && !googleState.isLoading,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
            ) {
                if (googleState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_google),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = androidx.compose.ui.graphics.Color.Unspecified
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.cloud_continue_with_google),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Show Google-specific error below the button (distinct from email/password error)
            googleState.errorMessage?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onRecoverPassword,
            enabled = !state.isLoading && !googleState.isLoading
        ) {
            Text(
                text = stringResource(R.string.cloud_forgot_password),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        TextButton(
            onClick = onCreateAccount,
            enabled = !state.isLoading && !googleState.isLoading
        ) {
            Text(
                text = stringResource(R.string.cloud_create_account),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        TextButton(
            onClick = onSessionRecovery,
            enabled = !state.isLoading && !googleState.isLoading
        ) {
            Text(
                text = stringResource(R.string.cloud_cant_access_account),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Prominent when device limit reached — check both errorMessage and dialog
        val isTooManyDevices =
            state.errorMessage?.contains("maximum number of devices", ignoreCase = true) == true ||
                    state.authDialog?.message?.contains(
                        "maximum number of devices",
                        ignoreCase = true
                    ) == true ||
                    googleState.errorMessage?.contains(
                        "maximum number of devices",
                        ignoreCase = true
                    ) == true
        if (isTooManyDevices) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSessionRecovery,
                enabled = !state.isLoading && !googleState.isLoading,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.cloud_release_sessions),
                    style = MaterialTheme.typography.labelLarge
                )
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
    googleState.authDialog?.let { dialog ->
        CloudErrorDialog(
            title = dialog.title,
            message = dialog.message,
            onDismiss = { googleViewModel?.dismissDialog() }
        )
    }
}
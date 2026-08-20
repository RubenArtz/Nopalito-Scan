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

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.ApiException

/** Title + body for the account-level error modal. */
data class CloudAuthDialog(val title: String, val message: String)

/**
 * Maps an auth failure to a friendly modal when the backend tells us the
 * account does not exist (or the security config is misaligned). Returns null
 * to keep the regular inline error otherwise.
 */
fun authAccountDialog(e: Throwable, application: android.app.Application): CloudAuthDialog? {
    if (e !is ApiException) return null
    return when (e.code) {
        ApiException.ACCOUNT_NOT_FOUND -> CloudAuthDialog(
            application.stringFor(R.string.cloud_dialog_account_not_found_title, AppLocaleOverride.locale),
            application.stringFor(R.string.cloud_dialog_account_not_found_body, AppLocaleOverride.locale)
        )

        ApiException.INVALID_APP_SECRET -> CloudAuthDialog(
            application.stringFor(R.string.cloud_dialog_secret_title, AppLocaleOverride.locale),
            application.stringFor(R.string.cloud_dialog_secret_body, AppLocaleOverride.locale)
        )

        ApiException.AUTH_ACCOUNT_SUSPENDED,
        ApiException.AUTH_PASSWORD_RESET_BLOCKED_SUSPENDED -> CloudAuthDialog(
            application.stringFor(R.string.cloud_dialog_account_suspended_title, AppLocaleOverride.locale),
            application.stringFor(R.string.cloud_dialog_account_suspended_body, AppLocaleOverride.locale)
        )

        else -> null
    }
}

/**
 * Nice, consistent modal for account-level auth errors (e.g. "account not
 * found"). Falls back to a generic error icon when none is provided.
 */
@Composable
fun CloudErrorDialog(
    title: String,
    message: String,
    icon: ImageVector = Icons.Default.ErrorOutline,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cloud_dialog_ok))
            }
        },
    )
}
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

package nopalito.app.ui.screens.about

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import nopalito.app.BuildConfig
import nopalito.app.R
import nopalito.app.ui.components.GradientHeroHeader

/**
 * About screen: app info, developer, contact, support, license and libraries,
 * following the app's green → purple gradient style.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    aboutUiState: AboutUiState,
    onBack: () -> Unit,
    onCopyLogs: () -> Unit,
    onContactWithLastImageClicked: () -> Unit,
    onStartActivity: (Intent) -> Unit,
    onViewLibraries: () -> Unit,
) {
    val showLicenseDialog = rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        GradientHeroHeader(
            title = stringResource(R.string.about),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // App identity
            Image(
                painter = painterResource(R.drawable.icon),
                contentDescription = null,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.9f),
            )

            AboutSection(
                icon = Icons.Default.Info,
                title = stringResource(R.string.version),
            ) {
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            AboutSection(
                icon = Icons.Default.Person,
                title = stringResource(R.string.developer),
            ) {
                Text(
                    text = stringResource(R.string.developer_name),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            AboutSection(
                icon = Icons.Default.Email,
                title = stringResource(R.string.contact),
            ) {
                ContactLink(
                    icon = Icons.Default.Email,
                    text = EMAIL_ADDRESS,
                    onClick = { onStartActivity(createContactEmailIntent()) },
                )
                val websiteUrl = "https://nopalitoscan.org/"
                ContactLink(
                    icon = Icons.Default.Language,
                    text = websiteUrl,
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, websiteUrl.toUri())
                        onStartActivity(intent)
                    },
                )
            }

            AboutSection(
                icon = Icons.Default.BugReport,
                title = stringResource(R.string.support),
            ) {
                EmailImageButton(aboutUiState, onContactWithLastImageClicked)
                CopyLogsButton(onClick = onCopyLogs)
            }

            AboutSection(
                icon = Icons.Default.Description,
                title = stringResource(R.string.license),
            ) {
                Text(
                    text = stringResource(R.string.licensed_under),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.view_the_full_license),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { showLicenseDialog.value = true },
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            AboutSection(
                icon = Icons.AutoMirrored.Filled.LibraryBooks,
                title = stringResource(R.string.libraries),
            ) {
                Text(
                    text = stringResource(R.string.libraries_open_source),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.view_full_list),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable(onClick = onViewLibraries),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (showLicenseDialog.value) {
        LicenseBottomSheet(sheetState, onDismiss = { showLicenseDialog.value = false })
    }
}

/** Section with an icon header + a rounded card, matching the app's card language. */
@Composable
private fun AboutSection(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 4.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        RoundedCornerShape(9.dp)
                    )
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(14.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ContactLink(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline
            )
        )
    }
}

@Composable
private fun CopyLogsButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.copy_logs),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EmailImageButton(
    aboutUiState: AboutUiState,
    onContactWithLastImageClicked: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = aboutUiState.hasLastCapturedImage,
                onClick = onContactWithLastImageClicked,
            )
            .alpha(if (aboutUiState.hasLastCapturedImage) 1f else 0.5f)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Email,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.support_last_image),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseBottomSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val licenseText by remember {
        mutableStateOf(
            resources.openRawResource(R.raw.gpl3)
                .bufferedReader()
                .use { it.readText() }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.license),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
            }
        }

        HorizontalDivider()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = licenseText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}
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

package nopalito.app.ui.screens.settings

import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import nopalito.app.R
import nopalito.app.data.OcrLanguage
import nopalito.app.ui.components.GradientHeroHeader

enum class LanguageState {
    ACTIVE,
    INACTIVE,
    NOT_INSTALLED,
}

@Composable
fun OcrLanguagesScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onLanguageClick: (String) -> Unit,
    onRemoveLanguage: (String) -> Unit,
    onCancelOcrDownload: () -> Unit,
) {
    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        GradientHeroHeader(
            title = stringResource(R.string.settings_ocr_languages),
            subtitle = stringResource(R.string.settings_ocr_languages_subtitle),
            onBack = onBack,
            actions = {},
        )
        OcrLanguagesContent(
            uiState = uiState,
            onLanguageClick = onLanguageClick,
            onCancelOcrDownload = onCancelOcrDownload,
            onRemoveLanguage = onRemoveLanguage,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun OcrLanguagesContent(
    uiState: SettingsUiState,
    onLanguageClick: (String) -> Unit,
    onRemoveLanguage: (String) -> Unit,
    onCancelOcrDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = Locale.current.platformLocale

    val installed = remember(
        uiState.installedOcrLanguages,
        uiState.enabledOcrLanguages,
        locale,
    ) {
        uiState.installedOcrLanguages
            .map { OcrLanguage(it) }
            .sortedWith(compareBy { it.displayName(locale) })
    }

    val available = remember(
        uiState.installedOcrLanguages,
        locale,
    ) {
        OcrLanguage.AVAILABLE_LANGUAGE_CODES
            .filterNot { it in uiState.installedOcrLanguages }
            .map { OcrLanguage(it) }
            .sortedBy { it.displayName(locale) }
    }

    val suggested = available.firstOrNull {
        it.locale.displayLanguage == locale.displayLanguage
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(14.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                RoundedCornerShape(8.dp)
                            )
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.settings_ocr_download_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (installed.isNotEmpty()) {
            item {
                SectionTitle(
                    stringResource(R.string.settings_ocr_languages_installed)
                )
            }

            items(
                items = installed,
                key = { it.code }
            ) { lang ->
                LanguageItem(
                    language = lang,
                    state = if (lang.code in uiState.enabledOcrLanguages) {
                        LanguageState.ACTIVE
                    } else {
                        LanguageState.INACTIVE
                    },
                    onClick = { onLanguageClick(lang.code) },
                    onRemove = { onRemoveLanguage(lang.code) }
                )
            }
        }

        if (suggested != null) {
            item {
                SectionTitle(
                    stringResource(R.string.settings_ocr_suggested)
                )
            }

            item {
                LanguageItem(
                    language = suggested,
                    state = LanguageState.NOT_INSTALLED,
                    onClick = {
                        onLanguageClick(suggested.code)
                    }
                )
            }
        }

        item {
            SectionTitle(
                stringResource(R.string.settings_ocr_languages_available)
            )
        }

        items(
            available.filter { it != suggested },
            key = { it.code }
        ) { lang ->
            LanguageItem(
                language = lang,
                state = LanguageState.NOT_INSTALLED,
                onClick = {
                    onLanguageClick(lang.code)
                }
            )
        }
    }
    uiState.currentDownload?.let { download ->
        OcrDownloadDialog(
            state = download,
            onCancel = onCancelOcrDownload,
        )
    }
}

@Composable
private fun LanguageItem(
    language: OcrLanguage,
    state: LanguageState,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val locale = Locale.current.platformLocale

    val (leadingIcon, iconTint) = when (state) {
        LanguageState.ACTIVE ->
            Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary

        LanguageState.INACTIVE ->
            Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant

        LanguageState.NOT_INSTALLED ->
            Icons.Default.Download to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = {
                Text(
                    language.displayName(locale),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            },
            leadingContent = {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = iconTint,
                )
            },
            trailingContent = {
                if (onRemove != null) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                onClick = {
                                    menuExpanded = false
                                    onRemove()
                                }
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            ),
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

@Composable
private fun SectionTitle(
    text: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = 20.dp,
            end = 20.dp,
            top = 16.dp,
            bottom = 4.dp,
        )
    )
}

@Composable
fun OcrDownloadDialog(
    state: OcrDownloadUiState,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {}, // tapping outside the dialog should not cancel
        title = {
            Text(
                stringResource(R.string.settings_ocr_downloading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            if (state.failed) {
                Text(
                    text = stringResource(R.string.settings_ocr_download_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Column {
                    Text(
                        state.language.displayName(Locale.current.platformLocale),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(16.dp))

                    val progress =
                        state.totalBytes?.let { total ->
                            state.downloadedBytes.toFloat() / total
                        }

                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        buildProgressText(
                            state.downloadedBytes,
                            state.totalBytes,
                            LocalContext.current
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(
                    stringResource(if (state.failed) R.string.close else R.string.cancel),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        confirmButton = {},
        shape = RoundedCornerShape(28.dp),
    )
}

private fun buildProgressText(
    downloadedBytes: Long,
    totalBytes: Long?,
    context: Context,
): String {
    return if (totalBytes != null) {
        listOf(
            Formatter.formatShortFileSize(context, downloadedBytes),
            Formatter.formatShortFileSize(context, totalBytes),
        ).joinToString(" / ")
    } else {
        Formatter.formatShortFileSize(context, downloadedBytes)
    }
}
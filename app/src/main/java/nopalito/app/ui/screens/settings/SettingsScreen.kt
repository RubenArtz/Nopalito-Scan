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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nopalito.app.BuildConfig
import nopalito.app.R
import nopalito.app.data.OcrLanguage
import nopalito.app.i18n.AppLanguage
import nopalito.app.ui.Navigation
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.components.TopActionButtons

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onDefaultColorModeChanged: (DefaultColorMode) -> Unit,
    onChooseDirectoryClick: () -> Unit,
    onResetExportDirClick: () -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
    navigation: Navigation,
) {
    BackHandler { navigation.back() }
    Column(modifier = Modifier.fillMaxSize()) {
        GradientHeroHeader(
            title = stringResource(R.string.settings),
            subtitle = stringResource(R.string.settings_subtitle),
            onBack = navigation.back,
            actions = {
                TopActionButtons(
                    navigation = navigation,
                    tint = Color.White,
                    circleColor = Color.White.copy(alpha = 0.22f)
                )
            },
        )
        SettingsContent(
            uiState,
            onDefaultColorModeChanged,
            onChooseDirectoryClick,
            onResetExportDirClick,
            onLanguageSelected,
            navigation,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun SettingsContent(
    uiState: SettingsUiState,
    onDefaultColorModeChanged: (DefaultColorMode) -> Unit,
    onChooseDirectoryClick: () -> Unit,
    onResetExportDirClick: () -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
    navigation: Navigation,
    modifier: Modifier = Modifier,
) {
    val displayLocale = Locale.current.platformLocale

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // --- Language section ---
        LanguageSettingsSection(
            currentLanguage = uiState.selectedLanguage,
            cloudSyncFailed = uiState.cloudLanguageSyncFailed,
            onLanguageSelected = onLanguageSelected,
        )

        // --- Scan section ---
        SettingsSection(
            icon = Icons.Default.DocumentScanner,
            title = stringResource(R.string.settings_section_scan),
        ) {
            SingleChoiceSetting(
                title = stringResource(R.string.color_mode_default),
                entries = DefaultColorMode.entries,
                selectedValue = uiState.defaultColorMode,
                onValueChanged = { value -> onDefaultColorModeChanged(value as DefaultColorMode) },
                label = { value -> stringResource((value as DefaultColorMode).labelResource) },
            )
        }

        // --- Export section ---
        SettingsSection(
            icon = Icons.Default.Folder,
            title = stringResource(R.string.settings_section_export),
        ) {
            DirectorySettingItem(
                label = stringResource(R.string.export_directory),
                folderLabel = if (uiState.exportDirUri != null) {
                    uiState.exportDirName ?: stringResource(R.string.download_dirname)
                } else {
                    stringResource(R.string.download_dirname)
                },
                folderLabelColor = if (uiState.exportDirUri != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
                onClick = onChooseDirectoryClick,
            )

            if (uiState.exportDirUri != null) {
                TextButton(
                    onClick = onResetExportDirClick,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(start = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.reset_to_default),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // --- OCR section ---
        SettingsSection(
            icon = Icons.Default.TextFields,
            title = stringResource(R.string.settings_section_ocr),
        ) {
            SettingsRow(
                title = stringResource(R.string.settings_ocr_languages),
                subtitle = uiState.enabledOcrLanguages
                    .map { OcrLanguage(it).displayName(displayLocale) }
                    .sorted()
                    .joinToString(" • ")
                    .ifEmpty { stringResource(R.string.settings_ocr_languages_disabled) },
                onClick = { navigation.toOcrLanguagesScreen() },
            )
        }

        // --- Statistics section ---
        SettingsSection(
            icon = Icons.Default.BarChart,
            title = stringResource(R.string.stats_section_title),
        ) {
            SettingsRow(
                title = stringResource(R.string.stats_title),
                subtitle = stringResource(R.string.stats_view_usage),
                onClick = { navigation.toStatsScreen() },
            )
        }

        // --- About section ---
        SettingsSection(
            icon = Icons.Default.Info,
            title = stringResource(R.string.about),
        ) {
            SettingsRow(
                title = stringResource(R.string.about),
                subtitle = "v${BuildConfig.VERSION_NAME}",
                onClick = { navigation.toAboutScreen() },
            )
        }
    }
}

@Composable
private fun SettingsSection(
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
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    divider: Boolean = false,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (value != null) {
                Spacer(Modifier.width(12.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = valueColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp,
            )
        }
    }
}

@Composable
private fun SingleChoiceSetting(
    title: String,
    entries: List<*>,
    selectedValue: Any?,
    onValueChanged: (Any?) -> Unit,
    label: @Composable (Any?) -> String,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    SettingsRow(
        title = title,
        value = label(selectedValue),
        onClick = { showDialog = true },
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entries.forEach { entry ->
                        val isSelected = selectedValue == entry
                        val icon = when (entry) {
                            is DefaultColorMode -> when (entry) {
                                DefaultColorMode.AUTO -> Icons.Filled.AutoAwesome
                                DefaultColorMode.COLOR -> Icons.Filled.Palette
                                DefaultColorMode.GRAYSCALE -> Icons.Filled.Contrast
                            }

                            else -> null
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onValueChanged(entry)
                                    showDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (icon != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary.copy(
                                                    alpha = 0.12f
                                                )
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(8.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                }
                                Text(
                                    text = label(entry),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                )
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        onValueChanged(entry)
                                        showDialog = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {},
            shape = RoundedCornerShape(28.dp),
        )
    }
}

@Composable
fun DirectorySettingItem(
    label: String,
    folderLabel: String,
    folderLabelColor: Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.height(3.dp))

            Text(
                text = folderLabel,
                style = MaterialTheme.typography.bodySmall,
                color = folderLabelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(12.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    RoundedCornerShape(10.dp)
                )
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = stringResource(R.string.change_directory),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(Modifier.width(4.dp))

        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LanguageSettingsSection(
    currentLanguage: AppLanguage,
    cloudSyncFailed: Boolean,
    onLanguageSelected: (AppLanguage) -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    SettingsSection(
        icon = Icons.Default.Translate,
        title = stringResource(R.string.language),
    ) {
        SettingsRow(
            title = stringResource(R.string.language),
            subtitle = currentLanguage.nativeName,
            value = currentLanguage.flag,
            onClick = { showDialog = true },
        )
        if (cloudSyncFailed) {
            Text(
                text = stringResource(R.string.settings_language_sync_failed),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(
                    stringResource(R.string.language),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguage.supported.forEach { language ->
                        val isSelected = language == currentLanguage
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onLanguageSelected(language)
                                    showDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = language.flag,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontSize = 22.sp
                                )
                                Text(
                                    text = language.nativeName,
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                )
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        onLanguageSelected(language)
                                        showDialog = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {},
            shape = RoundedCornerShape(28.dp),
        )
    }
}
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

package nopalito.app.ui.screens.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LowPriority
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import nopalito.app.R
import nopalito.app.THUMBNAIL_SIZE_DP
import nopalito.app.domain.ExportQuality
import nopalito.app.ui.Navigation
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.components.NewDocumentDialog
import nopalito.app.ui.components.TopActionButtons
import nopalito.app.ui.components.isLandscape
import nopalito.app.ui.components.rememberHapticManager
import nopalito.app.ui.screens.cloud.security.AndroidBiometricPromptController
import nopalito.app.ui.screens.cloud.security.BiometricPromptHost
import nopalito.app.ui.screens.cloud.security.buildBiometricPromptInfo
import nopalito.app.ui.state.DocumentUiModel

@Composable
fun ExportScreenWrapper(
    navigation: Navigation,
    uiState: ExportUiState,
    currentDocument: DocumentUiModel,
    exportActions: ExportActions,
    onCloseScan: () -> Unit,
) {
    val onUploadToCloud = exportActions.uploadToCloud
    BackHandler { navigation.back() }

    // Host the biometric prompt while this screen is attached (same pattern
    // as the cloud screens): a NeedsUnlock session can then be unlocked right
    // here instead of forcing a detour through the Cloud tab.
    val context = LocalContext.current
    val promptController = remember(context) {
        AndroidBiometricPromptController(
            activity = context as FragmentActivity,
            promptInfo = buildBiometricPromptInfo(context),
        )
    }
    DisposableEffect(promptController) {
        BiometricPromptHost.register(promptController)
        onDispose { BiometricPromptHost.unregister(promptController) }
    }

    val showConfirmationDialog = rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        exportActions.prepareExportIfNeeded()
        exportActions.checkCloudAuth()
    }
    // Locked biometric session: run the unlock prompt once on entry.
    if (uiState.needsCloudUnlock) {
        LaunchedEffect(Unit) {
            exportActions.requestCloudUnlock()
        }
    }
    // Tactile confirmation when a save finishes or fails (UI-layer only).
    val haptics = rememberHapticManager()
    LaunchedEffect(uiState.savedBundle) {
        if (uiState.savedBundle != null) haptics.success()
    }
    LaunchedEffect(uiState.error) {
        if (uiState.error != null) haptics.error()
    }
    DisposableEffect(Unit) {
        onDispose {
            exportActions.cancelPreparationJob()
        }
    }

    val onFilenameChange = { newName: String ->
        exportActions.setFilename(newName)
    }

    ExportScreen(
        onFilenameChange = onFilenameChange,
        onFormatChange = exportActions.setFormat,
        onQualityChange = exportActions.setQuality,
        onProtectWithPasswordChange = exportActions.setProtectWithPassword,
        onPasswordChange = exportActions.setPassword,
        onGeneratePassword = exportActions.generatePassword,
        onIneExportScaleChange = exportActions.setIneExportScale,
        uiState = uiState,
        currentDocument = currentDocument,
        navigation = navigation,
        onShare = {
            if (!uiState.isSaving) {
                exportActions.share()
            }
        },
        onSave = {
            if (!uiState.isSaving) {
                exportActions.save()
            }
        },
        onOpen = exportActions.open,
        onCloseScan = {
            if (!uiState.isSaving) {
                if (uiState.hasSavedOrShared)
                    onCloseScan()
                else
                    showConfirmationDialog.value = true
            }
        },
        onUploadToCloud = onUploadToCloud,
        onToggleCloudUpload = exportActions.toggleCloudUpload,
    )

    if (showConfirmationDialog.value) {
        NewDocumentDialog(onCloseScan, showConfirmationDialog, stringResource(R.string.scan_new))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onFilenameChange: (String) -> Unit,
    onFormatChange: (ExportFormat) -> Unit,
    onQualityChange: (ExportQuality) -> Unit,
    onProtectWithPasswordChange: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onGeneratePassword: () -> Unit,
    uiState: ExportUiState,
    currentDocument: DocumentUiModel,
    navigation: Navigation,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onOpen: (ExportArtifact) -> Unit,
    onCloseScan: () -> Unit,
    onUploadToCloud: () -> Unit = {},
    onToggleCloudUpload: () -> Unit = {},
    onIneExportScaleChange: (IneExportScale) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        GradientHeroHeader(
            title = stringResource(R.string.export_as, uiState.format.displayName),
            subtitle = stringResource(R.string.export_subtitle),
            onBack = navigation.back,
            actions = {
                TopActionButtons(
                    navigation = navigation,
                    tint = Color.White,
                    circleColor = Color.White.copy(alpha = 0.22f),
                )
            },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            val containerModifier = Modifier.padding(horizontal = 16.dp)
            val onThumbnailClick = navigation.toDocumentScreen
            if (!isLandscape(LocalConfiguration.current)) {
                Column(
                    modifier = containerModifier
                        .fillMaxSize()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PdfInfosAndResultBar(uiState, currentDocument, onOpen, onThumbnailClick)
                    FormatQualitySelectors(
                        uiState.format,
                        uiState.quality,
                        onFormatChange,
                        onQualityChange,
                    )
                    if (uiState.isIneDocument) {
                        IneExportScaleSelector(
                            selected = uiState.ineExportScale,
                            onSelected = onIneExportScaleChange,
                        )
                    }
                    if (uiState.format == ExportFormat.PDF || uiState.format == ExportFormat.WORD) {
                        PasswordProtectionCard(
                            enabled = uiState.protectWithPassword,
                            password = uiState.password,
                            onEnabledChange = onProtectWithPasswordChange,
                            onPasswordChange = onPasswordChange,
                            onGenerate = onGeneratePassword,
                        )
                    }
                    MainActions(
                        onFilenameChange,
                        uiState,
                        onShare,
                        onSave,
                        onCloseScan,
                        onUploadToCloud,
                        onToggleCloudUpload
                    )
                }
            } else {
                Row(
                    modifier = containerModifier.fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        PdfInfosAndResultBar(uiState, currentDocument, onOpen, onThumbnailClick)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        FormatQualitySelectors(
                            uiState.format,
                            uiState.quality,
                            onFormatChange,
                            onQualityChange,
                        )
                        if (uiState.format == ExportFormat.PDF || uiState.format == ExportFormat.WORD) {
                            PasswordProtectionCard(
                                enabled = uiState.protectWithPassword,
                                password = uiState.password,
                                onEnabledChange = onProtectWithPasswordChange,
                                onPasswordChange = onPasswordChange,
                                onGenerate = onGeneratePassword,
                            )
                        }
                        MainActions(
                            onFilenameChange,
                            uiState,
                            onShare,
                            onSave,
                            onCloseScan,
                            onUploadToCloud,
                            onToggleCloudUpload
                        )
                    }
                }
            }

            // Preparation indicator pinned to the bottom-right corner of the
            // export window; it only shows while the export is being prepared.
            if (uiState.isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp)
                        .size(44.dp),
                    strokeWidth = 4.dp,
                )
            }
        }
    }
}

@Composable
private fun FormatQualitySelectors(
    format: ExportFormat,
    quality: ExportQuality,
    onFormatChange: (ExportFormat) -> Unit,
    onQualityChange: (ExportQuality) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // --- Format selector ---
        SectionCard {
            SectionHeader(
                icon = Icons.Default.PictureAsPdf,
                title = stringResource(R.string.export_format)
            )
            FormatSelector(format, onFormatChange)
        }

        // --- Quality/Compression selector ---
        SectionCard {
            SectionHeader(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.export_quality)
            )
            QualitySelector(quality, onQualityChange)
        }
    }
}

@Composable
private fun PasswordProtectionCard(
    enabled: Boolean,
    password: String,
    onEnabledChange: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onGenerate: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.export_protect_document),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled) {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.export_password)) },
                placeholder = { Text(stringResource(R.string.export_password_hint)) },
                singleLine = true,
                visualTransformation = if (reveal) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                imageVector = if (reveal) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = stringResource(
                                    if (reveal) R.string.export_hide_password
                                    else R.string.export_show_password
                                ),
                            )
                        }
                        IconButton(onClick = onGenerate) {
                            Icon(
                                imageVector = Icons.Default.Casino,
                                contentDescription = stringResource(R.string.export_generate_password),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FormatSelector(
    format: ExportFormat,
    onFormatChange: (ExportFormat) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(14.dp)
            )
            .padding(4.dp)
    ) {
        ExportFormat.entries.forEach { fmt ->
            val isSelected = fmt == format
            val containerColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                onClick = { onFormatChange(fmt) },
                shape = RoundedCornerShape(10.dp),
                color = containerColor,
                contentColor = contentColor,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = formatIcon(fmt),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = fmt.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun QualitySelector(
    quality: ExportQuality,
    onQualityChange: (ExportQuality) -> Unit,
) {
    val qualities = ExportQuality.entries.reversed()
    val rows = qualities.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { rowQualities ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowQualities.forEach { q ->
                    val isSelected = q == quality
                    val containerColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Card(
                        onClick = { onQualityChange(q) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = containerColor,
                            contentColor = contentColor,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = qualityIcon(q),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(q.labelResource),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                repeat(3 - rowQualities.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun qualityIcon(quality: ExportQuality): ImageVector = when (quality) {
    ExportQuality.ORIGINAL -> Icons.Default.HighQuality
    ExportQuality.HIGH -> Icons.Default.Star
    ExportQuality.BALANCED -> Icons.Default.Tune
    ExportQuality.COMPRESSED -> Icons.Default.Compress
    ExportQuality.MAX_COMPRESSION -> Icons.Default.LowPriority
}

/** INE-only selector: how large the composed credential appears on the exported sheet. */
@Composable
private fun IneExportScaleSelector(
    selected: IneExportScale,
    onSelected: (IneExportScale) -> Unit,
) {
    SectionCard {
        SectionHeader(
            icon = Icons.Default.ZoomIn,
            title = stringResource(R.string.ine_export_scale)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(14.dp)
                )
                .padding(4.dp)
        ) {
            IneExportScale.entries.forEach { scale ->
                val isSelected = scale == selected
                Surface(
                    onClick = { onSelected(scale) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else Color.Transparent,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(scale.labelResource),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private fun formatIcon(format: ExportFormat): ImageVector = when (format) {
    ExportFormat.PDF -> Icons.Default.PictureAsPdf
    ExportFormat.JPEG -> Icons.Default.Image
    ExportFormat.WORD -> Icons.Default.TextFields
}

@Composable
private fun PdfInfosAndResultBar(
    uiState: ExportUiState,
    currentDocument: DocumentUiModel,
    onOpen: (ExportArtifact) -> Unit,
    onThumbnailClick: () -> Unit,
) {
    val haptics = rememberHapticManager()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        SectionCard {
            PdfInfos(uiState, currentDocument, onThumbnailClick)
            SaveStatusBar(uiState) { artifact ->
                haptics.click()
                onOpen(artifact)
            }
        }

        ExportPreviewStrip(currentDocument)

        uiState.error?.let {
            ErrorBar(it)
        }
    }

}

/** Horizontal preview strip of every page of the scanned document. */
@Composable
private fun ExportPreviewStrip(currentDocument: DocumentUiModel) {
    val pageCount = currentDocument.pageCount()
    if (pageCount <= 0) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(List(pageCount) { it }) { index, _ ->
            ExportPreviewItem(currentDocument, index)
        }
    }
}

/** One thumbnail of the export preview strip: page image + page number badge. */
@Composable
private fun ExportPreviewItem(
    currentDocument: DocumentUiModel,
    pageIndex: Int,
) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(pageIndex) { mutableStateOf(false) }

    LaunchedEffect(pageIndex) {
        val rendered = currentDocument.thumbnail(pageIndex)
        if (rendered == null) {
            failed = true
        } else {
            bitmap?.recycle()
            bitmap = rendered
        }
    }
    DisposableEffect(pageIndex) {
        onDispose { bitmap?.recycle() }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .width(96.dp)
            .height(132.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            when {
                bmp != null -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                failed -> Text(
                    text = stringResource(R.string.ep_preview_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(6.dp),
                )

                else -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.ep_page_number, pageIndex + 1),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PdfInfos(
    uiState: ExportUiState,
    currentDocument: DocumentUiModel,
    onThumbnailClick: () -> Unit,
) {
    val result = uiState.result

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val thumbnail = currentDocument.thumbnail(0)
        thumbnail?.let {
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .heightIn(max = THUMBNAIL_SIZE_DP.dp)
                    .widthIn(max = THUMBNAIL_SIZE_DP.dp)
            ) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.clickable { onThumbnailClick() }
                )
            }
        }
        // PDF infos
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            val pageCount = result?.pageCount ?: uiState.progress?.totalPages
            pageCount?.let { count ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = pageCountText(count),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            uiState.ocrActivation?.let { activated ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (activated) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(50)
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (activated)
                            stringResource(R.string.text_recognition_enabled)
                        else
                            stringResource(R.string.text_recognition_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (uiState.isGenerating) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.creating_export),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                    uiState.progress?.let { p ->
                        if (p.totalPages == 1) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { p.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }
            } else if (result != null) {
                val context = LocalContext.current
                val formattedFileSize = formatFileSize(result.sizeInBytes, context)
                val sizeMessageKey =
                    if (result.files.size == 1) R.string.file_size else R.string.file_size_total
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(sizeMessageKey, formattedFileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveStatusBar(
    uiState: ExportUiState,
    onOpen: (ExportArtifact) -> Unit,
) {
    when {
        uiState.isSaving -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }

        uiState.savedBundle != null -> {
            SaveInfoBar(uiState.savedBundle, onOpen)
        }
    }
}

@Composable
private fun FilenameTextField(
    filename: String,
    onFilenameChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    OutlinedTextField(
        value = filename,
        onValueChange = onFilenameChange,
        label = { Text(stringResource(R.string.filename)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        trailingIcon = {
            if (filename.isNotEmpty()) {
                IconButton(onClick = {
                    onFilenameChange("")
                    focusRequester.requestFocus()
                }) {
                    Icon(Icons.Default.Clear, stringResource(R.string.clear_text))
                }
            }
        },
    )
}

@Composable
private fun MainActions(
    onFilenameChange: (String) -> Unit,
    uiState: ExportUiState,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onCloseScan: () -> Unit,
    onUploadToCloud: () -> Unit = {},
    onToggleCloudUpload: () -> Unit = {},
) {
    val haptics = rememberHapticManager()
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard {
            FilenameTextField(uiState.filename, onFilenameChange)
        }

        // Opt-in cloud upload toggle (same pattern as the tools): when
        // enabled with an active session, the export is also uploaded on save.
        // A biometric-locked session shows a fingerprint affordance; tapping
        // it runs the OS prompt and unlocks without leaving the screen.
        CloudUploadCard(
            authenticated = uiState.isCloudAuthAvailable,
            locked = uiState.needsCloudUnlock,
            checked = uiState.cloudUploadEnabled,
            onToggle = onToggleCloudUpload,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ExportButton(
                onClick = {
                    haptics.click()
                    onShare()
                },
                enabled = uiState.result != null,
                isPrimary = false,
                icon = Icons.Default.Share,
                text = stringResource(R.string.share),
                modifier = Modifier.weight(1f)
            )
            ExportButton(
                onClick = {
                    haptics.click()
                    onSave()
                },
                enabled = uiState.result != null,
                isPrimary = true,
                icon = Icons.Default.Download,
                text = stringResource(R.string.save),
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (uiState.isSaving) 0.6f else 1f)
            )
        }

        // Cloud upload section: only show if cloud session is available and file was saved
        if (uiState.isCloudAuthAvailable && uiState.savedBundle != null) {
            SectionCard {
                SectionHeader(
                    icon = Icons.Default.CloudUpload,
                    title = stringResource(R.string.cloud_upload_to_cloud)
                )

                when {
                    uiState.isUploadingToCloud -> {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                        Text(
                            text = stringResource(R.string.cloud_uploading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    uiState.cloudUploadSuccess == true -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.cloud_upload_success),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    uiState.cloudUploadError != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = uiState.cloudUploadError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    else -> {
                        Button(
                            onClick = onUploadToCloud,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.cloud_upload_now),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        // Show cloud auth hint if not authenticated and not saved yet
        if (!uiState.isCloudAuthAvailable && uiState.savedBundle != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.cloud_login_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        ExportButton(
            icon = Icons.Default.Done,
            text = stringResource(R.string.scan_new),
            onClick = onCloseScan,
            modifier = Modifier.fillMaxWidth(),
            isPrimary = uiState.hasSavedOrShared,
        )
    }
}

/**
 * Opt-in toggle for uploading the export to the cloud on save (same pattern
 * as the tools' card). The switch is disabled without an active session; with
 * a biometric-locked session ([locked]) the card is tappable and runs the
 * OS unlock prompt instead.
 */
@Composable
private fun CloudUploadCard(
    authenticated: Boolean,
    locked: Boolean = false,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = authenticated || locked) { onToggle() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = null,
                tint = if (locked) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tools_upload_cloud),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.export_cloud_upload_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!authenticated) {
                Icon(
                    imageVector = if (locked) Icons.Default.Fingerprint else Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Switch(checked = checked, onCheckedChange = null, enabled = authenticated)
        }
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}


@Composable
fun ExportButton(
    icon: ImageVector,
    text: String,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isPrimary) MaterialTheme.colorScheme.primary
        else Color.Transparent
    )
    val contentColor by animateColorAsState(
        targetValue = if (isPrimary) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.primary
    )
    val borderColor by animateColorAsState(
        targetValue = if (isPrimary) Color.Transparent
        else MaterialTheme.colorScheme.primary
    )

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        enabled = enabled,
        modifier = modifier
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SaveInfoBar(
    savedBundle: SavedBundle,
    onOpen: (ExportArtifact) -> Unit,
) {
    val dirName = savedBundle.folderName ?: savedBundle.saveDir?.name
    ?: stringResource(R.string.download_dirname)
    val items = savedBundle.items
    val nbFiles = items.size
    val firstFileName = items[0].fileName
    val artifact = remember(savedBundle) { ExportArtifactMapper.fromBundle(savedBundle) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = LocalResources.current.getQuantityString(
                        R.plurals.files_saved_to,
                        nbFiles,
                        nbFiles, firstFileName, dirName
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                when {
                    artifact.folderUri != null -> {
                        // Photo batch saved via SAF: open the container folder
                        TextButton(onClick = { onOpen(artifact) }) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.open_folder),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    nbFiles == 1 -> {
                        TextButton(onClick = { onOpen(artifact) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.open), fontWeight = FontWeight.SemiBold)
                        }
                    }

                    else -> {
                        // Photo batch without SAF (MediaStore): open the first image
                        TextButton(onClick = { onOpen(artifact) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.open), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBar(error: ExportError) {
    val (summary, details) = error.toDisplayText()
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )

                if (details != null) {
                    IconButton(
                        onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = buildString {
                                append(summary)
                                append("\n\n")
                                append(details)
                            }
                            // primaryClip is synthesized read-only (two setPrimaryClip
                            // overloads), so use the method form. Single-arg variant is
                            // deprecated on API 33+ but still functional.
                            @Suppress("DEPRECATION")
                            clipboard.setPrimaryClip(ClipData.newPlainText("Export error", text))
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.copy_logs),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (details != null) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun ExportError.toDisplayText(): Pair<String, String?> {
    return when (this) {
        is ExportError.OnPrepareOrShare -> {
            val summary = message
            summary to null
        }

        is ExportError.OnSave -> {
            val summary = stringResource(messageRes)
            val contextLines = buildErrorContextLines(saveDir)
            val details = buildString {
                if (contextLines.isNotEmpty()) {
                    append(contextLines.joinToString("\n"))
                }
            }.ifEmpty { null }

            summary to details
        }
    }
}

@Composable
private fun buildErrorContextLines(
    saveDir: SaveDir?,
): List<String> {
    val defaultDirName = stringResource(R.string.download_dirname)

    val folderLine = when {
        saveDir == null ->
            stringResource(R.string.error_context_folder, defaultDirName)

        saveDir.name != null ->
            stringResource(R.string.error_context_folder, saveDir.name)

        else -> null
    }

    val providerLine = saveDir?.uri?.authority
        ?.let { providerLabel(it, stringResource(R.string.provider_local_storage)) }
        ?.let { stringResource(R.string.error_context_provider, it) }

    return listOfNotNull(folderLine, providerLine)
}

fun providerLabel(authority: String, localStorageLabel: String): String =
    when {
        authority.contains("nextcloud", ignoreCase = true) ->
            "Nextcloud"

        authority == "com.android.externalstorage.documents" ->
            localStorageLabel

        else ->
            authority
    }

fun formatFileSize(sizeInBytes: Long?, context: Context): String {
    return if (sizeInBytes == null) context.getString(R.string.unknown_size)
    else Formatter.formatShortFileSize(context, sizeInBytes)
}

@Composable
fun pageCountText(count: Int): String =
    LocalResources.current.getQuantityString(R.plurals.page_count, count, count)

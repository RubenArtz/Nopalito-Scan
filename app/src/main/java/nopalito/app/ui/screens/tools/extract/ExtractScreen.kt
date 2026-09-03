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

package nopalito.app.ui.screens.tools.extract

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nopalito.app.R
import nopalito.app.ui.ZoomableBitmapDialog
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.components.rememberHapticManager
import nopalito.app.ui.screens.export.formatFileSize
import nopalito.app.ui.screens.tools.PickedFile
import nopalito.app.ui.screens.tools.queryDisplayName
import nopalito.app.ui.screens.tools.querySizeBytes

/**
 * Screen of the "Extract PDF pages" tool.
 *
 * Mirrors the other tools (PasswordProtect / Convert): same top bar, file
 * picker card, save-location card, message cards and share/open result flow.
 * The extra section is the lazy page preview and the range input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractScreen(
    viewModel: ExtractViewModel,
    onBack: () -> Unit,
    onGoToCloud: () -> Unit,
    onShare: (List<ExtractResult>) -> Unit,
    onOpen: (List<ExtractResult>) -> Unit,
    topBarActions: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultFileName = stringResource(R.string.tools_default_filename)
    // Tactile confirmation for result actions (share / open).
    val haptics = rememberHapticManager()

    BackHandler { onBack() }

    val singleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: defaultFileName
            if (name.substringAfterLast('.', "").lowercase() == "pdf") {
                viewModel.addFile(
                    PickedFile(
                        name = name,
                        uri = uri,
                        sizeBytes = querySizeBytes(context, uri),
                    )
                )
            } else {
                viewModel.reportInvalidFileType()
            }
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                // Not all providers support persistable grants; writing still works.
            }
            viewModel.setSaveLocation(uri.toString())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GradientHeroHeader(
            title = stringResource(R.string.tools_extract_pdf),
            subtitle = stringResource(R.string.tools_extract_pdf_desc),
            onBack = onBack,
            actions = { topBarActions() },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle(stringResource(R.string.tools_mode_individual))
            FilesCard(
                files = state.files,
                isLoading = state.isLoading,
                onPick = { singleLauncher.launch(arrayOf("application/pdf")) },
            )

            if (state.isLoading) {
                LoadingCard()
            }

            if (state.isLoaded) {
                DocumentInfoCard(
                    fileName = state.fileName,
                    pageCount = state.pageCount,
                )

                SectionTitle(stringResource(R.string.ep_preview_title))
                PagePreview(viewModel = viewModel, pageCount = state.pageCount)
                PageStrip(viewModel = viewModel, pageCount = state.pageCount)

                SignatureNoteCard()

                SectionTitle(stringResource(R.string.ep_range_label))
                RangeInputSection(
                    input = state.rangeInput,
                    onInputChange = viewModel::setRangeInput,
                    parse = state.parse,
                )

                SaveLocationCard(
                    locationName = state.saveLocationName,
                    onChange = { folderLauncher.launch(null) },
                )

                CloudUploadCard(
                    authenticated = state.isAuthenticated,
                    checked = state.cloudUploadEnabled,
                    onToggle = viewModel::toggleCloudUpload,
                )

                SectionTitle(stringResource(R.string.ep_output_name_label))
                OutlinedTextField(
                    value = state.outputFileName,
                    onValueChange = viewModel::setOutputFileName,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                SectionTitle(stringResource(R.string.ep_export_format))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.exportMode == ExtractExportMode.PDF,
                        onClick = { viewModel.setExportMode(ExtractExportMode.PDF) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text(stringResource(R.string.ep_export_pdf))
                    }
                    SegmentedButton(
                        selected = state.exportMode == ExtractExportMode.IMAGES,
                        onClick = { viewModel.setExportMode(ExtractExportMode.IMAGES) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(stringResource(R.string.ep_export_images))
                    }
                }

                if (state.exportMode == ExtractExportMode.PDF) {
                    PasswordSection(
                        enabled = state.passwordEnabled,
                        onEnabledChange = viewModel::setPasswordEnabled,
                        password = state.password,
                        onPasswordChange = viewModel::setPassword,
                        onGenerate = viewModel::generatePassword,
                    )
                }

                Button(
                    onClick = {
                        if (state.exportMode == ExtractExportMode.PDF) {
                            viewModel.exportPdf()
                        } else {
                            viewModel.exportImages()
                        }
                    },
                    enabled = !state.isProcessing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (state.exportMode == ExtractExportMode.PDF) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.ep_export_pdf),
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.ep_export_images),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (state.isProcessing) {
                    LinearProgressIndicator(
                        progress = { state.progress ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    state.progressLabel?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.results.isNotEmpty()) {
                    ResultCard(
                        context = context,
                        results = state.results,
                        passwordProtected = state.passwordEnabled && state.password.isNotBlank(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = {
                                haptics.click()
                                onShare(state.results)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.share), fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = {
                                haptics.click()
                                onOpen(state.results)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.open), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            state.infoMessages.forEach { message ->
                MessageCard(message = message, isError = false)
            }
            state.errorMessage?.let { message ->
                MessageCard(message = message, isError = true)
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (state.generateDialogVisible) {
        GeneratedPasswordDialog(
            password = state.generatedPassword,
            onUse = viewModel::acceptGeneratedPassword,
            onDismiss = viewModel::dismissGenerateDialog,
        )
    }

    if (state.premiumDialogVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPremiumDialog,
            title = { Text(stringResource(R.string.tools_upload_cloud)) },
            text = { Text(stringResource(R.string.tools_cloud_premium_required)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissPremiumDialog()
                        onGoToCloud()
                    }
                ) {
                    Text(stringResource(R.string.tools_go_login))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPremiumDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun FilesCard(
    files: List<PickedFile>,
    isLoading: Boolean,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        onClick = onPick,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (files.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                RoundedCornerShape(32.dp),
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.ep_select_pdf),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.ep_files_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                } else {
                    val file = files.first()
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            if (isLoading) R.string.ep_loading
                            else R.string.ep_tap_to_change
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!isLoading) {
                        Text(
                            text = formatFileSize(file.sizeBytes, context),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.ep_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun DocumentInfoCard(fileName: String, pageCount: Int) {
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.ep_pages_total, pageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PagePreview(viewModel: ExtractViewModel, pageCount: Int) {
    if (pageCount <= 0) return
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(List(pageCount) { it }) { index, _ ->
            PreviewPageItem(
                pageIndex = index,
                pageNumber = index + 1,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun PageStrip(viewModel: ExtractViewModel, pageCount: Int) {
    if (pageCount <= 0) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(List(pageCount) { it }) { index, _ ->
            PageStripItem(
                pageIndex = index,
                pageNumber = index + 1,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun PageStripItem(
    pageIndex: Int,
    pageNumber: Int,
    viewModel: ExtractViewModel,
) {
    var zoomPage by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    BoxWithConstraints(
        modifier = Modifier.width(96.dp),
        contentAlignment = Alignment.Center,
    ) {
        val targetWidth = constraints.maxWidth.coerceAtLeast(60)
        var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(pageIndex) { mutableStateOf(false) }

        LaunchedEffect(pageIndex, targetWidth) {
            val rendered = viewModel.renderPageForPreview(pageIndex, targetWidth)
            if (rendered == null) {
                failed = true
            } else if (bitmap != null && bitmap !== rendered) {
                // A previous render finished while this one was queued (scroll
                // recycling): drop the stale one and keep the fresh page.
                bitmap?.recycle()
                bitmap = rendered
            } else {
                bitmap = rendered
            }
        }
        DisposableEffect(pageIndex) {
            onDispose {
                // Never recycle while the zoom dialog is showing this bitmap.
                if (zoomPage == null) bitmap?.recycle()
            }
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .width(96.dp)
                .height(132.dp)
                .clickable { bitmap?.let { zoomPage = it } },
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
                        text = stringResource(R.string.ep_page_number, pageNumber),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }

    zoomPage?.let { page ->
        ZoomableBitmapDialog(bitmap = page, onDismiss = { zoomPage = null })
    }
}

@Composable
private fun PreviewPageItem(
    pageIndex: Int,
    pageNumber: Int,
    viewModel: ExtractViewModel,
) {
    var zoomPage by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val targetWidth = constraints.maxWidth.coerceAtLeast(100)
        var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(pageIndex) { mutableStateOf(false) }

        LaunchedEffect(pageIndex, targetWidth) {
            val rendered = viewModel.renderPageForPreview(pageIndex, targetWidth)
            if (rendered == null) {
                failed = true
            } else if (bitmap != null && bitmap !== rendered) {
                // A previous render finished while this one was queued (scroll
                // recycling): drop the stale one and keep the fresh page.
                bitmap?.recycle()
                bitmap = rendered
            } else {
                bitmap = rendered
            }
        }
        DisposableEffect(pageIndex) {
            onDispose {
                // Never recycle while the zoom dialog is showing this bitmap.
                if (zoomPage == null) bitmap?.recycle()
            }
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                when {
                    bmp != null -> Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { zoomPage = bmp },
                    )

                    failed -> Text(
                        text = stringResource(R.string.ep_preview_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )

                    else -> CircularProgressIndicator(
                        modifier = Modifier.padding(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ep_page_number, pageNumber),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    zoomPage?.let { page ->
        ZoomableBitmapDialog(bitmap = page, onDismiss = { zoomPage = null })
    }
}

@Composable
private fun SignatureNoteCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.ep_signatures_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RangeInputSection(
    input: String,
    onInputChange: (String) -> Unit,
    parse: PageRangeResult,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text(stringResource(R.string.ep_range_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.ep_range_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (parse) {
            is PageRangeResult.Valid -> {
                val count = parse.pages.size
                if (count > 0) {
                    Text(
                        text = stringResource(R.string.ep_range_pages_selected, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.ep_range_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is PageRangeResult.Invalid -> {
                val message = when (parse.error.kind) {
                    PageRangeErrorKind.SYNTAX -> stringResource(
                        R.string.ep_range_invalid,
                        parse.error.token
                    )

                    PageRangeErrorKind.NOT_POSITIVE -> stringResource(R.string.ep_range_zero)
                    PageRangeErrorKind.DESCENDING -> stringResource(
                        R.string.ep_range_descending,
                        parse.error.a,
                        parse.error.b
                    )

                    PageRangeErrorKind.OUT_OF_BOUNDS -> stringResource(
                        R.string.ep_range_out_of_bounds,
                        parse.error.a,
                        parse.error.totalPages
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            PageRangeResult.Empty -> Text(
                text = stringResource(R.string.ep_range_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PasswordSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    onGenerate: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ep_password_toggle),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.ep_password_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                PasswordField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.ep_password_label),
                    show = reveal,
                    onToggleShow = { reveal = !reveal },
                    trailingGenerate = onGenerate,
                )
            }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    show: Boolean,
    onToggleShow: () -> Unit,
    trailingGenerate: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
        visualTransformation = if (show) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            Row {
                IconButton(onClick = onToggleShow) {
                    Icon(
                        imageVector = if (show) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = stringResource(
                            if (show) R.string.tools_hide_password
                            else R.string.tools_show_password
                        ),
                    )
                }
                if (trailingGenerate != null) {
                    IconButton(onClick = trailingGenerate) {
                        Icon(
                            imageVector = Icons.Default.Casino,
                            contentDescription = stringResource(R.string.tools_generate_password),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GeneratedPasswordDialog(
    password: String?,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.pp_generate_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pp_generate_dialog_desc))
                if (password != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = password,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUse) {
                Text(
                    stringResource(R.string.pp_generate_dialog_use),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun SaveLocationCard(locationName: String?, onChange: () -> Unit) {
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tools_save_location),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = locationName ?: stringResource(R.string.download_dirname),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onChange) {
                Text(stringResource(R.string.tools_change_folder))
            }
        }
    }
}

@Composable
private fun CloudUploadCard(
    authenticated: Boolean,
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
                .clickable { onToggle() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.tools_upload_cloud),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = stringResource(R.string.tools_cloud_upload_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!authenticated) {
                Icon(
                    imageVector = Icons.Default.Lock,
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
private fun MessageCard(message: String, isError: Boolean) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val content = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun ResultCard(
    context: Context,
    results: List<ExtractResult>,
    passwordProtected: Boolean,
) {
    val isFolder = results.size > 1
    val totalSize = results.sumOf { it.sizeBytes }
    val headerText = if (isFolder) {
        stringResource(R.string.ep_success_images, results.size)
    } else {
        stringResource(R.string.ep_success_pdf)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = headerText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (!isFolder) {
                Text(
                    text = results.first().fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (passwordProtected) {
                Text(
                    text = stringResource(R.string.tools_password_protected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(R.string.pp_size, formatFileSize(totalSize, context)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            when {
                results.any { it.cloudUploadSuccess == false } -> Text(
                    text = results.firstNotNullOfOrNull { it.cloudUploadError }
                        ?: stringResource(R.string.cloud_error_upload),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                results.any { it.cloudUploadSuccess == true } -> Text(
                    text = stringResource(R.string.tools_cloud_uploaded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

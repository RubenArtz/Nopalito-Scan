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

package nopalito.app.ui.screens.tools.passwordprotect

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nopalito.app.R
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.screens.export.formatFileSize
import nopalito.app.ui.screens.tools.BatchMode
import nopalito.app.ui.screens.tools.OriginalFileAction
import nopalito.app.ui.screens.tools.PickedFile
import nopalito.app.ui.screens.tools.core.ToolTransfer
import nopalito.app.ui.screens.tools.queryDisplayName
import nopalito.app.ui.screens.tools.querySizeBytes
import nopalito.app.ui.screens.tools.shared.FilePreviewBatchSection
import nopalito.app.ui.screens.tools.shared.FilePreviewFailed
import nopalito.app.ui.screens.tools.shared.FilePreviewInfo
import nopalito.app.ui.screens.tools.shared.FilePreviewLoading
import nopalito.app.ui.screens.tools.shared.FilePreviewStrip
import nopalito.app.ui.screens.tools.shared.PreviewFileType

/**
 * Screen of the "Protect with password" tool.
 *
 * Does not depend on Navigation: it only receives callbacks ([onBack],
 * [onSendToCompressor], [onGoToCloud], [onShare], [onOpen]) and the
 * [PasswordProtectViewModel].
 */
@Composable
fun PasswordProtectScreen(
    viewModel: PasswordProtectViewModel,
    onBack: () -> Unit,
    onSendToCompressor: (ToolTransfer.Request) -> Unit,
    onGoToCloud: () -> Unit,
    onShare: (List<PasswordProtectResult>) -> Unit,
    onOpen: (List<PasswordProtectResult>) -> Unit,
    topBarActions: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultFileName = stringResource(R.string.tools_default_filename)

    BackHandler { onBack() }

    val singleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: defaultFileName
            if (isValidFor(state.fileType, name)) {
                viewModel.addFiles(
                    listOf(
                        PickedFile(
                            name = name,
                            uri = uri,
                            sizeBytes = querySizeBytes(context, uri),
                        )
                    )
                )
            } else {
                viewModel.reportInvalidFileType()
            }
        }
    }
    val batchLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val picked = uris.map { uri ->
                PickedFile(
                    name = queryDisplayName(context, uri) ?: defaultFileName,
                    uri = uri,
                    sizeBytes = querySizeBytes(context, uri),
                )
            }
            val valid = picked.filter { isValidFor(state.fileType, it.name) }
            if (valid.isNotEmpty()) viewModel.addFiles(valid)
            if (valid.size != picked.size) viewModel.reportInvalidFileType()
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
            title = stringResource(R.string.tools_protect_password),
            subtitle = stringResource(R.string.tools_protect_password_subtitle),
            onBack = onBack,
            actions = { topBarActions() },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle(stringResource(R.string.tools_pick_type))
            FileTypeSelector(state.fileType, viewModel::setFileType)

            SectionTitle(stringResource(R.string.tools_mode_individual))
            ModeSelector(state.batchMode, viewModel::setBatchMode)

            FilesCard(
                fileType = state.fileType,
                batchMode = state.batchMode,
                files = state.files,
                onPick = {
                    if (state.batchMode == BatchMode.BATCH) {
                        batchLauncher.launch(state.fileType.mimeTypes)
                    } else {
                        singleLauncher.launch(state.fileType.mimeTypes)
                    }
                },
            )

            if (state.files.size == 1) {
                when {
                    state.isPreviewLoading -> FilePreviewLoading()
                    state.previewFailed -> FilePreviewFailed(protected = state.previewProtected)
                    state.previewPageCount > 0 -> FilePreviewInfo(state.fileType.toPreviewType())
                }
                if (state.previewPageCount > 0) {
                    FilePreviewStrip(
                        fileType = state.fileType.toPreviewType(),
                        pageCount = state.previewPageCount,
                        render = { pageIndex, targetWidth ->
                            viewModel.renderPageForPreview(pageIndex, targetWidth)
                        },
                    )
                }
            } else if (state.previewBatch.isNotEmpty()) {
                FilePreviewBatchSection(
                    fileType = state.fileType.toPreviewType(),
                    previews = state.previewBatch,
                    renderBatch = { uriKey, pageIndex, targetWidth ->
                        viewModel.renderBatchPage(uriKey, pageIndex, targetWidth)
                    },
                )
            }

            SectionTitle(stringResource(R.string.tools_original_action))
            ActionOption(
                selected = state.originalAction == OriginalFileAction.KEEP,
                title = stringResource(R.string.tools_keep_original),
                subtitle = stringResource(R.string.pp_keep_original_desc),
                onClick = { viewModel.setOriginalAction(OriginalFileAction.KEEP) },
            )
            ActionOption(
                selected = state.originalAction == OriginalFileAction.REPLACE,
                title = stringResource(R.string.tools_replace_original),
                subtitle = stringResource(R.string.pp_replace_original_desc),
                onClick = { viewModel.setOriginalAction(OriginalFileAction.REPLACE) },
            )
            ActionOption(
                selected = state.originalAction == OriginalFileAction.COPY,
                title = stringResource(R.string.tools_save_copy),
                subtitle = stringResource(R.string.pp_save_copy_desc),
                onClick = { viewModel.setOriginalAction(OriginalFileAction.COPY) },
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

            PasswordSection(
                password = state.password,
                onPasswordChange = viewModel::setPassword,
                onGenerate = viewModel::generatePassword,
            )

            Button(
                onClick = viewModel::save,
                enabled = state.files.isNotEmpty() && !state.isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (state.isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pp_protecting))
                } else {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pp_save), fontWeight = FontWeight.SemiBold)
                }
            }

            OutlinedButton(
                onClick = {
                    val st = state
                    onSendToCompressor(
                        ToolTransfer.Request(
                            tool = st.fileType.compressTool,
                            batchMode = st.batchMode,
                            files = st.files,
                            password = st.password,
                        )
                    )
                },
                enabled = state.files.isNotEmpty() && !state.isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Compress, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.pp_send_to_compressor),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.pp_send_to_compressor_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.errorMessage?.let { message ->
                MessageCard(message = message)
            }
            if (state.results.isNotEmpty()) {
                ResultCard(
                    context = context,
                    results = state.results,
                    isBatch = state.batchMode == BatchMode.BATCH,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = { onShare(state.results) },
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
                        onClick = { onOpen(state.results) },
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
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Maps the selected file type to the shared preview type. */
private fun ProtectedFileType.toPreviewType(): PreviewFileType = when (this) {
    ProtectedFileType.PDF -> PreviewFileType.PDF
    ProtectedFileType.WORD -> PreviewFileType.WORD
}

@Composable
private fun FileTypeSelector(
    fileType: ProtectedFileType,
    onFileTypeChange: (ProtectedFileType) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ProtectedFileType.entries.forEach { type ->
            FilterChip(
                selected = fileType == type,
                onClick = { onFileTypeChange(type) },
                label = { Text(stringResource(type.titleRes)) },
                leadingIcon = {
                    Icon(
                        imageVector = type.iconFor(),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun ModeSelector(batchMode: BatchMode, onChange: (BatchMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = batchMode == BatchMode.INDIVIDUAL,
            onClick = { onChange(BatchMode.INDIVIDUAL) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) {
            Text(stringResource(R.string.tools_mode_individual))
        }
        SegmentedButton(
            selected = batchMode == BatchMode.BATCH,
            onClick = { onChange(BatchMode.BATCH) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) {
            Text(stringResource(R.string.tools_mode_batch))
        }
    }
}

@Composable
private fun FilesCard(
    fileType: ProtectedFileType,
    batchMode: BatchMode,
    files: List<PickedFile>,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    val totalSize = files.sumOf { it.sizeBytes }
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
                when {
                    files.isEmpty() -> {
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
                                imageVector = fileType.iconFor(),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = stringResource(
                                if (batchMode == BatchMode.BATCH) R.string.tools_select_files
                                else R.string.tools_select_file
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.pp_files_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }

                    batchMode == BatchMode.INDIVIDUAL -> {
                        val file = files.first()
                        Icon(
                            imageVector = fileType.iconFor(),
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
                            text = formatFileSize(file.sizeBytes, context),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Icon(
                            imageVector = fileType.iconFor(),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.tools_n_files_selected, files.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = formatFileSize(totalSize, context),
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
private fun PasswordSection(
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
                Column {
                    Text(
                        text = stringResource(R.string.pp_password_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.pp_password_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.pp_password_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (reveal) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
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
                                    if (reveal) R.string.tools_hide_password
                                    else R.string.tools_show_password
                                ),
                            )
                        }
                        IconButton(onClick = onGenerate) {
                            Icon(
                                imageVector = Icons.Default.Casino,
                                contentDescription = stringResource(R.string.tools_generate_password),
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
private fun MessageCard(message: String) {
    val container = MaterialTheme.colorScheme.errorContainer
    val content = MaterialTheme.colorScheme.onErrorContainer
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
    results: List<PasswordProtectResult>,
    isBatch: Boolean,
) {
    val totalSize = results.sumOf { it.sizeBytes }
    val anyUploadFail = results.any { it.cloudUploadSuccess == false }
    val anyUploadOk = results.any { it.cloudUploadSuccess == true }
    val uploadError = results.firstNotNullOfOrNull { it.cloudUploadError }
        ?: stringResource(R.string.cloud_error_upload)
    val headerText = if (isBatch) {
        stringResource(R.string.pp_batch_success, results.size)
    } else {
        stringResource(R.string.pp_success)
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
            Text(
                text = stringResource(R.string.tools_password_protected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (!isBatch) {
                Text(
                    text = results.first().fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.pp_size, formatFileSize(totalSize, context)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            when {
                anyUploadFail -> Text(
                    text = uploadError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                anyUploadOk -> Text(
                    text = stringResource(R.string.tools_cloud_uploaded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun ProtectedFileType.iconFor(): ImageVector = when (this) {
    ProtectedFileType.PDF -> Icons.Default.PictureAsPdf
    ProtectedFileType.WORD -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** Guards against picking a file whose extension does not match the selected type. */
private fun isValidFor(fileType: ProtectedFileType, fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in fileType.extensions
}

/**
 * Radio-card for choosing what to do with the original file (keep / replace /
 * save a copy), mirroring the compressor's options.
 */
@Composable
private fun ActionOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val border = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = container,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

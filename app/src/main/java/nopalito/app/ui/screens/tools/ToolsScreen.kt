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

package nopalito.app.ui.screens.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nopalito.app.R
import nopalito.app.ui.Navigation
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.components.TopActionButtons
import nopalito.app.ui.components.rememberHapticManager
import nopalito.app.ui.screens.export.formatFileSize
import nopalito.app.ui.screens.tools.shared.*
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ToolsScreen(
    navigation: Navigation,
    onCompressClick: () -> Unit,
    onProtectPasswordClick: () -> Unit,
    onConvertClick: () -> Unit,
    onExtractPdfClick: () -> Unit,
    onReorderPdfClick: () -> Unit,
    onDeletePagesClick: () -> Unit,
    onOrganizePagesClick: () -> Unit,
    onGenerateQrClick: () -> Unit,
) {
    BackHandler { navigation.back() }
    var hintLabel by remember { mutableStateOf<String?>(null) }
    // Saved across navigation (entering a tool and coming back) so the list
    // returns to the same scroll position instead of jumping to the top.
    val toolsScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        GradientHeroHeader(
            title = stringResource(R.string.tools),
            subtitle = stringResource(R.string.tools_subtitle),
            onBack = { navigation.back() },
            actions = {
                TopActionButtons(
                    navigation = navigation,
                    tint = Color.White,
                    circleColor = Color.White.copy(alpha = 0.22f)
                )
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(toolsScrollState)
                .padding(16.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                ToolGrid(
                    categories = listOf(
                        ToolCategory(
                            title = stringResource(R.string.tools_cat_files),
                            icon = Icons.Default.Folder,
                            accent = ToolAccent.Green,
                            tools = listOf(
                                ToolEntry(
                                    icon = Icons.Default.Compress,
                                    label = stringResource(R.string.tools_compress),
                                    description = stringResource(R.string.tools_compress_desc),
                                    onClick = onCompressClick,
                                ),
                                ToolEntry(
                                    icon = Icons.Default.Lock,
                                    label = stringResource(R.string.tools_protect_password),
                                    description = stringResource(R.string.tools_protect_password_subtitle),
                                    onClick = onProtectPasswordClick,
                                ),
                                ToolEntry(
                                    icon = Icons.Default.SwapHoriz,
                                    label = stringResource(R.string.tools_convert),
                                    description = stringResource(R.string.tools_convert_desc),
                                    requiresInternet = true,
                                    onClick = onConvertClick,
                                ),
                            ),
                        ),
                        ToolCategory(
                            title = stringResource(R.string.tools_cat_pdf),
                            icon = Icons.Default.PictureAsPdf,
                            accent = ToolAccent.Purple,
                            tools = listOf(
                                ToolEntry(
                                    icon = Icons.Default.ContentCut,
                                    label = stringResource(R.string.tools_extract_pdf),
                                    description = stringResource(R.string.tools_extract_pdf_desc),
                                    onClick = onExtractPdfClick,
                                ),
                                ToolEntry(
                                    icon = Icons.Default.SwapVert,
                                    label = stringResource(R.string.tools_reorder_pdf),
                                    description = stringResource(R.string.tools_reorder_pdf_desc),
                                    onClick = onReorderPdfClick,
                                ),
                                ToolEntry(
                                    icon = Icons.Default.DeleteSweep,
                                    label = stringResource(R.string.tools_delete_pages),
                                    description = stringResource(R.string.tools_delete_pages_desc),
                                    onClick = onDeletePagesClick,
                                ),
                                ToolEntry(
                                    icon = Icons.Default.Reorder,
                                    label = stringResource(R.string.tools_organize_pages),
                                    description = stringResource(R.string.tools_organize_pages_desc),
                                    onClick = onOrganizePagesClick,
                                ),
                            ),
                        ),
                        ToolCategory(
                            title = stringResource(R.string.tools_cat_generate),
                            icon = Icons.Default.AddCircleOutline,
                            accent = ToolAccent.Blue,
                            tools = listOf(
                                ToolEntry(
                                    icon = Icons.Default.QrCode,
                                    label = stringResource(R.string.qr_generate),
                                    description = stringResource(R.string.qr_generate_desc),
                                    requiresInternet = true,
                                    onClick = onGenerateQrClick,
                                ),
                            ),
                        ),
                    ),
                    twoColumns = maxWidth >= 600.dp,
                    hintLabel = hintLabel,
                    onHintChange = { label, visible ->
                        hintLabel = if (visible) label else null
                    },
                )
            }
        }
    }
}

/**
 * Accent tone per category: green (files), purple (PDF) and blue (generate).
 */
private enum class ToolAccent { Green, Purple, Blue }

private data class ToolAccentColors(
    val container: Color,
    val onContainer: Color,
)

@Composable
private fun accentColors(accent: ToolAccent): ToolAccentColors = when (accent) {
    ToolAccent.Green -> ToolAccentColors(
        container = MaterialTheme.colorScheme.primaryContainer,
        onContainer = MaterialTheme.colorScheme.primary,
    )

    ToolAccent.Purple -> ToolAccentColors(
        container = MaterialTheme.colorScheme.tertiaryContainer,
        onContainer = MaterialTheme.colorScheme.tertiary,
    )

    ToolAccent.Blue -> ToolAccentColors(
        container = MaterialTheme.colorScheme.secondaryContainer,
        onContainer = MaterialTheme.colorScheme.secondary,
    )
}

private data class ToolCategory(
    val title: String,
    val icon: ImageVector,
    val accent: ToolAccent,
    val tools: List<ToolEntry>,
)

private data class ToolEntry(
    val icon: ImageVector,
    val label: String,
    val description: String,
    val requiresInternet: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Tools organized in categories. Each category shows a header chip with its
 * icon + title, and its tools as cards (icon in a tonal container, name,
 * one-line description and a chevron). On wide screens the cards are laid out
 * in two columns.
 */
@Composable
private fun ToolGrid(
    categories: List<ToolCategory>,
    twoColumns: Boolean,
    hintLabel: String?,
    onHintChange: (label: String, visible: Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        categories.forEach { category ->
            val accent = accentColors(category.accent)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .background(accent.container, RoundedCornerShape(8.dp)),
                    ) {
                        Icon(
                            imageVector = category.icon,
                            contentDescription = null,
                            tint = accent.onContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent.onContainer,
                    )
                }
                if (twoColumns) {
                    category.tools.chunked(2).forEach { rowTools ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            rowTools.forEach { tool ->
                                ToolCard(
                                    icon = tool.icon,
                                    label = tool.label,
                                    description = tool.description,
                                    onClick = tool.onClick,
                                    container = accent.container,
                                    onContainer = accent.onContainer,
                                    requiresInternet = tool.requiresInternet,
                                    hintVisible = hintLabel == tool.label,
                                    onHintChange = { visible ->
                                        onHintChange(tool.label, visible)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(2 - rowTools.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    category.tools.forEach { tool ->
                        ToolCard(
                            icon = tool.icon,
                            label = tool.label,
                            description = tool.description,
                            onClick = tool.onClick,
                            container = accent.container,
                            onContainer = accent.onContainer,
                            requiresInternet = tool.requiresInternet,
                            hintVisible = hintLabel == tool.label,
                            onHintChange = { visible ->
                                onHintChange(tool.label, visible)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    container: Color,
    onContainer: Color,
    modifier: Modifier = Modifier,
    requiresInternet: Boolean = false,
    hintVisible: Boolean = false,
    onHintChange: (Boolean) -> Unit = {},
) {
    val haptics = rememberHapticManager()
    LaunchedEffect(hintVisible) {
        if (hintVisible) {
            delay(10_000.milliseconds)
            onHintChange(false)
        }
    }
    Surface(
        onClick = {
            haptics.click()
            onClick()
        },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            onContainer.copy(alpha = 0.25f),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        container.copy(alpha = 0.6f),
                        RoundedCornerShape(14.dp),
                    ),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (requiresInternet) {
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.size(32.dp)) {
                    IconButton(
                        onClick = { onHintChange(!hintVisible) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = stringResource(R.string.tools_wifi_requires_internet),
                            tint = onContainer.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (hintVisible) {
                        InternetHintPopup(onDismiss = { onHintChange(false) })
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = onContainer.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Floating hint that appears above the wifi icon: a toast-like card saying the
 * tool needs internet to work. It disappears when tapping outside or after 10 s.
 */
@Composable
private fun InternetHintPopup(onDismiss: () -> Unit) {
    val gapPx = LocalDensity.current.run { 8.dp.roundToPx() }
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = IntOffset(
                    x = anchorBounds.center.x - popupContentSize.width / 2,
                    y = anchorBounds.top - popupContentSize.height - gapPx,
                )
            }
        },
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.width(220.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.tools_wifi_requires_internet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

/**
 * Horizontal row of quick-access tools with circular icons. When [activeTool] matches a
 * tool, that circle is highlighted as selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolQuickAccessRow(
    activeTool: CompressTool?,
    onSelect: (CompressTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CompressTool.entries.forEach { tool ->
            QuickAccessItem(
                icon = tool.iconFor(),
                label = stringResource(tool.shortLabelRes),
                selected = tool == activeTool,
                onClick = { onSelect(tool) },
            )
        }
    }
}

@Composable
private fun QuickAccessItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            shadowElevation = if (selected) 6.dp else 0.dp,
            modifier = Modifier.size(48.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(width = 22.dp, height = 3.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun CompressTool.iconFor(): ImageVector = when (this) {
    CompressTool.PDF -> Icons.Default.PictureAsPdf
    CompressTool.IMAGE -> Icons.Default.Image
    CompressTool.WORD -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** Maps the selected compressor tool to the shared preview type. */
private fun CompressTool.toPreviewType(): PreviewFileType = when (this) {
    CompressTool.PDF -> PreviewFileType.PDF
    CompressTool.IMAGE -> PreviewFileType.IMAGE
    CompressTool.WORD -> PreviewFileType.WORD
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolCompressScreen(
    viewModel: ToolsViewModel,
    tool: CompressTool,
    navigation: Navigation,
    onSwitchTool: (CompressTool) -> Unit,
    onFilesPicked: (CompressTool, List<PickedFile>) -> Unit,
    onShare: (List<CompressedResult>) -> Unit,
    onOpen: (List<CompressedResult>) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultFileName = stringResource(R.string.tools_default_filename)
    val currentTool by rememberUpdatedState(tool)

    BackHandler { navigation.back() }
    LaunchedEffect(tool) { viewModel.bindTool(tool) }

    // Tactile confirmation for result actions (share / open).
    val haptics = rememberHapticManager()

    val singleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: defaultFileName
            if (isValidForTool(currentTool, name)) {
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
                val inferred = toolForFileName(name)
                if (inferred != null) {
                    onFilesPicked(
                        inferred,
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
            val valid = picked.filter { isValidForTool(currentTool, it.name) }
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
            title = stringResource(tool.titleRes),
            subtitle = stringResource(R.string.tools_compress_desc),
            onBack = { navigation.back() },
            actions = {
                TopActionButtons(
                    navigation = navigation,
                    tint = Color.White,
                    circleColor = Color.White.copy(alpha = 0.22f)
                )
            },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // Reserve exactly the measured height of the pinned thumbnail strip,
            // so the scrollable content is never hidden behind it.
            var stripHeight by remember { mutableStateOf(0.dp) }
            val showPageStrip = state.previewPageCount > 0
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (showPageStrip) stripHeight else 0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ToolQuickAccessRow(
                        activeTool = tool,
                        onSelect = onSwitchTool,
                    )

                    BatchModeSelector(state.batchMode, viewModel::setBatchMode)

                    FilePickerCard(
                        tool = tool,
                        batchMode = state.batchMode,
                        files = state.files,
                        onPick = {
                            if (state.batchMode == BatchMode.BATCH) {
                                batchLauncher.launch(mimeTypesFor(tool))
                            } else {
                                singleLauncher.launch(mimeTypesFor(tool))
                            }
                        },
                    )

                    if (state.files.size == 1) {
                        when {
                            state.isPreviewLoading -> FilePreviewLoading()
                            state.previewFailed -> FilePreviewFailed(protected = state.previewProtected)
                            state.previewPageCount > 0 -> FilePreviewInfo(tool.toPreviewType())
                        }
                    } else if (state.batchPreviews.isNotEmpty()) {
                        FilePreviewBatchSection(
                            fileType = tool.toPreviewType(),
                            previews = state.batchPreviews,
                            renderBatch = { uriKey, pageIndex, targetWidth ->
                                viewModel.renderBatchPage(uriKey, pageIndex, targetWidth)
                            },
                        )
                    }

                    CompressionLevelSection(state.level, viewModel::setLevel)
                    if (tool == CompressTool.IMAGE) {
                        Text(
                            text = stringResource(R.string.tools_image_quality_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (tool == CompressTool.PDF || tool == CompressTool.WORD) {
                        PasswordCard(
                            showOriginal = state.files.any { it.isPasswordProtected },
                            originalPassword = state.originalPassword,
                            onOriginalPasswordChange = viewModel::setOriginalPassword,
                            password = state.password,
                            onPasswordChange = viewModel::setPassword,
                            onGenerate = viewModel::generatePassword,
                        )
                    }

                    SectionTitle(stringResource(R.string.tools_original_action))
                    ActionOption(
                        selected = state.originalAction == OriginalFileAction.KEEP,
                        title = stringResource(R.string.tools_keep_original),
                        subtitle = stringResource(R.string.tools_keep_original_desc),
                        onClick = { viewModel.setOriginalAction(OriginalFileAction.KEEP) },
                    )
                    ActionOption(
                        selected = state.originalAction == OriginalFileAction.REPLACE,
                        title = stringResource(R.string.tools_replace_original),
                        subtitle = stringResource(R.string.tools_replace_original_desc),
                        onClick = { viewModel.setOriginalAction(OriginalFileAction.REPLACE) },
                    )
                    ActionOption(
                        selected = state.originalAction == OriginalFileAction.COPY,
                        title = stringResource(R.string.tools_save_copy),
                        subtitle = stringResource(R.string.tools_save_copy_desc),
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

                    Button(
                        onClick = viewModel::compress,
                        enabled = state.files.isNotEmpty() && !state.isCompressing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (state.isCompressing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.tools_compressing))
                        } else {
                            Icon(Icons.Default.Compress, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.tools_compress), fontWeight = FontWeight.SemiBold)
                        }
                    }

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
                                onClick = {
                                    haptics.click()
                                    onShare(state.results)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
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

                    Spacer(Modifier.height(8.dp))
                }
            }

            if (showPageStrip) {
                val density = LocalDensity.current
                Surface(
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            with(density) {
                                stripHeight = coords.size.height.toDp()
                            }
                        },
                ) {
                    FilePreviewStrip(
                        fileType = tool.toPreviewType(),
                        pageCount = state.previewPageCount,
                        render = { pageIndex, targetWidth ->
                            viewModel.renderPageForPreview(pageIndex, targetWidth)
                        },
                    )
                }
            }
        }
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
                        navigation.toCloudScreen()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchModeSelector(batchMode: BatchMode, onChange: (BatchMode) -> Unit) {
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
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun FilePickerCard(
    tool: CompressTool,
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
                                imageVector = tool.iconFor(),
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
                            text = stringResource(R.string.tools_file_types_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }

                    batchMode == BatchMode.INDIVIDUAL -> {
                        val file = files.first()
                        Icon(
                            imageVector = tool.iconFor(),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            text = formatFileSize(file.sizeBytes, context),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Icon(
                            imageVector = tool.iconFor(),
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
private fun CompressionLevelSection(level: CompressLevel, onLevelChange: (CompressLevel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.tools_compression_level))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            CompressLevel.entries.forEach { option ->
                FilterChip(
                    selected = level == option,
                    onClick = { onLevelChange(option) },
                    label = { Text(stringResource(option.labelRes)) },
                )
            }
        }
    }
}

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

@Composable
private fun PasswordCard(
    showOriginal: Boolean,
    originalPassword: String,
    onOriginalPasswordChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    onGenerate: () -> Unit,
) {
    var revealOriginal by remember { mutableStateOf(false) }
    var revealPassword by remember { mutableStateOf(false) }
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
                        text = stringResource(R.string.tools_password_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.tools_password_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showOriginal) {
                PasswordField(
                    value = originalPassword,
                    onValueChange = onOriginalPasswordChange,
                    label = stringResource(R.string.tools_original_password_label),
                    show = revealOriginal,
                    onToggleShow = { revealOriginal = !revealOriginal },
                )
            }
            PasswordField(
                value = password,
                onValueChange = onPasswordChange,
                label = stringResource(R.string.tools_new_password_label),
                show = revealPassword,
                onToggleShow = { revealPassword = !revealPassword },
                trailingGenerate = onGenerate,
            )
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
    results: List<CompressedResult>,
    isBatch: Boolean,
) {
    val totalOriginal = results.sumOf { it.originalSizeBytes }
    val totalOutput = results.sumOf { it.outputSizeBytes }
    val savedBytes = (totalOriginal - totalOutput).coerceAtLeast(0)
    val savedFraction = savedBytes.toFloat() / totalOriginal.coerceAtLeast(1)
    val outputFraction = 1f - savedFraction
    val savedPercent = (savedBytes * 100 / totalOriginal.coerceAtLeast(1)).toInt()
    val reducedCount = results.count { it.reduced }
    val notReducedCount = results.size - reducedCount
    val anyUploadFail = results.any { it.cloudUploadSuccess == false }
    val anyUploadOk = results.any { it.cloudUploadSuccess == true }
    val uploadError = results.firstNotNullOfOrNull { it.cloudUploadError }
        ?: stringResource(R.string.cloud_error_upload)
    val headerText = when {
        notReducedCount == 0 ->
            if (isBatch) stringResource(R.string.tools_batch_success, results.size)
            else stringResource(R.string.tools_success)

        reducedCount == 0 -> stringResource(R.string.tools_not_reduced)
        else -> stringResource(R.string.tools_partial_reduced, reducedCount, notReducedCount)
    }
    val headerColor = if (notReducedCount == 0) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.tertiary
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
                    color = headerColor,
                )
            }
            // Comparison bar: the "after" portion in primary, the saved portion
            // in tertiary; a full bar means nothing could be saved.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(outputFraction.coerceAtLeast(0.001f))
                        .background(MaterialTheme.colorScheme.primary),
                )
                if (savedFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(savedFraction)
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        R.string.tools_size_before,
                        formatFileSize(totalOriginal, context),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(
                        R.string.tools_size_after,
                        formatFileSize(totalOutput, context),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (savedBytes > 0) {
                Text(
                    text = stringResource(
                        R.string.tools_saved_amount,
                        formatFileSize(savedBytes, context),
                        savedPercent,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                Text(
                    text = stringResource(R.string.tools_not_reduced),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (results.any { it.protected }) {
                Text(
                    text = stringResource(R.string.tools_password_protected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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

/** Maps a file name to the compression tool that supports it (null when unsupported). */
private fun toolForFileName(fileName: String): CompressTool? =
    CompressTool.entries.firstOrNull { isValidForTool(it, fileName) }

private fun mimeTypesFor(tool: CompressTool): Array<String> = when (tool) {
    CompressTool.PDF -> arrayOf("application/pdf")
    CompressTool.IMAGE -> arrayOf("image/*")
    CompressTool.WORD -> arrayOf(
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )
}

/** Guards against picking a file whose extension does not match the selected tool. */
private fun isValidForTool(tool: CompressTool, fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (tool) {
        CompressTool.PDF -> ext == "pdf"
        CompressTool.IMAGE -> ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
        CompressTool.WORD -> ext in setOf("doc", "docx")
    }
}

internal fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else null
    }

internal fun querySizeBytes(context: Context, uri: Uri): Long =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0) cursor.getLong(index) else 0L
        } else 0L
    } ?: 0L

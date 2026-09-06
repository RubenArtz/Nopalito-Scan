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

package nopalito.app.ui.screens.camera

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.Navigation
import nopalito.app.ui.screens.cloud.data.CloudSessionManager
import nopalito.app.ui.screens.cloud.data.CloudSessionState
import nopalito.app.ui.screens.cloud.model.CloudFile
import nopalito.app.ui.screens.cloud.screens.CloudFileRow
import nopalito.app.ui.screens.cloud.screens.FolderBreadcrumbs
import nopalito.app.ui.screens.cloud.viewmodel.CloudFileListViewModel
import nopalito.app.ui.screens.cloud.viewmodel.CloudViewModelFactory
import nopalito.app.ui.uriForFile
import java.io.IOException

/**
 * Splits one level listing for import. Folders pass through untouched and in
 * backend order — [isImportableFile] only ever filters files, so a folder
 * named e.g. "backup.zip" is still shown and navigable. Pure and JVM-testable.
 */
internal fun partitionForImport(
    folders: List<CloudFile>,
    files: List<CloudFile>,
): Pair<List<CloudFile>, List<CloudFile>> =
    folders.toList() to files.filter { isImportableFile(it.originalName, it.mimeType) }

/**
 * Full-screen cloud file picker hosted as an overlay inside the Camera route
 * (same pattern as the import-progress overlay). It reuses the existing cloud
 * stack — [CloudSessionManager] for auth state, [CloudFileListViewModel] for
 * listing, folder navigation and cached downloads (same instance methods the
 * cloud file list uses) — then hands the downloaded file to the existing
 * [CameraViewModel.importPhotos] pipeline via a FileProvider URI. No import
 * logic and no repository are duplicated here.
 *
 * Behavior contract:
 * - Same four states as the cloud file list: loading / error+retry / empty /
 *   content. A failed load never looks like an empty browser.
 * - Folders always visible and navigable; only importable files are shown.
 * - Pagination fires on user scroll only, never during the initial load.
 * - While a download is in flight ([importingId] != null) every other row is
 *   disabled and progress shows on the selected file, so double taps and
 *   duplicate imports are impossible.
 * - Closing the browser cancels a pending download; pages are only added
 *   through [CameraViewModel.importPhotos], never partially.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudImportBrowser(
    cameraViewModel: CameraViewModel,
    navigation: Navigation,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val sessionManager = remember { CloudSessionManager.getInstance(appContext) }
    val sessionState by sessionManager.state.collectAsStateWithLifecycle()
    val listViewModel: CloudFileListViewModel = viewModel(
        key = "cloud_import_browser",
        factory = CloudViewModelFactory(appContext as Application),
    )
    val listState by listViewModel.state.collectAsStateWithLifecycle()

    var importingId by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    fun closeSafely() {
        downloadJob?.cancel()
        downloadJob = null
        onClose()
    }
    DisposableEffect(Unit) {
        onDispose { downloadJob?.cancel() }
    }

    // Back climbs folders first; only at root does it leave the browser.
    BackHandler {
        if (importingId != null) return@BackHandler
        if (listState.folderStack.isNotEmpty()) {
            listViewModel.navigateToLevel(listState.folderStack.size - 2)
        } else {
            closeSafely()
        }
    }

    fun startImport(file: CloudFile) {
        if (importingId != null) return
        if (file.itemType == "folder") return
        // Final validation, even though the list is already pre-filtered.
        if (!isImportableFile(file.originalName, file.mimeType)) return
        importingId = file.id
        importError = null
        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                // Same cached-download entry point the cloud file list uses.
                val result = withContext(Dispatchers.IO) {
                    listViewModel.downloadForCache(file)
                }
                val downloaded = result.getOrNull()
                // Re-validate the downloaded payload: real bytes, importable type.
                if (downloaded == null || !downloaded.exists() || downloaded.length() <= 0L ||
                    !isImportableFile(downloaded.name, null)
                ) {
                    throw IOException("Downloaded file is not importable: ${file.originalName}")
                }
                ensureActive()
                val uri = uriForFile(context, downloaded)
                // Hand off to the existing pipeline (same as device import).
                cameraViewModel.importPhotos(listOf(uri))
                importingId = null
                // Close only once the import handoff has started; the camera
                // import-progress overlay takes over from here.
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                importingId = null
                importError = appContext.stringFor(
                    R.string.cloud_download_error,
                    AppLocaleOverride.locale,
                )
            } finally {
                downloadJob = null
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (listState.folderStack.isNotEmpty() && importingId == null) {
                        listViewModel.navigateToLevel(listState.folderStack.size - 2)
                    } else {
                        closeSafely()
                    }
                }) {
                    Icon(
                        imageVector = if (listState.folderStack.isEmpty()) {
                            Icons.Filled.Close
                        } else {
                            Icons.AutoMirrored.Filled.ArrowBack
                        },
                        contentDescription = null,
                    )
                }
                Text(
                    text = stringResource(R.string.import_source_cloud),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { listViewModel.refresh() },
                    enabled = importingId == null,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
            }

            when (val session = sessionState) {
                is CloudSessionState.Checking -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is CloudSessionState.Authenticated -> {
                    CloudImportContent(
                        listViewModel = listViewModel,
                        importingId = importingId,
                        importError = importError,
                        onOpenFolder = { listViewModel.openFolder(it) },
                        onImportFile = ::startImport,
                        onClearImportError = { importError = null },
                    )
                }

                // No session, locked, or failed: show the real state with a
                // path to sign in — never an empty file list.
                is CloudSessionState.Unauthenticated,
                is CloudSessionState.NeedsUnlock,
                is CloudSessionState.Error,
                    -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.import_cloud_no_session),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (session is CloudSessionState.Error) {
                                session.message
                            } else {
                                stringResource(R.string.import_cloud_sign_in_hint)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                        // Existing auth flow; returning here refreshes the
                        // session state automatically, no Camera restart needed.
                        Button(onClick = { navigation.toCloudScreen() }) {
                            Text(stringResource(R.string.cloud_login))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudImportContent(
    listViewModel: CloudFileListViewModel,
    importingId: String?,
    importError: String?,
    onOpenFolder: (CloudFile) -> Unit,
    onImportFile: (CloudFile) -> Unit,
    onClearImportError: () -> Unit,
) {
    val listState by listViewModel.state.collectAsStateWithLifecycle()
    // Same separation contract as the cloud file list: folders untouched and
    // first, backend order preserved inside each group.
    val (folders, files) = remember(listState.folders, listState.files) {
        partitionForImport(listState.folders, listState.files)
    }
    val hasContent = folders.isNotEmpty() || files.isNotEmpty()

    // Same pull-to-refresh contract as the cloud file list: swipe down
    // anywhere reloads the current level through the shared ViewModel.
    PullToRefreshBox(
        isRefreshing = listState.isRefreshing,
        onRefresh = { listViewModel.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Shared breadcrumbs (no create-folder action in the import flow).
            FolderBreadcrumbs(
                path = listState.folderStack,
                onNavigate = { listViewModel.navigateToLevel(it) },
                onNewFolder = null,
            )

            Box(modifier = Modifier.weight(1f)) {
                // Same four states as the cloud file list: a failed load shows
                // error + retry, an empty folder shows the empty state — a bare
                // list is never rendered for either case.
                val hasListContent = listState.files.isNotEmpty() || listState.folders.isNotEmpty()
                when {
                    listState.isLoading && !hasListContent -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    listState.errorMessage != null && !hasListContent -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline, null, Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = listState.errorMessage
                                    ?: stringResource(R.string.error_occurred),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { listViewModel.refresh() }) {
                                Text(stringResource(R.string.cloud_retry))
                            }
                        }
                    }

                    !hasListContent -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Filled.CloudOff, null, Modifier.size(80.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.cloud_no_files),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.cloud_empty_files),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (importError != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = importError,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = onClearImportError) {
                                        Text(stringResource(R.string.retry))
                                    }
                                }
                            }
                            CloudImportList(
                                listViewModel = listViewModel,
                                folders = folders,
                                files = files,
                                importingId = importingId,
                                onOpenFolder = onOpenFolder,
                                onImportFile = onImportFile,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudImportList(
    listViewModel: CloudFileListViewModel,
    folders: List<CloudFile>,
    files: List<CloudFile>,
    importingId: String?,
    onOpenFolder: (CloudFile) -> Unit,
    onImportFile: (CloudFile) -> Unit,
) {
    val listState by listViewModel.state.collectAsStateWithLifecycle()
    val listScroll = rememberLazyListState()
    // Pagination on user scroll only (same rule as the cloud file list), plus
    // !isLoading so nothing fires while the initial load is still running.
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listScroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listScroll.layoutInfo.totalItemsCount
            lastVisible >= total - 6 && listState.hasMore &&
                    !listState.isLoadingMore && !listState.isLoading
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) listViewModel.loadMore() }

    LazyColumn(
        state = listScroll,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Folders of the current level, always above the files.
        if (folders.isNotEmpty()) {
            item(key = "import-folders-header") {
                Text(
                    text = stringResource(R.string.cloud_folders),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
            items(folders, key = { "import-folder-${it.id}" }) { folder ->
                FolderImportRow(
                    folder = folder,
                    enabled = importingId == null,
                    onClick = { onOpenFolder(folder) },
                )
            }
            item(key = "import-folders-divider") {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )
            }
        }
        items(files, key = { "import-file-${it.id}" }) { file ->
            CloudFileRow(
                file = file,
                downloadForCache = listViewModel::downloadForCache,
                onClick = { onImportFile(file) },
                enabled = importingId == null,
                modifier = Modifier.alpha(
                    if (importingId != null && importingId != file.id) 0.5f else 1f
                ),
                footerContent = {
                    if (importingId == file.id) {
                        // Indeterminate: the cached download reports no byte
                        // progress; the camera import overlay covers the handoff.
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                },
            )
        }
        if (listState.isLoadingMore) {
            item(key = "import-loading-more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun FolderImportRow(
    folder: CloudFile,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = folder.originalName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
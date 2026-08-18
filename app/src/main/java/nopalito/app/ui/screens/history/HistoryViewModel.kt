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

package nopalito.app.ui.screens.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nopalito.app.AppContainer
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.DownloadLocation
import nopalito.app.ui.uriForFile
import java.io.File

data class HistoryUiState(
    val history: List<ExportHistoryEntity> = emptyList(),
    val searchQuery: String = "",
    val sortBy: SortOption = SortOption.DATE_DESC,
    val filterFormat: String? = null, // null = all, "PDF", "JPEG"
    val isLoading: Boolean = false,
)

enum class SortOption {
    DATE_DESC,
    DATE_ASC,
    NAME_ASC,
    NAME_DESC,
    SIZE_ASC,
    SIZE_DESC,
}

class HistoryViewModel(appContainer: AppContainer) : ViewModel() {
    private val repository = appContainer.historyRepository

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _sortBy = MutableStateFlow(SortOption.DATE_DESC)
    private val _filterFormat = MutableStateFlow<String?>(null)

    init {
        observeHistory()
    }

    private fun observeHistory() {
        viewModelScope.launch {
            combine(
                _searchQuery,
                _sortBy,
                _filterFormat
            ) { query, sort, format -> Triple(query, sort, format) }
                .collect { (query, sort, format) ->
                    _uiState.value = _uiState.value.copy(isLoading = true)
                    val flow = when {
                        query.isNotBlank() -> repository.searchByName(query)
                        format != null -> repository.filterByFormat(format)
                        else -> getSortedFlow(sort)
                    }
                    flow.collect { list ->
                        _uiState.value = _uiState.value.copy(
                            history = list,
                            searchQuery = query,
                            sortBy = sort,
                            filterFormat = format,
                            isLoading = false
                        )
                    }
                }
        }
    }

    private fun getSortedFlow(sort: SortOption): Flow<List<ExportHistoryEntity>> {
        return when (sort) {
            SortOption.DATE_DESC -> repository.getAllHistory()
            SortOption.DATE_ASC -> error("No direct DAO, fallback to all")
            SortOption.NAME_ASC -> repository.getAllSortedByNameAsc()
            SortOption.NAME_DESC -> repository.getAllSortedByNameDesc()
            SortOption.SIZE_ASC -> repository.getAllSortedBySizeAsc()
            SortOption.SIZE_DESC -> repository.getAllSortedBySizeDesc()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortBy(sort: SortOption) {
        _sortBy.value = sort
    }

    fun setFilterFormat(format: String?) {
        _filterFormat.value = format
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            val entity = repository.getById(id)
            entity?.let { deleteBackup(it) }
            repository.deleteById(id)
        }
    }

    /** Deletes several history entries (backup files + DB rows) in one batch. */
    fun deleteHistoryItems(items: List<ExportHistoryEntity>, context: Context) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                items.forEach { item ->
                    // Folder entries: remove their exported images and SAF folder (best-effort).
                    if (item.resultType == ExportHistoryEntity.RESULT_TYPE_FOLDER) {
                        item.childrenUris?.split("\n")?.forEach { uriStr ->
                            runCatching { context.contentResolver.delete(uriStr.toUri(), null, null) }
                        }
                        item.exportedFolderUri?.let { folderUriStr ->
                            runCatching {
                                val uri = folderUriStr.toUri()
                                if (uri.authority == "com.android.externalstorage.documents") {
                                    context.contentResolver.delete(uri, null, null)
                                }
                            }
                        }
                    }
                    deleteBackup(item)
                }
            }
            items.forEach { repository.deleteById(it.id) }
        }
    }

    private suspend fun deleteBackup(entity: ExportHistoryEntity) {
        withContext(Dispatchers.IO) {
            entity.backupPath?.let { File(it).delete() }
            entity.backupDirPath?.let { File(it).deleteRecursively() }
        }
    }

    /**
     * Deletes a multiple export from the history and removes its physical
     * files (exported images) and the container folder (best-effort).
     * MediaStore/SAF uris are deleted via ContentResolver.
     */
    fun deleteExportFolder(item: ExportHistoryEntity, context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                item.childrenUris
                    ?.split("\n")
                    ?.forEach { uriStr ->
                        runCatching { context.contentResolver.delete(uriStr.toUri(), null, null) }
                    }
                val folderUriStr = item.exportedFolderUri
                if (!folderUriStr.isNullOrBlank()) {
                    runCatching {
                        val uri = folderUriStr.toUri()
                        // Only delete real SAF folders (DocumentFile). The synthetic
                        // MediaStore (externalstorage) uri does not represent
                        // a deletable folder and is silently ignored.
                        if (uri.authority == "com.android.externalstorage.documents") {
                            context.contentResolver.delete(uri, null, null)
                        }
                    }
                }
                deleteBackup(item)
            }
            repository.deleteById(item.id)
        }
    }

    /**
     * Deletes a single image of a multi-image export: its exported file
     * (MediaStore/SAF), the private backup copy and the persisted children
     * list. The folder row stays (unless it was the only image).
     */
    fun deleteFolderChild(item: ExportHistoryEntity, childName: String, childUri: Uri?, context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (childUri != null && childUri.scheme != "file") {
                    runCatching { context.contentResolver.delete(childUri, null, null) }
                }
                // Backup dir is the source of truth when present.
                item.backupDirPath?.let { dir ->
                    File(dir, childName).delete()
                }
                // Otherwise persist the updated childrenUris.
                if (item.backupDirPath == null) {
                    val updated = item.childrenUris
                        ?.split("\n")
                        ?.filter { it != childUri?.toString() }
                        ?.joinToString("\n")
                    if (updated != null && updated != item.childrenUris) {
                        repository.update(item.copy(childrenUris = updated.ifEmpty { null }))
                    }
                }
            }
        }
    }

    /**
     * Re-downloads an entry from its private backup to the configured download
     * folder (or Downloads/Nopalito Scan when none is set). Shows a toast with
     * the result.
     */
    fun exportHistory(context: Context, item: ExportHistoryEntity) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val files = resolveSourceFiles(context, item)
                if (files.isEmpty()) return@withContext false
                val mimeType = mimeTypeFor(item.format)
                val saved = files.count {
                    DownloadLocation.saveStream(
                        context = context,
                        displayName = it.name,
                        mimeType = mimeType,
                        totalBytes = it.length(),
                        openInput = it::inputStream
                    ) != null
                }
                saved > 0
            }
            val message = if (ok) {
                context.stringFor(R.string.history_downloaded, AppLocaleOverride.locale)
            } else {
                context.stringFor(R.string.export_failed, AppLocaleOverride.locale)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Downloads a single image of a multi-image export to the download folder. */
    fun downloadHistoryChild(
        context: Context,
        childUri: Uri?,
        backupFile: File?,
    ) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val source = backupFile?.takeIf { it.exists() }
                    ?: childUri?.let { resolveUriToFile(context, it) }
                    ?: return@withContext false
                DownloadLocation.saveStream(
                    context = context,
                    displayName = source.name,
                    mimeType = "image/jpeg",
                    totalBytes = source.length(),
                    openInput = source::inputStream
                ) != null
            }
            val message = if (ok) {
                context.stringFor(R.string.history_downloaded, AppLocaleOverride.locale)
            } else {
                context.stringFor(R.string.export_failed, AppLocaleOverride.locale)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Shares the exported file/folder via the system share sheet. */
    fun shareHistory(context: Context, item: ExportHistoryEntity) {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { resolveSourceFiles(context, item) }
            if (files.isEmpty()) {
                Toast.makeText(
                    context,
                    context.stringFor(R.string.export_failed, AppLocaleOverride.locale),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val uris = files.map { uriForFile(context, it) }
            val intent = Intent().apply {
                action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
                type = mimeTypeFor(item.format)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(
                        intent,
                        context.stringFor(R.string.share_document, AppLocaleOverride.locale)
                    )
                )
            }
        }
    }

    private fun resolveSourceFiles(context: Context, item: ExportHistoryEntity): List<File> {
        // Folder backup dir.
        item.backupDirPath?.let { dir ->
            val backupDir = File(dir)
            if (backupDir.isDirectory) {
                backupDir.listFiles()?.toList()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        // Single file backup.
        item.backupPath?.let { path ->
            val file = File(path)
            if (file.exists()) return listOf(file)
        }
        // Fallback to the exported files (MediaStore/SAF), materialized to temp.
        val uriStrings = if (item.backupDirPath != null)
            item.childrenUris?.split("\n") ?: emptyList()
        else
            listOfNotNull(item.exportedFilePath)
        return uriStrings.mapNotNull { str ->
            runCatching {
                val uri = str.toUri()
                if (uri.scheme == "file") File(uri.path!!) else resolveUriToFile(context, uri)
            }.getOrNull()
        }
    }

    private fun resolveUriToFile(context: Context, uri: Uri): File {
        val dir = File(context.cacheDir, "history_export_tmp").apply { mkdirs() }
        val tmp = File.createTempFile("export_", ".bin", dir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        return tmp
    }

    private fun mimeTypeFor(format: String): String = when (format) {
        "PDF" -> "application/pdf"
        "DOCX" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> "image/jpeg"
    }
}
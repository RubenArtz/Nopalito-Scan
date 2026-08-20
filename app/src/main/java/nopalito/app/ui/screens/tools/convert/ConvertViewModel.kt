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

package nopalito.app.ui.screens.tools.convert

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nopalito.app.AppContainer
import nopalito.app.R
import nopalito.app.data.ExportNames
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.*
import nopalito.app.ui.screens.cloud.model.ConversionItem
import nopalito.app.ui.screens.history.ExportHistoryEntity
import nopalito.app.ui.screens.tools.BatchFolder
import nopalito.app.ui.screens.tools.BatchMode
import nopalito.app.ui.screens.tools.OriginalFileAction
import nopalito.app.ui.screens.tools.PickedFile
import nopalito.app.ui.screens.tools.passwordprotect.PasswordProtectEngine
import nopalito.app.ui.screens.tools.passwordprotect.ProtectedFileType
import nopalito.app.ui.screens.tools.shared.FilePreviewController
import nopalito.app.ui.screens.tools.shared.PasswordGenerator
import nopalito.app.ui.screens.tools.shared.PreviewFileType
import nopalito.app.ui.screens.tools.shared.ToolOutputSaver
import nopalito.app.ui.uriForFile
import java.io.File

class ConvertViewModel(container: AppContainer) : ViewModel() {

    @SuppressLint("StaticFieldLeak")
    private val context: Context = container.applicationContext
    private val settingsRepository = container.settingsRepository
    private val cloudSessionManager: CloudSessionManager = container.cloudSessionManager
    private val cloudConversionRepository = CloudConversionRepository(context)
    private val cloudRepository = CloudRepository(context)
    private val historyRepository = container.historyRepository
    private val logger = container.logger
    private val exportsBackupDir = container.exportsBackupDir
    private val toolsDir: File = File(context.cacheDir, "tools").apply { mkdirs() }
    private val saver = ToolOutputSaver(context)

    /** Shared read-only file preview (single file strip + batch strips). */
    private val preview = FilePreviewController(context, logger, viewModelScope)

    private val _state = MutableStateFlow(ConvertUiState())
    val state: StateFlow<ConvertUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val uri = settingsRepository.exportDirUri.first()
            val name = uri?.let { settingsRepository.resolveExportDirName(it) }
            _state.update { it.copy(saveLocationUri = uri, saveLocationName = name) }
        }
        viewModelScope.launch {
            cloudSessionManager.state.collect { sessionState ->
                _state.update {
                    it.copy(isAuthenticated = sessionState is CloudSessionState.Authenticated)
                }
            }
        }
        // Mirror the shared preview state into this tool's UiState. Every input
        // document is converted by the backend, so the preview always uses the
        // backend path (Word semantics).
        viewModelScope.launch {
            preview.state.collect { pv ->
                _state.update {
                    it.copy(
                        previewPageCount = pv.singlePageCount,
                        isPreviewLoading = pv.isLoading,
                        previewFailed = pv.failed,
                        previewProtected = pv.protected,
                        previewBatch = pv.batch,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        preview.clear()
        super.onCleared()
    }

    /** Renders one page of the single-file preview (see [FilePreviewController]). */
    suspend fun renderPageForPreview(pageIndex: Int, targetWidthPx: Int): Bitmap? =
        preview.renderPage(pageIndex, targetWidthPx)

    /** Renders one page of a batch file preview (see [FilePreviewController]). */
    suspend fun renderBatchPage(uriKey: String, pageIndex: Int, targetWidthPx: Int): Bitmap? =
        preview.renderBatchPage(uriKey, pageIndex, targetWidthPx)

    fun setBatchMode(mode: BatchMode) {
        preview.clear()
        _state.update {
            it.copy(
                batchMode = mode,
                files = emptyList(),
                cached = emptyList(),
                results = emptyList(),
                phase = ConvertPhase.SELECTING,
                pendingAction = null,
                historyRecorded = false,
                errorMessage = null,
            )
        }
    }

    fun addFiles(files: List<PickedFile>) {
        _state.update {
            it.copy(
                files = files,
                cached = emptyList(),
                results = emptyList(),
                phase = ConvertPhase.SELECTING,
                pendingAction = null,
                historyRecorded = false,
                errorMessage = null,
            )
        }
        preview.prepare(files, PreviewFileType.WORD)
    }

    fun setOriginalAction(action: OriginalFileAction) {
        _state.update {
            it.copy(
                originalAction = action,
                cached = emptyList(),
                results = emptyList(),
                pendingAction = null,
                historyRecorded = false,
                errorMessage = null,
            )
        }
    }

    fun reportInvalidFileType() {
        _state.update {
            it.copy(
                errorMessage = context.stringFor(R.string.cv_invalid_file_type, AppLocaleOverride.locale),
                results = emptyList(),
            )
        }
    }

    fun setSaveLocation(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setExportDirUri(uri)
            val name = uri?.let { settingsRepository.resolveExportDirName(it) }
            _state.update {
                it.copy(saveLocationUri = uri, saveLocationName = name, results = emptyList())
            }
        }
    }

    /**
     * Server-side DOC → PDF conversion (LibreOffice backend):
     * upload → poll job → download PDFs. The produced PDFs are kept in the
     * app cache ([ConvertUiState.cached]); they are written to the selected
     * destination only when the user presses Save, Share or Open.
     */
    fun convert() {
        val st = _state.value
        if (st.files.isEmpty() || st.isConverting) return

        _state.update {
            it.copy(
                isConverting = true,
                phase = ConvertPhase.UPLOADING,
                progress = null,
                convertedCount = 0,
                cached = emptyList(),
                results = emptyList(),
                pendingAction = null,
                historyRecorded = false,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { convertAllCloud(st) }
                _state.update {
                    it.copy(
                        isConverting = false,
                        phase = if (outcome.partial) ConvertPhase.PARTIAL else ConvertPhase.COMPLETED,
                        progress = null,
                        convertedCount = 0,
                        cached = outcome.cached,
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                logger.e("Convert", "Cloud convert failed", e)
                _state.update {
                    it.copy(
                        isConverting = false,
                        phase = ConvertPhase.FAILED,
                        errorMessage = mapError(e),
                    )
                }
            }
        }
    }

    /**
     * Writes the cached PDFs to the selected destination. Re-runnable: every
     * press saves the (possibly password-protected) files again.
     */
    fun save() {
        val st = _state.value
        if (st.cached.isEmpty() || st.isSaving) return
        if (!destinationAvailable(st.saveLocationUri)) {
            _state.update {
                it.copy(
                    errorMessage = context.stringFor(
                        R.string.cv_destination_unavailable,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }

        _state.update { it.copy(isSaving = true, results = emptyList(), errorMessage = null) }
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { saveCached(st) }
                if (outcome.results.any { it.error == null } && !st.historyRecorded) {
                    withContext(Dispatchers.IO) {
                        recordInHistory(outcome.results, outcome.batchFolder, outcome.backupItems)
                    }
                }
                _state.update {
                    it.copy(
                        isSaving = false,
                        results = outcome.results,
                        // Encrypted copies produced during this save are kept in
                        // the cache so the next save/share/open reuses them.
                        cached = outcome.updatedCache,
                        // REPLACE deletes the original file: a new selection is
                        // required for further conversions.
                        files = if (st.originalAction == OriginalFileAction.REPLACE) emptyList() else it.files,
                        historyRecorded = true,
                    )
                }
            } catch (e: Exception) {
                logger.e("Convert", "Save failed", e)
                _state.update {
                    it.copy(isSaving = false, pendingAction = null, errorMessage = mapError(e))
                }
            }
        }
    }

    /** Saves to the destination and then shares the saved files. */
    fun saveAndShare() {
        _state.update { it.copy(pendingAction = ResultAction.SHARE) }
        save()
    }

    /** Saves to the destination and then opens the saved files. */
    fun saveAndOpen() {
        _state.update { it.copy(pendingAction = ResultAction.OPEN) }
        save()
    }

    fun consumeAction() {
        _state.update { it.copy(pendingAction = null) }
    }

    fun setPassword(password: String) {
        _state.update { it.copy(password = password) }
    }

    /** Generates a strong suggested password and asks the user to confirm. */
    fun generatePassword() {
        _state.update {
            it.copy(generatedPassword = PasswordGenerator.generate(), generateDialogVisible = true)
        }
    }

    /** The user accepted the suggested password: it fills the password field. */
    fun acceptGeneratedPassword() {
        _state.update {
            it.copy(
                password = it.generatedPassword ?: it.password,
                generatedPassword = null,
                generateDialogVisible = false,
                errorMessage = null,
            )
        }
    }

    fun dismissGenerateDialog() {
        _state.update { it.copy(generateDialogVisible = false) }
    }

    /**
     * Cloud upload is gated on having an active cloud session (the only real
     * entitlement available today).
     */
    fun toggleCloudUpload() {
        if (cloudUploadAllowed()) {
            _state.update { it.copy(cloudUploadEnabled = !it.cloudUploadEnabled) }
        } else {
            _state.update { it.copy(premiumDialogVisible = true) }
        }
    }

    fun dismissPremiumDialog() {
        _state.update { it.copy(premiumDialogVisible = false) }
    }

    private fun cloudUploadAllowed(): Boolean =
        _state.value.isAuthenticated

    private suspend fun convertAllCloud(st: ConvertUiState): ConvertOutcome {
        _state.update { it.copy(phase = ConvertPhase.UPLOADING, progress = 0.1f) }
        val jobResult = cloudConversionRepository.startConversion(
            st.files.map { it.uri to it.name }
        )
        val job = jobResult.getOrElse { err ->
            _state.update { it.copy(phase = ConvertPhase.FAILED, errorMessage = mapError(err)) }
            throw err
        }
        val jobId = job.id ?: throw IllegalStateException("Missing job id")

        _state.update { it.copy(phase = ConvertPhase.PROCESSING, progress = 0.25f) }
        val done = cloudConversionRepository.awaitJob(
            jobId = jobId,
            onProgress = { current ->
                val p = (current.progress ?: 0) / 100f
                _state.update { it.copy(phase = ConvertPhase.PROCESSING, progress = p) }
            },
        )
        val finalJob = done.getOrElse { err ->
            _state.update { it.copy(phase = ConvertPhase.FAILED, errorMessage = mapError(err)) }
            throw err
        }

        val completedItems = cloudConversionRepository.completedItems(finalJob)
        if (completedItems.isEmpty()) {
            throw ConversionException(context.stringFor(R.string.cloud_conversion_failed, AppLocaleOverride.locale))
        }

        _state.update { it.copy(phase = ConvertPhase.DOWNLOADING, progress = 0.85f) }
        val usedNames = mutableSetOf<String>()
        val cachedFiles = mutableListOf<CachedConvert>()
        var failures = 0

        completedItems.forEachIndexed { index, item: ConversionItem ->
            val originalName = item.originalName ?: st.files.getOrNull(index)?.name ?: "document"
            val baseName = originalName.substringBeforeLast('.', originalName)
            val targetBase = when (st.originalAction) {
                OriginalFileAction.KEEP -> context.stringFor(
                    R.string.cv_name_keep,
                    AppLocaleOverride.locale,
                    baseName,
                    "pdf"
                )

                OriginalFileAction.REPLACE -> "$baseName.pdf"
                OriginalFileAction.COPY -> context.stringFor(
                    R.string.cv_name_copy,
                    AppLocaleOverride.locale,
                    baseName,
                    "pdf"
                )
            }
            var name = targetBase
            var counter = 2
            while (!usedNames.add(name)) {
                name = uniqueName(targetBase, counter++)
            }

            val download = cloudConversionRepository.downloadConverted(
                jobId = jobId,
                fileId = item.fileId ?: throw IllegalStateException("Missing file id"),
                fileName = name,
            )
            val downloaded = download.getOrElse {
                failures += 1
                return@forEachIndexed
            }

            val cachedFile = moveToCache(downloaded, name)
            cachedFiles += CachedConvert(
                fileName = name,
                file = cachedFile,
                sizeBytes = cachedFile.length(),
                sourceUri = st.files.getOrNull(index)?.uri,
            )
            _state.update {
                it.copy(
                    convertedCount = cachedFiles.size,
                    progress = (index.toFloat() + 1) / completedItems.size.coerceAtLeast(1),
                )
            }
        }

        if (cachedFiles.isEmpty()) {
            throw ConversionException(context.stringFor(R.string.cv_error, AppLocaleOverride.locale))
        }
        return ConvertOutcome(cachedFiles, partial = failures > 0)
    }

    /**
     * Writes the cached PDFs to the selected destination, applying the optional
     * password first and uploading to the cloud when enabled.
     *
     * A protected PDF is encrypted only when the password changes: the encrypted
     * files are cached on the entry ([CachedConvert.protectedFile]) and reused
     * on every subsequent save/share/open, so opening again never re-encrypts.
     */
    private suspend fun saveCached(st: ConvertUiState): SaveOutcome {
        val uploadToCloud = st.cloudUploadEnabled && cloudUploadAllowed()
        val password = st.password
        val usedNames = mutableSetOf<String>()
        val isBatch = st.cached.size > 1
        val batchFolder = if (isBatch) createBatchFolder(st.saveLocationUri) else null
        val backupItems = mutableListOf<Pair<ToolOutputSaver.SaveRef, String>>()
        val results = mutableListOf<ConvertResult>()
        val updatedCache = mutableListOf<CachedConvert>()
        val uploadFiles = mutableListOf<File>()
        val uploadResultIndex = mutableListOf<Int>()

        st.cached.forEach { cached ->
            var name = cached.fileName
            var counter = 2
            while (!usedNames.add(name)) {
                name = uniqueName(name, counter++)
            }

            // Reuse the previously encrypted copy unless the password changed.
            val reuseProtected = password.isNotBlank() &&
                    cached.protectedFile != null &&
                    cached.protectedWithPassword == password
            var newProtected: File? = null
            val sourceFile = when {
                reuseProtected -> cached.protectedFile
                password.isNotBlank() -> {
                    try {
                        PasswordProtectEngine.protectToTemp(ProtectedFileType.PDF, cached.file, password)
                            .also { newProtected = it }
                    } catch (e: Exception) {
                        results += ConvertResult(
                            fileName = name,
                            outputUri = null,
                            sizeBytes = 0,
                            error = mapError(e),
                        )
                        return@forEach
                    }
                }

                else -> cached.file
            }

            try {
                val outputRef = saver.save(name, sourceFile, st.saveLocationUri, batchFolder?.uri)
                val finalName = (outputRef as? ToolOutputSaver.SaveRef.FileRef)?.file?.name ?: name
                backupItems += outputRef to finalName

                // REPLACE: the converted file now takes the original's place,
                // so the original is removed once it has been saved.
                if (st.originalAction == OriginalFileAction.REPLACE && cached.sourceUri != null) {
                    runCatching { context.contentResolver.delete(cached.sourceUri, null, null) }
                }

                val savedLength = outputRef.length(context)
                results += ConvertResult(
                    fileName = finalName,
                    outputUri = when (outputRef) {
                        is ToolOutputSaver.SaveRef.FileRef -> uriForFile(context, outputRef.file)
                        is ToolOutputSaver.SaveRef.UriRef -> outputRef.uri
                    },
                    // Some providers report a null/0 SIZE right after writing:
                    // fall back to the actual source file length.
                    sizeBytes = if (savedLength > 0) savedLength else sourceFile.length(),
                    batchFolderUri = batchFolder?.uri,
                )
                if (uploadToCloud) {
                    uploadFiles += sourceFile
                    uploadResultIndex += results.size - 1
                }

                // Cache the encrypted copy (replacing a stale one from an old
                // password), or drop it entirely when the password was cleared.
                if (reuseProtected || newProtected != null) {
                    if (newProtected != null && cached.protectedWithPassword != password) {
                        cached.protectedFile?.delete()
                    }
                    updatedCache += cached.copy(
                        protectedFile = newProtected ?: cached.protectedFile,
                        protectedWithPassword = password,
                    )
                } else {
                    cached.protectedFile?.delete()
                    updatedCache += cached.copy(protectedFile = null, protectedWithPassword = "")
                }
            } catch (e: Exception) {
                // Save failed: discard the never-used encrypted copy.
                newProtected?.delete()
                results += ConvertResult(
                    fileName = name,
                    outputUri = null,
                    sizeBytes = 0,
                    error = mapError(e),
                )
                updatedCache += cached
            }
        }

        // Cloud: multiple converted PDFs are grouped in a folder (enter to
        // visualize), a single output is uploaded plainly.
        if (uploadToCloud && uploadFiles.isNotEmpty()) {
            val cloudResults = cloudRepository.uploadGroup(
                fileUris = uploadFiles.map { uriForFile(context, it) },
                groupName = batchFolder?.name ?: ExportNames.folderName("NopalitoScan_Convert"),
                formatName = "PDF",
            )
            cloudResults.forEachIndexed { i, r ->
                val index = uploadResultIndex.getOrNull(i) ?: return@forEachIndexed
                val previous = results.getOrNull(index) ?: return@forEachIndexed
                val ok = r.isSuccess
                results[index] = previous.copy(
                    cloudUploadSuccess = ok,
                    cloudUploadError = if (ok) null else {
                        CloudErrorPresenter.message(context, r.exceptionOrNull(), R.string.cloud_error_upload)
                    },
                )
            }
        }
        return SaveOutcome(results, batchFolder, backupItems, updatedCache)
    }

    /** Moves a downloaded PDF into the tools cache folder so it survives until saved. */
    private fun moveToCache(file: File, fileName: String): File {
        val target = File(toolsDir, "${System.currentTimeMillis()}_$fileName")
        return if (file.renameTo(target)) target else file
    }

    private data class ConvertOutcome(
        val cached: List<CachedConvert>,
        val partial: Boolean,
    )

    private data class SaveOutcome(
        val results: List<ConvertResult>,
        val batchFolder: BatchFolder?,
        val backupItems: List<Pair<ToolOutputSaver.SaveRef, String>>,
        /** Cache entries with the encrypted copies updated after this save. */
        val updatedCache: List<CachedConvert>,
    )

    private fun destinationAvailable(saveLocationUri: String?): Boolean =
        saveLocationUri == null || runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(
                context,
                saveLocationUri.toUri(),
            )
        }.getOrNull() != null

    private fun createBatchFolder(saveLocationUri: String?): BatchFolder? {
        val folderName = ExportNames.folderName("NopalitoScan_Convert")
        val treeUri = saveLocationUri?.toUri()
        val tree = treeUri?.let {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it)
        }
        return if (tree != null) {
            val existing = tree.findFile(folderName)
            val dir = if (existing != null && existing.isDirectory) {
                existing
            } else {
                tree.createDirectory(folderName)
                    ?: throw IllegalStateException("Cannot create batch folder")
            }
            BatchFolder(dir.uri, dir.name ?: folderName)
        } else {
            val base = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val dir = File(base, folderName).apply { mkdirs() }
            BatchFolder(Uri.fromFile(dir), folderName)
        }
    }

    private fun uniqueName(name: String, counter: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)}_$counter${name.substring(dot)}"
        else "${name}_$counter"
    }

    private fun mapError(e: Throwable): String =
        CloudErrorPresenter.message(context, e, R.string.cv_error)

    private suspend fun recordInHistory(
        results: List<ConvertResult>,
        batchFolder: BatchFolder?,
        backupItems: List<Pair<ToolOutputSaver.SaveRef, String>>,
    ) {
        if (results.isEmpty() || backupItems.isEmpty()) return
        try {
            val isFolder = results.size > 1
            val format = "PDF"
            val totalSize = results.sumOf { it.sizeBytes }
            val documentName = if (isFolder) {
                batchFolder?.name ?: ExportNames.folderName("NopalitoScan_Convert")
            } else {
                results.first().fileName
            }
            val backupDir = exportsBackupDir.apply { mkdirs() }
            val backupPath = if (isFolder) {
                val dir = File(backupDir, "convert_${System.currentTimeMillis()}")
                dir.mkdirs()
                backupItems.forEach { (ref, name) -> writeBackupCopy(ref, File(dir, name)) }
                dir.absolutePath
            } else {
                val ref = backupItems.first().first
                val file = File(backupDir, backupItems.first().second)
                writeBackupCopy(ref, file)
                file.absolutePath
            }
            val childrenUris = if (isFolder) {
                results.joinToString("\n") { it.outputUri?.toString() ?: "" }.ifEmpty { null }
            } else {
                null
            }
            historyRepository.insert(
                ExportHistoryEntity(
                    documentName = documentName,
                    dateTime = System.currentTimeMillis(),
                    pageCount = results.size,
                    format = format,
                    quality = "CONVERTED",
                    fileSizeBytes = totalSize,
                    exportCount = 1,
                    thumbnailPath = null,
                    originalDocumentPath = null,
                    exportedFilePath = if (isFolder) null else results.first().outputUri?.toString(),
                    status = ExportHistoryEntity.STATUS_AVAILABLE,
                    resultType = if (isFolder) {
                        ExportHistoryEntity.RESULT_TYPE_FOLDER
                    } else {
                        ExportHistoryEntity.RESULT_TYPE_FILE
                    },
                    exportedFolderUri = if (isFolder) {
                        batchFolder?.uri
                            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
                            ?.toString()
                    } else {
                        null
                    },
                    exportedItemCount = results.size,
                    childrenUris = childrenUris,
                    backupPath = if (isFolder) null else backupPath,
                    backupDirPath = if (isFolder) backupPath else null,
                )
            )
        } catch (e: Exception) {
            logger.e("ConvertHistory", "Failed to record convert history", e)
        }
    }

    private fun writeBackupCopy(ref: ToolOutputSaver.SaveRef, target: File) {
        when (ref) {
            is ToolOutputSaver.SaveRef.FileRef -> ref.file.copyTo(target, overwrite = true)
            is ToolOutputSaver.SaveRef.UriRef -> {
                val input = context.contentResolver.openInputStream(ref.uri)
                    ?: throw IllegalStateException("Cannot read output for backup")
                input.use { ins ->
                    target.outputStream().use { out -> ins.copyTo(out) }
                }
            }
        }
    }
}
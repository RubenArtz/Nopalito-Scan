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

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nopalito.app.AppContainer
import nopalito.app.R
import nopalito.app.data.ExportNames
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.platform.crypto.CryptoException
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.data.CloudSessionManager
import nopalito.app.ui.screens.cloud.data.CloudSessionState
import nopalito.app.ui.screens.history.ExportHistoryEntity
import nopalito.app.ui.screens.tools.BatchFolder
import nopalito.app.ui.screens.tools.BatchMode
import nopalito.app.ui.screens.tools.OriginalFileAction
import nopalito.app.ui.screens.tools.PickedFile
import nopalito.app.ui.screens.tools.shared.FilePreviewController
import nopalito.app.ui.screens.tools.shared.PasswordGenerator
import nopalito.app.ui.screens.tools.shared.PreviewFileType
import nopalito.app.ui.screens.tools.shared.ToolOutputSaver
import nopalito.app.ui.uriForFile
import java.io.File

/**
 * ViewModel of the "Protect with password" tool.
 *
 * The output destination is read from the [nopalito.app.ui.screens.settings.SettingsRepository]
 * (the same source of truth as Settings and the compressor): configuration is
 * not duplicated, only consumed.
 */
class PasswordProtectViewModel(container: AppContainer) : ViewModel() {

    @SuppressLint("StaticFieldLeak")
    private val context: Context = container.applicationContext
    private val settingsRepository = container.settingsRepository
    private val cloudSessionManager: CloudSessionManager = container.cloudSessionManager
    private val cloudRepository = CloudRepository(context)
    private val historyRepository = container.historyRepository
    private val logger = container.logger
    private val exportsBackupDir = container.exportsBackupDir
    private val statsRepository = container.statsRepository
    private val saver = ToolOutputSaver(context)
    private val toolsDir: File = File(context.cacheDir, "tools").apply { mkdirs() }

    /** Shared read-only file preview (single file strip + batch strips). */
    private val preview = FilePreviewController(context, logger, viewModelScope)

    private val _state = MutableStateFlow(PasswordProtectUiState())
    val state: StateFlow<PasswordProtectUiState> = _state.asStateFlow()

    init {
        // A random password is pre-filled by default (it can still be edited,
        // cleared or regenerated with the dice button).
        _state.update { it.copy(password = PasswordGenerator.generate()) }
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
        // Mirror the shared preview state into this tool's UiState.
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

    /** Maps the selected file type to the shared preview type. */
    private fun ProtectedFileType.toPreviewType(): PreviewFileType = when (this) {
        ProtectedFileType.PDF -> PreviewFileType.PDF
        ProtectedFileType.WORD -> PreviewFileType.WORD
    }

    fun setFileType(type: ProtectedFileType) {
        preview.clear()
        _state.update {
            it.copy(
                fileType = type,
                files = emptyList(),
                results = emptyList(),
                errorMessage = null,
            )
        }
    }

    fun setBatchMode(mode: BatchMode) {
        preview.clear()
        _state.update {
            it.copy(
                batchMode = mode,
                files = emptyList(),
                results = emptyList(),
                errorMessage = null,
            )
        }
    }

    fun addFiles(files: List<PickedFile>) {
        _state.update {
            it.copy(files = files, results = emptyList(), errorMessage = null)
        }
        preview.prepare(files, _state.value.fileType.toPreviewType())
    }

    /** Renders one page of the single-file preview (see [FilePreviewController]). */
    suspend fun renderPageForPreview(pageIndex: Int, targetWidthPx: Int): Bitmap? =
        preview.renderPage(pageIndex, targetWidthPx)

    /** Renders one page of a batch file preview (see [FilePreviewController]). */
    suspend fun renderBatchPage(uriKey: String, pageIndex: Int, targetWidthPx: Int): Bitmap? =
        preview.renderBatchPage(uriKey, pageIndex, targetWidthPx)

    fun setPassword(password: String) {
        // Clearing the results forces a new export with the new password: the
        // previous result no longer matches the current password.
        _state.update {
            it.copy(password = password, results = emptyList(), errorMessage = null)
        }
    }

    /** What to do with the original file after protecting (keep / replace / copy). */
    fun setOriginalAction(action: OriginalFileAction) {
        _state.update {
            it.copy(originalAction = action, results = emptyList(), errorMessage = null)
        }
    }

    /** Generates a strong suggested password and asks the user to confirm. */
    fun generatePassword() {
        val generated = PasswordGenerator.generate()
        _state.update {
            it.copy(generatedPassword = generated, generateDialogVisible = true)
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

    /** Reports that a picked file does not match the selected file type. */
    fun reportInvalidFileType() {
        val typeName = context.stringFor(_state.value.fileType.titleRes, AppLocaleOverride.locale)
        _state.update {
            it.copy(
                errorMessage = context.stringFor(
                    R.string.pp_invalid_file_type,
                    AppLocaleOverride.locale,
                    typeName
                ),
                results = emptyList(),
            )
        }
    }

    /**
     * Persists the output destination in Settings (same source of truth as the
     * settings screen) and refreshes the displayed name.
     */
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
     * Cloud upload is gated on having an active cloud session (the only real
     * entitlement available today). ponytail: when a real Premium/Billing
     * entitlement exists, combine it here without UI changes.
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

    /** Protects the selected files without sending them to the compressor. */
    fun save() {
        val st = _state.value
        if (st.files.isEmpty() || st.isProcessing) return

        when {
            st.password.isBlank() -> _state.update {
                it.copy(
                    errorMessage = context.stringFor(
                        R.string.pp_password_required,
                        AppLocaleOverride.locale
                    )
                )
            }

            !destinationAvailable(st.saveLocationUri) -> _state.update {
                it.copy(
                    errorMessage = context.stringFor(
                        R.string.pp_destination_unavailable,
                        AppLocaleOverride.locale
                    )
                )
            }

            else -> {
                _state.update {
                    it.copy(
                        isProcessing = true,
                        results = emptyList(),
                        errorMessage = null
                    )
                }
                viewModelScope.launch {
                    try {
                        val results = withContext(Dispatchers.IO) { protectAll(st, st.password) }
                        _state.update {
                            it.copy(
                                isProcessing = false,
                                results = results,
                                // REPLACE deletes the original file: a new selection is
                                // required for further exports.
                                files = if (st.originalAction == OriginalFileAction.REPLACE) {
                                    emptyList()
                                } else {
                                    it.files
                                },
                            )
                        }
                        viewModelScope.launch {
                            runCatching { statsRepository.logToolUsed("password_protect") }
                            runCatching {
                                statsRepository.logScanExported(
                                    format = "PDF",
                                    destination = "tool_password_protect"
                                )
                            }
                            runCatching { statsRepository.logScanOpened(source = "tool_password_protect") }
                        }
                    } catch (e: Exception) {
                        logger.e("Protect", "Protect failed", e)
                        _state.update {
                            it.copy(isProcessing = false, errorMessage = mapError(e))
                        }
                    }
                }
            }
        }
    }

    private fun destinationAvailable(saveLocationUri: String?): Boolean =
        saveLocationUri == null || runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(
                context,
                saveLocationUri.toUri(),
            )
        }.getOrNull() != null

    private suspend fun protectAll(
        st: PasswordProtectUiState,
        password: String,
    ): List<PasswordProtectResult> {
        val uploadToCloud = st.cloudUploadEnabled && cloudUploadAllowed()
        val usedNames = mutableSetOf<String>()
        val isBatch = st.files.size > 1
        val batchFolder = if (isBatch) createBatchFolder(st.saveLocationUri) else null
        val backupItems = mutableListOf<Pair<ToolOutputSaver.SaveRef, String>>()

        data class Processed(
            val file: PickedFile,
            val protectedFile: File,
            val finalName: String,
            val outputRef: ToolOutputSaver.SaveRef,
        )

        // First pass: protect + save, keeping the outputs alive for the group upload.
        val processed = st.files.map { file ->
            val inputFile = cacheInput(file)
            val protectedFile = try {
                PasswordProtectEngine.protectToTemp(st.fileType, inputFile, password)
            } finally {
                inputFile.delete()
            }

            var targetName = targetNameFor(st.originalAction, file, protectedFile)
            var counter = 2
            while (!usedNames.add(targetName)) {
                targetName = uniqueName(targetName, counter++)
            }

            val outputRef =
                saver.save(targetName, protectedFile, st.saveLocationUri, batchFolder?.uri)
            val finalName =
                (outputRef as? ToolOutputSaver.SaveRef.FileRef)?.file?.name ?: targetName
            backupItems += outputRef to finalName
            Processed(file, protectedFile, finalName, outputRef)
        }

        // Cloud: multiple outputs are grouped in a folder (enter to visualize),
        // a single output is uploaded plainly.
        val cloudResults = if (uploadToCloud) {
            cloudRepository.uploadGroup(
                fileUris = processed.map { uriForFile(context, it.protectedFile) },
                groupName = batchFolder?.name ?: ExportNames.folderName("Nopalito_Scan_Protect"),
                formatName = when (st.fileType) {
                    ProtectedFileType.PDF -> "PDF"
                    ProtectedFileType.WORD -> "DOCX"
                },
            )
        } else {
            emptyList()
        }

        return processed.mapIndexed { index, item ->
            // REPLACE: the protected file now takes the original's place, so the
            // original is removed (mirrors the compressor behavior).
            if (st.originalAction == OriginalFileAction.REPLACE) {
                runCatching { context.contentResolver.delete(item.file.uri, null, null) }
            }

            val cloud = if (uploadToCloud) {
                val uploadResult = cloudResults.getOrNull(index)
                if (uploadResult != null && uploadResult.isSuccess) {
                    true to null
                } else {
                    val message = CloudErrorPresenter.message(
                        context,
                        uploadResult?.exceptionOrNull(),
                        R.string.cloud_error_upload,
                    )
                    false to message
                }
            } else {
                null to null
            }

            item.protectedFile.delete()

            PasswordProtectResult(
                fileName = item.finalName,
                outputUri = when (item.outputRef) {
                    is ToolOutputSaver.SaveRef.FileRef -> uriForFile(context, item.outputRef.file)
                    is ToolOutputSaver.SaveRef.UriRef -> item.outputRef.uri
                },
                sizeBytes = item.outputRef.length(context),
                batchFolderUri = batchFolder?.uri,
                cloudUploadSuccess = cloud.first,
                cloudUploadError = cloud.second,
            )
        }.also { results ->
            recordInHistory(st, results, batchFolder, backupItems)
        }
    }

    /** Creates the batch destination folder, or null for a single file. */
    private fun createBatchFolder(saveLocationUri: String?): BatchFolder? {
        val folderName = ExportNames.folderName("Nopalito_Scan_Protect")
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

    /**
     * Registers the result in the local history, like the compressor does:
     * keeps a private backup copy so History can preview/open/share the file
     * even after it is deleted from Downloads, then inserts an
     * [ExportHistoryEntity].
     */
    private suspend fun recordInHistory(
        st: PasswordProtectUiState,
        results: List<PasswordProtectResult>,
        batchFolder: BatchFolder?,
        backupItems: List<Pair<ToolOutputSaver.SaveRef, String>>,
    ) {
        if (results.isEmpty() || backupItems.isEmpty()) return
        try {
            val isFolder = results.size > 1
            val format = when (st.fileType) {
                ProtectedFileType.PDF -> "PDF"
                ProtectedFileType.WORD -> "DOCX"
            }
            val totalSize = results.sumOf { it.sizeBytes }
            val documentName = if (isFolder) {
                batchFolder?.name ?: ExportNames.folderName("Nopalito_Scan_Protect")
            } else {
                results.first().fileName
            }

            val backupDir = exportsBackupDir.apply { mkdirs() }
            val backupPath = if (isFolder) {
                val dir = File(backupDir, "protect_${System.currentTimeMillis()}")
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
                    quality = "PROTECTED",
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
            logger.e("ProtectHistory", "Failed to record protect history", e)
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

    private fun cacheInput(file: PickedFile): File {
        val cacheFile = File(toolsDir, "${System.currentTimeMillis()}_${file.name}")
        val input = context.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Cannot read input file")
        input.use { ins ->
            cacheFile.outputStream().use { out -> ins.copyTo(out) }
        }
        return cacheFile
    }

    /**
     * Output file name derived from the chosen original-file action:
     * - KEEP: "base (protegido).ext", the original stays untouched.
     * - REPLACE: "base.ext", the original is deleted and replaced.
     * - COPY: "copia de base.ext", the original stays and a protected copy is added.
     */
    private fun targetNameFor(
        action: OriginalFileAction,
        file: PickedFile,
        protected: File,
    ): String {
        val base = file.name.substringBeforeLast('.')
        val ext = protected.extension.ifEmpty { file.name.substringAfterLast('.', "bin") }
        return when (action) {
            OriginalFileAction.KEEP -> "$base (protegido).$ext"
            OriginalFileAction.REPLACE -> "$base.$ext"
            OriginalFileAction.COPY -> "copia de $base.$ext"
        }
    }

    private fun uniqueName(name: String, counter: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)}_$counter${name.substring(dot)}"
        else "${name}_$counter"
    }

    /** Maps known protection exceptions to user-facing messages. */
    private fun mapError(e: Exception): String = when (e) {
        is InvalidPasswordException -> context.stringFor(
            R.string.pp_error_protected,
            AppLocaleOverride.locale
        )

        is CryptoException -> if (e.message?.contains("not a ZIP") == true) {
            context.stringFor(R.string.pp_error_protected, AppLocaleOverride.locale)
        } else {
            context.stringFor(R.string.pp_error, AppLocaleOverride.locale)
        }

        else -> context.stringFor(R.string.pp_error, AppLocaleOverride.locale)
    }
}

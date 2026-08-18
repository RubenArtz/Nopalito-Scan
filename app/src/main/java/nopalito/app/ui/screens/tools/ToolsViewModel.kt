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

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nopalito.app.AppContainer
import nopalito.app.R
import nopalito.app.data.ExportNames
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.platform.crypto.DocxDecryptor
import nopalito.app.platform.crypto.DocxEncryptor
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.data.CloudSessionManager
import nopalito.app.ui.screens.cloud.data.CloudSessionState
import nopalito.app.ui.screens.history.ExportHistoryEntity
import nopalito.app.ui.screens.tools.shared.FilePreviewController
import nopalito.app.ui.screens.tools.shared.PreviewFileState
import nopalito.app.ui.screens.tools.shared.PreviewFileType
import nopalito.app.ui.uriForFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A compression tool offered by the Herramientas section. */
enum class CompressTool(val titleRes: Int, val shortLabelRes: Int) {
    PDF(R.string.tools_compress_pdf, R.string.tools_short_pdf),
    IMAGE(R.string.tools_compress_images, R.string.tools_short_images),
    WORD(R.string.tools_compress_word, R.string.tools_short_word),
}

/** Selection mode: one file, or a batch of files. */
enum class BatchMode {
    INDIVIDUAL, BATCH,
}

/** What to do with the original file after compressing. */
enum class OriginalFileAction {
    KEEP, REPLACE, COPY,
}

/** Compression level offered to the user. */
enum class CompressLevel(val labelRes: Int, val jpegQuality: Int, val sampleSize: Int) {
    HIGH(R.string.quality_high, 85, 1),
    BALANCED(R.string.quality_balanced, 70, 1),
    COMPRESSED(R.string.quality_compressed, 50, 2),
    MAX_COMPRESSION(R.string.quality_max_compression, 35, 4),
}

data class PickedFile(
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    /** True when the picked file is itself password-protected. */
    val isPasswordProtected: Boolean = false,
)

/** Destination folder used for a batch compression (uri to open/write + display name). */
data class BatchFolder(
    val uri: Uri,
    val name: String,
)

data class CompressedResult(
    val fileName: String,
    val originalSizeBytes: Long,
    val outputSizeBytes: Long,
    /** True when the output is actually smaller than the input. */
    val reduced: Boolean = false,
    /** True when the output PDF is encrypted with a password. */
    val protected: Boolean = false,
    val cloudUploadSuccess: Boolean? = null,
    val cloudUploadError: String? = null,
    /** Content uri used to open/share the compressed file. */
    val shareUri: Uri? = null,
    /** Folder uri that contains the batch output (set when several files were compressed). */
    val batchFolderUri: Uri? = null,
)

data class ToolsUiState(
    val tool: CompressTool = CompressTool.PDF,
    val batchMode: BatchMode = BatchMode.INDIVIDUAL,
    val files: List<PickedFile> = emptyList(),
    val level: CompressLevel = CompressLevel.BALANCED,
    val originalAction: OriginalFileAction = OriginalFileAction.KEEP,
    val saveLocationName: String? = null,
    val saveLocationUri: String? = null,
    val isAuthenticated: Boolean = false,
    val cloudUploadEnabled: Boolean = false,
    val password: String = "",
    val originalPassword: String = "",
    val isCompressing: Boolean = false,
    val results: List<CompressedResult> = emptyList(),
    val errorMessage: String? = null,
    val premiumDialogVisible: Boolean = false,
    /** How many pages the file preview shows (single file only); 0 = no preview. */
    val previewPageCount: Int = 0,
    /** True while a preview is being generated (Word documents via the backend). */
    val isPreviewLoading: Boolean = false,
    /** True when the preview could not be generated (still compresses normally). */
    val previewFailed: Boolean = false,
    /** True when the picked file is password-protected and cannot be previewed. */
    val previewProtected: Boolean = false,
    /** Per-file previews shown in batch mode (one entry per picked file). */
    val batchPreviews: List<PreviewFileState> = emptyList(),
)

class ToolsViewModel(private val container: AppContainer) : ViewModel() {

    @SuppressLint("StaticFieldLeak")
    private val context: Context = container.applicationContext
    private val settingsRepository = container.settingsRepository
    private val cloudSessionManager: CloudSessionManager = container.cloudSessionManager
    private val cloudRepository = CloudRepository(context)
    private val historyRepository = container.historyRepository
    private val exportsBackupDir = container.exportsBackupDir
    private val logger = container.logger

    private val toolsDir: File = File(context.cacheDir, "tools").apply { mkdirs() }

    /** Shared read-only file preview (single file strip + batch strips). */
    private val preview = FilePreviewController(context, logger, viewModelScope)

    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

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
        // Mirror the shared preview state into this tool's UiState.
        viewModelScope.launch {
            preview.state.collect { pv ->
                _state.update {
                    it.copy(
                        previewPageCount = pv.singlePageCount,
                        isPreviewLoading = pv.isLoading,
                        previewFailed = pv.failed,
                        previewProtected = pv.protected,
                        batchPreviews = pv.batch,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        preview.clear()
        super.onCleared()
    }

    /**
     * Cloud upload is gated on having an active cloud session (the only real entitlement
     * available today). ponytail: when a real Premium/Billing entitlement exists, combine it
     * here (e.g. `isAuthenticated && billingClient.isPremium`) without UI changes.
     */
    fun bindTool(tool: CompressTool) {
        // Consume a pending transfer from another tool feature (e.g. the
        // "Protect with password" tool): preselected files, batch mode and
        // password prefill.
        val transfer = container.toolTransfer.consume()
        if (transfer != null && transfer.tool == tool) {
            _state.update {
                it.copy(
                    tool = tool,
                    batchMode = transfer.batchMode,
                    files = transfer.files,
                    password = transfer.password,
                    results = emptyList(),
                    errorMessage = null,
                )
            }
            preparePreview(transfer.files)
        } else {
            // The page preview renderer always belongs to the files already in
            // the state (or none), so it is kept as-is on re-entry.
            _state.update { it.copy(tool = tool) }
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
        detectProtection(files)
        preparePreview(files)
    }

    /** Marks in the background which picked files are already password-protected (to show the source password). */
    private fun detectProtection(files: List<PickedFile>) {
        viewModelScope.launch {
            val tool = _state.value.tool
            val detected = files.map { file ->
                file.copy(isPasswordProtected = isProtectedFile(file, tool))
            }
            _state.update { it.copy(files = detected) }
        }
    }

    private suspend fun isProtectedFile(file: PickedFile, tool: CompressTool): Boolean = try {
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(file.uri) ?: return@withContext false
            stream.use { input ->
                when (tool) {
                    CompressTool.PDF -> streamContainsAscii(input)
                    CompressTool.WORD -> {
                        val head = ByteArray(8)
                        var off = 0
                        while (off < head.size) {
                            val n = input.read(head, off, head.size - off)
                            if (n < 0) break
                            off += n
                        }
                        head.contentEquals(OLE2_MAGIC)
                    }

                    else -> false
                }
            }
        }
    } catch (_: Exception) {
        false
    }

    /** Scans the stream for the PDF encryption marker without loading it entirely into memory. */
    private fun streamContainsAscii(input: java.io.InputStream): Boolean {
        val t = PDF_ENCRYPT_MARKER.toByteArray(Charsets.US_ASCII)
        val buf = ByteArray(8192)
        val tail = ByteArray(t.size - 1)
        var n = input.read(buf)
        while (n >= 0) {
            val combined = ByteArray(tail.size + n)
            System.arraycopy(tail, 0, combined, 0, tail.size)
            System.arraycopy(buf, 0, combined, tail.size, n)
            if (indexOf(combined, t) >= 0) return true
            val keep = Math.min(tail.size, n)
            System.arraycopy(buf, n - keep, tail, 0, keep)
            n = input.read(buf)
        }
        return false
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    fun setLevel(level: CompressLevel) {
        _state.update { it.copy(level = level) }
    }

    fun setOriginalAction(action: OriginalFileAction) {
        _state.update { it.copy(originalAction = action) }
    }

    fun setPassword(password: String) {
        _state.update { it.copy(password = password) }
    }

    fun setOriginalPassword(password: String) {
        _state.update { it.copy(originalPassword = password) }
    }

    /** Generates a strong random password (16 chars, letters/digits/symbols). */
    fun generatePassword() {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"
        val random = java.security.SecureRandom()
        val password = buildString { repeat(16) { append(chars[random.nextInt(chars.length)]) } }
        _state.update { it.copy(password = password) }
    }

    fun setSaveLocation(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setExportDirUri(uri)
            val name = uri?.let { settingsRepository.resolveExportDirName(it) }
            _state.update { it.copy(saveLocationUri = uri, saveLocationName = name) }
        }
    }

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

    /** Reports that a picked file does not match the current tool's file type. */
    fun reportInvalidFileType() {
        val toolName = context.stringFor(_state.value.tool.titleRes, AppLocaleOverride.locale)
        _state.update {
            it.copy(
                errorMessage = context.stringFor(R.string.tools_invalid_file_type, AppLocaleOverride.locale, toolName),
                results = emptyList(),
            )
        }
    }

    // ── File preview (read-only thumbnail strips) ──

    /** Maps the selected compressor tool to the shared preview type. */
    private fun CompressTool.toPreviewType(): PreviewFileType = when (this) {
        CompressTool.PDF -> PreviewFileType.PDF
        CompressTool.IMAGE -> PreviewFileType.IMAGE
        CompressTool.WORD -> PreviewFileType.WORD
    }

    private fun preparePreview(files: List<PickedFile>) {
        preview.prepare(files, _state.value.tool.toPreviewType())
    }

    /** Renders one page of the single-file preview (see [FilePreviewController]). */
    suspend fun renderPageForPreview(pageIndex: Int, targetWidthPx: Int): Bitmap? =
        preview.renderPage(pageIndex, targetWidthPx)

    /** Renders one page of a batch file preview (see [FilePreviewController]). */
    suspend fun renderBatchPage(uriKey: String, pageIndex: Int, targetWidthPx: Int): Bitmap? =
        preview.renderBatchPage(uriKey, pageIndex, targetWidthPx)

    fun compress() {
        val st = _state.value
        if (st.files.isEmpty() || st.isCompressing) return
        _state.update { it.copy(isCompressing = true, results = emptyList(), errorMessage = null) }

        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { compressAll(st) }
                _state.update { it.copy(isCompressing = false, results = results) }
            } catch (e: Exception) {
                logger.e("Compress", "Compression failed", e)
                _state.update {
                    it.copy(
                        isCompressing = false,
                        errorMessage = context.stringFor(R.string.tools_error, AppLocaleOverride.locale),
                    )
                }
            }
        }
    }

    private suspend fun compressAll(st: ToolsUiState): List<CompressedResult> {
        val uploadToCloud = st.cloudUploadEnabled && cloudUploadAllowed()
        val usedNames = mutableSetOf<String>()
        val isBatch = st.files.size > 1
        val password = st.password.ifBlank { null }
        val originalPassword = st.originalPassword.ifBlank { null }

        val isProtected = (st.tool == CompressTool.PDF || st.tool == CompressTool.WORD) && password != null

        val batchFolder = if (isBatch) createBatchFolder(st.saveLocationUri) else null
        val backupItems = mutableListOf<Pair<SaveRef, String>>()

        data class Processed(
            val file: PickedFile,
            val compressedFile: File,
            val finalName: String,
            val outputRef: SaveRef,
            val outputSize: Long,
        )

        // First pass: compress + save, keeping the outputs alive for the group upload.
        val processed = st.files.map { file ->
            val inputFile = cacheInput(file)
            val compressedFile = try {
                CompressionEngine.compress(st.tool, inputFile, st.level, password, originalPassword)
            } finally {
                inputFile.delete()
            }

            var targetName = targetNameFor(st, file, compressedFile)
            var counter = 2
            while (!usedNames.add(targetName)) {
                targetName = uniqueName(targetName, counter++)
            }

            val outputRef = writeResultFile(st.saveLocationUri, batchFolder?.uri, targetName, compressedFile)
            // The final name may change if the destination already had one with the same name.
            val finalName = (outputRef as? SaveRef.FileRef)?.file?.name ?: targetName
            backupItems += outputRef to finalName

            Processed(file, compressedFile, finalName, outputRef, compressedFile.length())
        }

        // Cloud: multiple outputs are grouped in a folder (enter to visualize),
        // a single output is uploaded plainly.
        val cloudResults = if (uploadToCloud) {
            cloudRepository.uploadGroup(
                fileUris = processed.map { uriForFile(context, it.compressedFile) },
                groupName = batchFolder?.name ?: ExportNames.folderName("Nopalito_Scan_Compress"),
                formatName = when (st.tool) {
                    CompressTool.PDF -> "PDF"
                    CompressTool.IMAGE -> "JPEG"
                    CompressTool.WORD -> "DOCX"
                },
            )
        } else {
            emptyList()
        }

        return processed.mapIndexed { index, item ->
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

            val shareUri = when (item.outputRef) {
                is SaveRef.FileRef -> uriForFile(context, item.outputRef.file)
                is SaveRef.UriRef -> item.outputRef.uri
            }

            item.compressedFile.delete()

            // REPLACE: the compressed file replaces the original → delete the source file.
            if (st.originalAction == OriginalFileAction.REPLACE) {
                runCatching { context.contentResolver.delete(item.file.uri, null, null) }
            }

            CompressedResult(
                fileName = item.finalName,
                originalSizeBytes = item.file.sizeBytes,
                outputSizeBytes = item.outputSize,
                reduced = item.outputSize < item.file.sizeBytes,
                protected = isProtected,
                cloudUploadSuccess = cloud.first,
                cloudUploadError = cloud.second,
                shareUri = shareUri,
                batchFolderUri = batchFolder?.uri,
            )
        }.also { results ->
            recordInHistory(st, results, batchFolder, backupItems)
        }
    }

    /** Creates the destination folder for a batch, or null for a single file. */
    private fun createBatchFolder(saveLocationUri: String?): BatchFolder? {
        val folderName = ExportNames.folderName("Nopalito_Scan_Compress")
        val treeUri = saveLocationUri?.toUri()
        val tree = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
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
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(base, folderName).apply { mkdirs() }
            BatchFolder(Uri.fromFile(dir), folderName)
        }
    }

    /**
     * Registers the compression result in the local export history, mirroring
     * ExportViewModel.recordExportInHistory: it keeps an app-private backup copy so the
     * history can preview/open/share the file even after it is deleted from Downloads,
     * then inserts an [ExportHistoryEntity].
     */
    private suspend fun recordInHistory(
        st: ToolsUiState,
        results: List<CompressedResult>,
        batchFolder: BatchFolder?,
        backupItems: List<Pair<SaveRef, String>>,
    ) {
        if (results.isEmpty() || backupItems.isEmpty()) return
        try {
            val isFolder = results.size > 1
            val format = when (st.tool) {
                CompressTool.PDF -> "PDF"
                CompressTool.IMAGE -> "JPEG"
                CompressTool.WORD -> "DOCX"
            }
            val totalSize = results.sumOf { it.outputSizeBytes }
            val documentName = if (isFolder) {
                batchFolder?.name ?: ExportNames.folderName("Nopalito_Scan_Compress")
            } else {
                results.first().fileName
            }

            val backupDir = exportsBackupDir.apply { mkdirs() }
            val backupPath = if (isFolder) {
                val dir = File(backupDir, "compress_${System.currentTimeMillis()}")
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
                results.joinToString("\n") { it.shareUri?.toString() ?: "" }.ifEmpty { null }
            } else {
                null
            }

            historyRepository.insert(
                ExportHistoryEntity(
                    documentName = documentName,
                    dateTime = System.currentTimeMillis(),
                    pageCount = results.size,
                    format = format,
                    quality = st.level.name,
                    fileSizeBytes = totalSize,
                    exportCount = 1,
                    thumbnailPath = null,
                    originalDocumentPath = null,
                    exportedFilePath = if (isFolder) null else results.first().shareUri?.toString(),
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
            logger.e("ToolsHistory", "Failed to record compression history", e)
        }
    }

    private fun writeBackupCopy(ref: SaveRef, target: File) {
        when (ref) {
            is SaveRef.FileRef -> ref.file.copyTo(target, overwrite = true)
            is SaveRef.UriRef -> {
                val input = context.contentResolver.openInputStream(ref.uri)
                    ?: throw IllegalStateException("Cannot read output for backup")
                input.use { ins ->
                    target.outputStream().use { out -> ins.copyTo(out) }
                }
            }
        }
    }

    /**
     * Writes [source] into the batch folder (when [batchUri] is set) or into the configured
     * save location, returning a ref that can be read, sized and shared.
     */
    private fun writeResultFile(
        saveLocationUri: String?,
        batchUri: Uri?,
        fileName: String,
        source: File,
    ): SaveRef {
        if (batchUri != null) {
            return if (batchUri.scheme == ContentResolver.SCHEME_FILE) {
                writeIntoLocal(File(batchUri.path!!), fileName, source)
            } else {
                val folder = DocumentFile.fromTreeUri(context, batchUri)
                    ?: throw IllegalStateException("Cannot open batch folder")
                writeIntoTree(folder, fileName, source)
            }
        }
        return if (saveLocationUri != null) {
            val tree = DocumentFile.fromTreeUri(context, saveLocationUri.toUri())
                ?: throw IllegalStateException("Cannot open save folder")
            writeIntoTree(tree, fileName, source)
        } else {
            saveIntoDownloads(fileName, source)
        }
    }

    /**
     * Saves into the default Downloads folder. Android 10+ uses MediaStore so the file
     * is indexed and visible in the Files app (scoped storage blocks raw path visibility);
     * Android 8/9 fall back to the File API.
     */
    private fun saveIntoDownloads(fileName: String, source: File): SaveRef {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(fileName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Cannot create Downloads entry")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Cannot write to Downloads")
            return SaveRef.UriRef(uri)
        }
        return writeIntoLocal(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName,
            source,
        )
    }

    private fun writeIntoTree(folder: DocumentFile, fileName: String, source: File): SaveRef {
        val existing = folder.findFile(fileName)
        val child = existing ?: folder.createFile(mimeTypeFor(fileName), fileName)
        ?: throw IllegalStateException("Cannot create file in folder")
        val stream = context.contentResolver.openOutputStream(child.uri)
            ?: throw IllegalStateException("Cannot write to folder")
        stream.use { out -> source.inputStream().use { ins -> ins.copyTo(out) } }
        return SaveRef.UriRef(child.uri)
    }

    private fun writeIntoLocal(dir: File, fileName: String, source: File): SaveRef {
        dir.mkdirs()
        var name = fileName
        var counter = 2
        var file = File(dir, name)
        while (file.exists() && !file.delete()) {
            name = uniqueName(fileName, counter++)
            file = File(dir, name)
        }
        FileOutputStream(file).use { out -> source.inputStream().use { ins -> ins.copyTo(out) } }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        return SaveRef.FileRef(file)
    }

    private fun cloudUploadAllowed(): Boolean =
        _state.value.isAuthenticated

    private fun cacheInput(file: PickedFile): File {
        val cacheFile = File(toolsDir, "${System.currentTimeMillis()}_${file.name}")
        val input = context.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Cannot read input file")
        input.use { ins ->
            cacheFile.outputStream().use { out -> ins.copyTo(out) }
        }
        return cacheFile
    }

    private fun targetNameFor(st: ToolsUiState, file: PickedFile, compressed: File): String {
        val base = file.name.substringBeforeLast('.')
        val ext = compressed.extension.ifEmpty { file.name.substringAfterLast('.', "bin") }
        return when (st.originalAction) {
            OriginalFileAction.KEEP -> "$base (comprimido).$ext"
            OriginalFileAction.REPLACE -> "$base.$ext"
            OriginalFileAction.COPY -> "copia de $base.$ext"
        }
    }

    private fun uniqueName(name: String, counter: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)}_$counter${name.substring(dot)}"
        else "${name}_$counter"
    }

    private fun mimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> "application/octet-stream"
    }

    private sealed class SaveRef {
        abstract fun length(context: Context): Long

        class FileRef(val file: File) : SaveRef() {
            override fun length(context: Context): Long = file.length()
        }

        class UriRef(val uri: Uri) : SaveRef() {
            override fun length(context: Context): Long = querySizeBytes(context, uri)
        }
    }
}

/** Byte-level compression logic for all tools. */
object CompressionEngine {

    fun compress(
        tool: CompressTool,
        input: File,
        level: CompressLevel,
        password: String? = null,
        originalPassword: String? = null,
    ): File {
        return when (tool) {
            CompressTool.IMAGE -> compressImage(input, level)
            CompressTool.WORD -> compressWord(input, password, originalPassword)
            CompressTool.PDF -> compressPdf(input, level, password, originalPassword)
        }
    }

    private fun compressImage(input: File, level: CompressLevel): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = level.sampleSize }
        val bitmap = BitmapFactory.decodeFile(input.absolutePath, opts)
            ?: throw IllegalStateException("Cannot decode image")
        val output = File(input.parentFile, "compressed_${System.currentTimeMillis()}.jpg")
        FileOutputStream(output).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, level.jpegQuality, out)
        }
        bitmap.recycle()
        return output
    }

    private fun compressWord(input: File, password: String?, originalPassword: String?): File {
        val output = File(input.parentFile, "compressed_${System.currentTimeMillis()}.docx")
        val plain = if (isOle2Cfb(input)) {
            // Password-protected source: decrypt with its password before re-compressing.
            val plainBytes = DocxDecryptor.decrypt(input, originalPassword)
            val tmp = File(input.parentFile, "unlocked_${System.currentTimeMillis()}.docx")
            tmp.writeBytes(plainBytes)
            tmp
        } else {
            input
        }
        if (!password.isNullOrBlank()) {
            DocxEncryptor.encrypt(plain, password, output)
        } else {
            rezipDocx(plain, output)
        }
        if (plain !== input) plain.delete()
        return output
    }

    /** True when [file] is a Compound File Binary (password-encrypted Word). */
    private fun isOle2Cfb(file: File): Boolean {
        val head = ByteArray(8)
        file.inputStream().use { it.read(head) }
        return head.contentEquals(OLE2_MAGIC)
    }

    /**
     * Real PDF compression, always attempted regardless of image content:
     * 1. Every raster image is re-encoded as JPEG at the chosen quality.
     * 2. Every stream that is not already lossy/compressed (no filter, or only
     *    ASCIIHex/ASCII85) is re-encoded with FlateDecode.
     * 3. When [password] is set, the document is encrypted (user+owner password).
     * When [originalPassword] is set, the input PDF is opened with it (its own password).
     * The result is then honestly compared to the input by size.
     */
    private fun compressPdf(
        input: File,
        level: CompressLevel,
        password: String?,
        originalPassword: String?,
    ): File {
        val output = File(input.parentFile, "compressed_${System.currentTimeMillis()}.pdf")
        PDDocument.load(input, originalPassword.orEmpty()).use { doc ->
            recompressPageImages(doc, level)
            recompressUncompressedStreams(doc.documentCatalog.cosObject, mutableSetOf())
            if (!password.isNullOrBlank()) {
                val policy = StandardProtectionPolicy(password, password, AccessPermission())
                policy.encryptionKeyLength = 128
                doc.protect(policy)
            }
            doc.save(output)
        }
        return output
    }

    private fun recompressPageImages(doc: PDDocument, level: CompressLevel) {
        for (page in doc.pages) {
            val resources = page.resources ?: continue
            for (name in resources.xObjectNames.toList()) {
                val xObject = runCatching { resources.getXObject(name) }.getOrNull() ?: continue
                if (xObject !is PDImageXObject || xObject.isStencil) continue
                try {
                    val bitmap = xObject.getImage()
                    val bytes = ByteArrayOutputStream().also { bos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, level.jpegQuality, bos)
                    }.toByteArray()
                    resources.put(name, JPEGFactory.createFromByteArray(doc, bytes))
                    bitmap.recycle()
                } catch (_: Exception) {
                    // keep the original image if it cannot be re-encoded
                }
            }
        }
    }

    // Streams already using these filters are already compressed; re-encoding is pointless.
    private val COMPRESSED_FILTERS = setOf(
        COSName.FLATE_DECODE, COSName.LZW_DECODE, COSName.DCT_DECODE,
        COSName.CCITTFAX_DECODE, COSName.JBIG2_DECODE, COSName.JPX_DECODE,
    )

    @Suppress("DEPRECATION")
    private fun recompressUncompressedStreams(root: COSBase, visited: MutableSet<COSObject>) {
        when (root) {
            is COSStream -> try {
                val names = when (val filters = root.filters) {
                    is COSName -> listOf(filters)
                    is COSArray -> filters.filterIsInstance<COSName>()
                    else -> emptyList()
                }
                if (names.none { it in COMPRESSED_FILTERS }) {
                    val data = root.createInputStream().use { it.readBytes() }
                    root.filters = COSName.FLATE_DECODE
                    root.createOutputStream().use { it.write(data) }
                }
            } catch (_: Exception) {
                // keep the stream as-is if it cannot be re-encoded
            }

            is COSObject -> if (visited.add(root)) {
                recompressUncompressedStreams(root.`object`, visited)
            }

            is COSArray -> root.forEach { recompressUncompressedStreams(it, visited) }
            is COSDictionary -> root.values.forEach { recompressUncompressedStreams(it, visited) }
            else -> Unit
        }
    }
}

/** Re-zips a docx (zip) with best compression. Pure JVM, covered by a unit test. */
internal fun rezipDocx(input: File, output: File) {
    ZipInputStream(input.inputStream().buffered()).use { zin ->
        ZipOutputStream(output.outputStream().buffered()).use { zout ->
            zout.setLevel(Deflater.BEST_COMPRESSION)
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val newEntry = ZipEntry(entry.name)
                    newEntry.time = entry.time
                    zout.putNextEntry(newEntry)
                    zin.copyTo(zout)
                    zout.closeEntry()
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }
}

/** ASCII marker written by the PDF writer when a document is encrypted. */
private val PDF_ENCRYPT_MARKER = "/Encrypt"

/** Compound File Binary (OLE2) signature of password-encrypted Word files. */
internal val OLE2_MAGIC = byteArrayOf(
    (0xD0).toByte(), (0xCF).toByte(), 0x11, (0xE0).toByte(),
    (0xA1).toByte(), (0xB1).toByte(), 0x1A, (0xE1).toByte(),
)
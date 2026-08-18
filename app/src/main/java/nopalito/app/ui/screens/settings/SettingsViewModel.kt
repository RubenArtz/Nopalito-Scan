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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import nopalito.app.AppContainer
import nopalito.app.data.OcrLanguage
import nopalito.app.i18n.AppLanguage

data class SettingsUiState(
    val defaultColorMode: DefaultColorMode = DefaultColorMode.AUTO,
    val exportDirUri: String? = null,
    val exportDirName: String? = null,
    val installedOcrLanguages: Set<String> = emptySet(),
    val enabledOcrLanguages: Set<String> = emptySet(),
    val currentDownload: OcrDownloadUiState? = null,
    val autoDetect: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.BATCH,
    val selectedLanguage: AppLanguage = AppLanguage.default,
)

data class OcrDownloadUiState(
    val language: OcrLanguage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val failed: Boolean = false,
)

@Suppress("UNCHECKED_CAST")
class SettingsViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.settingsRepository
    private val languageRepository = container.languageRepository
    private val ocrLanguageRepo = container.ocrLanguageRepository
    private val logger = container.logger

    private val _installedLanguages = MutableStateFlow<Set<String>>(emptySet())
    private val _ocrDownload = MutableStateFlow<OcrDownloadUiState?>(null)
    private var downloadJob: Job? = null

    private val _dirName = MutableStateFlow<String?>(null)

    val uiState = combine(
        repo.defaultColorMode,
        repo.exportDirUri,
        _dirName,
        _installedLanguages,
        ocrLanguageRepo.enabledLanguages,
        _ocrDownload,
        repo.autoDetect,
        repo.captureMode,
        languageRepository.selectedLanguage,
    ) { args: Array<*> ->
        SettingsUiState(
            defaultColorMode = args[0] as DefaultColorMode,
            exportDirUri = args[1] as String?,
            exportDirName = args[2] as String?,
            installedOcrLanguages = args[3] as Set<String>,
            enabledOcrLanguages = args[4] as Set<String>,
            currentDownload = args[5] as OcrDownloadUiState?,
            autoDetect = args[6] as Boolean,
            captureMode = args[7] as CaptureMode,
            selectedLanguage = args[8] as AppLanguage,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsUiState(),
    )

    private suspend fun refreshInstalledLanguages() {
        _installedLanguages.value = ocrLanguageRepo.getInstalledLanguages()
    }

    init {
        viewModelScope.launch {
            refreshInstalledLanguages()
        }
    }

    fun setDefaultColorMode(pref: DefaultColorMode) {
        viewModelScope.launch {
            repo.setDefaultColorMode(pref)
        }
    }

    fun setExportDirUri(uri: String?) {
        viewModelScope.launch {
            repo.setExportDirUri(uri)
        }
    }

    fun refreshExportDirName() {
        viewModelScope.launch {
            val uri = repo.exportDirUri.first()
            _dirName.value = uri?.let { repo.resolveExportDirName(it) }
        }
    }

    fun onLanguageClick(code: String) {
        if (uiState.value.installedOcrLanguages.contains(code)) {
            setOcrLanguageEnabled(code, !uiState.value.enabledOcrLanguages.contains(code))
        } else {
            installLanguage(code)
        }
    }

    fun installLanguage(code: String) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _ocrDownload.value = OcrDownloadUiState(OcrLanguage(code))
            try {
                ocrLanguageRepo.downloadLanguage(code) { progress ->
                    _ocrDownload.value =
                        _ocrDownload.value?.copy(
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                        )
                }
                ocrLanguageRepo.setLanguageEnabled(code, true)
                refreshInstalledLanguages()
                _ocrDownload.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("OcrDownload", "Download failed for $code", e)
                _ocrDownload.value = _ocrDownload.value?.copy(failed = true)
            }
        }
    }

    fun cancelOcrDownload() {
        downloadJob?.cancel()
        _ocrDownload.value = null
    }

    fun setOcrLanguageEnabled(code: String, enabled: Boolean) {
        viewModelScope.launch {
            ocrLanguageRepo.setLanguageEnabled(code, enabled)
        }
    }

    fun onRemoveLanguage(code: String) {
        viewModelScope.launch {
            ocrLanguageRepo.deleteLanguage(code)
            refreshInstalledLanguages()
        }
    }

    fun setAutoDetect(enabled: Boolean) {
        viewModelScope.launch {
            repo.setAutoDetect(enabled)
        }
    }

    fun setCaptureMode(mode: CaptureMode) {
        viewModelScope.launch {
            repo.setCaptureMode(mode)
        }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            languageRepository.setSelectedLanguage(language)
        }
    }
}
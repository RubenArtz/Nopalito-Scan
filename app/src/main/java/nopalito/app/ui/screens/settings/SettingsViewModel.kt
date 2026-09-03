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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nopalito.app.AppContainer
import nopalito.app.data.OcrLanguage
import nopalito.app.i18n.AppLanguage
import nopalito.app.i18n.LocaleNormalizer
import nopalito.app.ui.screens.cloud.data.CloudRepository

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
    /** True when the last attempt to sync the language to the cloud failed. */
    val cloudLanguageSyncFailed: Boolean = false,
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

    private val _ocrDownload = MutableStateFlow<OcrDownloadUiState?>(null)
    private var downloadJob: Job? = null

    private val _dirName = MutableStateFlow<String?>(null)

    /**
     * Cloud repository for the language sync, created on first use: Settings
     * works fully offline and the sync is best-effort, so a missing/broken
     * cloud configuration must never break the screen.
     */
    private val cloudRepository: CloudRepository? by lazy {
        runCatching { CloudRepository(container.applicationContext) }.getOrNull()
    }

    private val _cloudLanguageSyncFailed = MutableStateFlow(false)

    val uiState = combine(
        repo.defaultColorMode,
        repo.exportDirUri,
        _dirName,
        ocrLanguageRepo.installedLanguagesFlow,
        ocrLanguageRepo.enabledLanguages,
        _ocrDownload,
        repo.autoDetect,
        repo.captureMode,
        languageRepository.selectedLanguage,
        _cloudLanguageSyncFailed,
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
            cloudLanguageSyncFailed = args[9] as Boolean,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsUiState(),
    )

    private suspend fun refreshInstalledLanguages() {
        ocrLanguageRepo.refreshInstalledLanguagesFlow()
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

    /**
     * Applies a language change: the local selection is saved FIRST (the UI
     * reflects it immediately via [languageRepository]), then the preference
     * is synced to the backend so account emails use the same language.
     * A failed sync never reverts the local choice — it only raises a hint.
     */
    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            languageRepository.setSelectedLanguage(language)
            syncLanguageToBackend(language)
        }
    }

    private suspend fun syncLanguageToBackend(language: AppLanguage) {
        val repository = cloudRepository
        if (repository == null) {
            _cloudLanguageSyncFailed.value = false
            return
        }
        val code = LocaleNormalizer.normalizeForBackend(language.code)
        _cloudLanguageSyncFailed.value = try {
            repository.updateUserLanguage(code).isFailure
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Language sync to backend failed for $code", e)
            true
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
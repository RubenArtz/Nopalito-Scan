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

package nopalito.app

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nopalito.app.data.*
import nopalito.app.domain.ImageSegmentationService
import nopalito.app.domain.OcrService
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.LanguageRepository
import nopalito.app.i18n.LanguageViewModel
import nopalito.app.i18n.LegalConsentRepository
import nopalito.app.platform.AndroidDocxWriter
import nopalito.app.platform.AndroidImageLoader
import nopalito.app.platform.AndroidPdfWriter
import nopalito.app.push.FcmTokenSync
import nopalito.app.ui.screens.camera.CameraViewModel
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.network.CloudApiClient
import nopalito.app.ui.screens.cloud.security.BiometricPromptHost
import nopalito.app.ui.screens.history.FairScanDatabase
import nopalito.app.ui.screens.history.HistoryRepository
import nopalito.app.ui.screens.history.HistoryViewModel
import nopalito.app.ui.screens.qr.QrScanRepository
import nopalito.app.ui.screens.qr.QrScannerViewModel
import nopalito.app.ui.screens.settings.SettingsRepository
import nopalito.app.ui.screens.settings.SettingsViewModel
import nopalito.app.ui.screens.tools.convert.ConvertViewModel
import nopalito.app.ui.screens.tools.core.ToolTransfer
import nopalito.app.ui.screens.tools.deletepages.DeletePagesViewModel
import nopalito.app.ui.screens.tools.extract.ExtractViewModel
import nopalito.app.ui.screens.tools.organizer.OrganizerViewModel
import nopalito.app.ui.screens.tools.passwordprotect.PasswordProtectViewModel
import nopalito.app.ui.screens.tools.qrgenerator.QrGeneratorViewModel
import nopalito.app.ui.screens.tools.reorder.ReorderViewModel
import java.io.File

class NopalitoApp : Application() {
    lateinit var appContainer: AppContainer

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        appContainer.cleanOrphanSessions()
        // Rebase the locale for every Activity before any UI is drawn.
        AppLocaleOverride.locale = appContainer.languageRepository.initialLanguage().locale
    }
}

const val THUMBNAIL_SIZE_DP = 120

private val Context.dataStore by preferencesDataStore(name = "fairscan_settings")

class AppContainer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheDir = context.cacheDir
    private val dataStore = context.dataStore
    val applicationContext: Context get() = context
    val preparationDir = File(context.cacheDir, "pdfs")
    val exportsBackupDir = File(context.filesDir, "exports_backup")
    val ocrLanguageRepository =
        OcrLanguageRepository(dataStore, File(context.filesDir, "tesseract/tessdata"))
    val ocrService = OcrService(ocrLanguageRepository, scope)
    val fileManager = FileManager(
        preparationDir,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        AndroidPdfWriter(ocrService, context.assets),
        AndroidDocxWriter(ocrService)
    )
    val logRepository = LogRepository(File(context.filesDir, "logs.txt"))
    val logger = FileLogger(logRepository)
    val imageSegmentationService = ImageSegmentationService(context, logger)
    val imageLoader = AndroidImageLoader(context.contentResolver)
    val settingsRepository = SettingsRepository(context, dataStore)
    val languageRepository = LanguageRepository(dataStore)
    val legalConsentRepository = LegalConsentRepository(dataStore)
    val permissionsRepository = PermissionsRepository(dataStore)
    val historyRepository =
        HistoryRepository(FairScanDatabase.getDatabase(context).exportHistoryDao())
    val qrScanRepository =
        QrScanRepository(FairScanDatabase.getDatabase(context).qrScanDao())
    val qrScansDir = File(context.filesDir, "qr_scans")

    val cloudSessionManager: nopalito.app.ui.screens.cloud.data.CloudSessionManager by lazy {
        nopalito.app.ui.screens.cloud.data.CloudSessionManager.getInstance(context)
            .also { it.initialize() }
    }

    /**
     * Push notifications (FCM): keeps the backend's device registration in
     * sync with this install. Registered on real sign-in and on FCM token
     * rotation, revoked before logout clears the session. The Firebase SDK
     * uses the public google-services.json config; the secret service account
     * lives only on the backend.
     */
    val fcmTokenSync: FcmTokenSync by lazy { FcmTokenSync.getInstance(context) }

    /** Pushes scanned QR/barcodes to the cloud history when authenticated. */
    val cloudScanUploader by lazy {
        nopalito.app.ui.screens.cloud.data.CloudScanUploader(context)
    }

    /** Bridge PasswordProtect → Compressor (minimal data, no navigation coupling). */
    val toolTransfer = ToolTransfer()

    init {
        scope.launch { imageSegmentationService.initialize() }
        scope.launch { ocrService.initialize() }
        scope.launch { installDefaultOcrLanguages() }

        // ── Push notifications: keep the backend device registration in sync ──
        val tokenProvider = CloudApiClient.getInstance(context).tokenProviderInstance
        // Diagnostic sink: push events go to the support log (About → Copy logs).
        fcmTokenSync.setDiagnosticSink { line -> logRepository.log("Push", line) }
        // Diagnostic sink: raw biometric prompt errors (code + vendor message)
        // go to the support log so device-specific failures are diagnosable.
        BiometricPromptHost.setErrorSink { code, message ->
            logRepository.log("Biometric", "prompt error code=$code msg=\"$message\"")
        }
        // Register at startup when the notification permission is granted
        // (permission-based install; no cloud session required).
        fcmTokenSync.syncOnAppStart()
        // Real sign-in → re-register so the backend REBINDS the device to the
        // user (the Bearer JWT upgrades the anonymous binding).
        tokenProvider.onLogin { scope.launch { fcmTokenSync.onSignedIn() } }
    }

    private suspend fun installDefaultOcrLanguages() {
        val defaultCodes = setOf("eng", "spa")
        val installed = ocrLanguageRepository.getInstalledLanguages()
        val toDownload = defaultCodes - installed
        for (code in toDownload) {
            try {
                ocrLanguageRepository.downloadLanguage(code) { /* silent progress */ }
                ocrLanguageRepository.setLanguageEnabled(code, true)
            } catch (e: Exception) {
                logger.e("OcrInit", "Failed to download default language $code", e)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified VM : ViewModel> viewModelFactory(
        crossinline create: (AppContainer) -> VM
    ) = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            return create(this@AppContainer) as T
        }
    }

    val cameraViewModelFactory = viewModelFactory { CameraViewModel(it) }
    val settingsViewModelFactory = viewModelFactory { SettingsViewModel(it) }
    val languageViewModelFactory = viewModelFactory {
        LanguageViewModel(it.languageRepository, it.legalConsentRepository)
    }
    val permissionsViewModelFactory = viewModelFactory { PermissionsViewModel(it.permissionsRepository) }
    val historyViewModelFactory = viewModelFactory { HistoryViewModel(it) }
    val qrScannerViewModelFactory =
        viewModelFactory { QrScannerViewModel(it.qrScanRepository) }
    val qrGeneratorViewModelFactory = viewModelFactory {
        QrGeneratorViewModel(
            context = it.applicationContext as Application,
            repository = CloudRepository(it.applicationContext),
            scanRepository = it.qrScanRepository,
            qrScansDir = it.qrScansDir,
            cloudScanUploader = it.cloudScanUploader,
        )
    }
    val passwordProtectViewModelFactory = viewModelFactory { PasswordProtectViewModel(it) }
    val convertViewModelFactory = viewModelFactory { ConvertViewModel(it) }
    val extractViewModelFactory = viewModelFactory { ExtractViewModel(it) }
    val reorderViewModelFactory = viewModelFactory { ReorderViewModel(it) }
    val deletePagesViewModelFactory = viewModelFactory { DeletePagesViewModel(it) }
    val organizePagesViewModelFactory = viewModelFactory { OrganizerViewModel(it) }

    fun cleanOrphanSessions() {
        val sessionsRoot = sessionsRoot()
        if (!sessionsRoot.exists()) return

        val now = System.currentTimeMillis()

        sessionsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { dir ->
                if (isOldSession(dir, now)) {
                    dir.deleteRecursively()
                }
            }
    }

    fun sessionsRoot(): File = File(cacheDir, "sessions")

    private fun isOldSession(dir: File, now: Long): Boolean {
        val lastModified = dir.lastModified()
        return now - lastModified > 24 * 60 * 60 * 1000 // 24h
    }
}

class SessionViewModelFactory(
    private val application: Application,
    private val launchMode: LaunchMode,
    private val appContainer: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SessionViewModel(application, launchMode, appContainer) as T
    }
}
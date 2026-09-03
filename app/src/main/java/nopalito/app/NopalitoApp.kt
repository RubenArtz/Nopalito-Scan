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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nopalito.app.data.FileLogger
import nopalito.app.data.FileManager
import nopalito.app.data.LogRepository
import nopalito.app.data.OcrLanguage
import nopalito.app.data.OcrLanguageRepository
import nopalito.app.data.PermissionsRepository
import nopalito.app.data.PermissionsViewModel
import nopalito.app.data.stats.StatsRepository
import nopalito.app.domain.ImageSegmentationService
import nopalito.app.domain.OcrService
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.LanguageRepository
import nopalito.app.i18n.LanguageViewModel
import nopalito.app.i18n.LegalConsentRepository
import nopalito.app.i18n.LocaleNormalizer
import nopalito.app.platform.AndroidDocxWriter
import nopalito.app.platform.AndroidImageLoader
import nopalito.app.platform.AndroidPdfWriter
import nopalito.app.push.FcmTokenSync
import nopalito.app.ui.screens.camera.CameraViewModel
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.network.CloudApiClient
import nopalito.app.ui.screens.cloud.security.BiometricPromptHost
import nopalito.app.ui.screens.history.HistoryRepository
import nopalito.app.ui.screens.history.HistoryViewModel
import nopalito.app.ui.screens.history.NopalitoScanDatabase
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
import kotlin.time.Duration.Companion.milliseconds

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

private val Context.dataStore by preferencesDataStore(name = "nopalitoscan_settings")

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
    val statsRepository by lazy {
        StatsRepository(NopalitoScanDatabase.getDatabase(context).statsDao())
    }
    val fileManager by lazy {
        FileManager(
            preparationDir,
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            AndroidPdfWriter(ocrService, context.assets),
            AndroidDocxWriter(ocrService),
            statsRepository = statsRepository
        )
    }
    val logRepository = LogRepository(File(context.filesDir, "logs.txt"))
    val logger = FileLogger(logRepository)
    val imageSegmentationService = ImageSegmentationService(context, logger)
    val imageLoader = AndroidImageLoader(context.contentResolver)
    val settingsRepository = SettingsRepository(context, dataStore)
    val languageRepository = LanguageRepository(dataStore)
    val legalConsentRepository = LegalConsentRepository(dataStore)
    val permissionsRepository = PermissionsRepository(dataStore)
    val historyRepository =
        HistoryRepository(NopalitoScanDatabase.getDatabase(context).exportHistoryDao())
    val qrScanRepository =
        QrScanRepository(NopalitoScanDatabase.getDatabase(context).qrScanDao())
    val qrScansDir = File(context.filesDir, "qr_scans")

    val cloudSessionManager: nopalito.app.ui.screens.cloud.data.CloudSessionManager by lazy {
        nopalito.app.ui.screens.cloud.data.CloudSessionManager.getInstance(context)
            .also { it.initialize() }
    }

    val billingEntitlementManager: nopalito.app.billing.BillingEntitlementManager by lazy {
        nopalito.app.billing.BillingEntitlementManager.initInstance(
            context = context.applicationContext,
            repository = nopalito.app.billing.BillingRepository(context.applicationContext),
            scope = scope,
            tokenProvider = nopalito.app.ui.screens.cloud.network.CloudApiClient.getInstance(context).tokenProviderInstance
        )
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

        // ── Billing entitlement (Fase 4): single global coordinator ──
        // ProcessLifecycleOwner for foreground, CloudSessionManager for session restore/login/logout/account switch
        scope.launch {
            try {
                // Single lifecycle observer for foreground
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                    object : androidx.lifecycle.DefaultLifecycleObserver {
                        override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                            billingEntitlementManager.onAppForeground()
                        }
                    }
                )
            } catch (_: Exception) {
            }
            // Collect invalidation events (semantic 403)
            try {
                billingEntitlementManager.collectInvalidationEvents()
            } catch (_: Exception) {
            }
            // Observe session state for restored/login/logout/switch
            try {
                cloudSessionManager.state.collect { state ->
                    when (state) {
                        is nopalito.app.ui.screens.cloud.data.CloudSessionState.Authenticated -> {
                            // Distinguish cold restore vs explicit login via currentUserId comparison
                            billingEntitlementManager.onSessionRestored()
                        }

                        is nopalito.app.ui.screens.cloud.data.CloudSessionState.Unauthenticated -> {
                            billingEntitlementManager.onLogout()
                        }

                        else -> {}
                    }
                }
            } catch (_: Exception) {
            }
        }
        // Extra safety: TokenProvider direct callbacks for login/account switch before state flow emits
        scope.launch {
            runCatching {
                CloudApiClient.getInstance(context).tokenProviderInstance
                    .onLogin {
                        // TokenProvider fires only on real sign-in with user payload
                        scope.launch { billingEntitlementManager.onLogin() }
                    }
            }
        }

        // ── Push notifications: keep the backend device registration in sync ──
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
        // Sync preferred_language to the backend when a session exists.
        // Fire-and-forget: never blocks the startup path if the domain is down.
        syncPreferredLanguageOnStartup()
        // Also re-sync immediately after a real sign-in (fresh tokens).
        scope.launch {
            runCatching {
                CloudApiClient.getInstance(context).tokenProviderInstance
                    .onLogin { syncPreferredLanguageOnStartup() }
            }.onFailure {
                // No-op: startup language sync is best-effort.
            }
        }
        // Real sign-in → re-register so the backend REBINDS the device to the
        // user (the Bearer JWT upgrades the anonymous binding).
        //
        // The token provider opens the encrypted cloud-token store, which can
        // be temporarily unusable (Keystore trouble); resolving it here on a
        // background thread and best-effort keeps that off the critical
        // Application.onCreate path — without a session the callback would be
        // pointless anyway.
        scope.launch {
            runCatching {
                CloudApiClient.getInstance(context).tokenProviderInstance
                    .onLogin { scope.launch { fcmTokenSync.onSignedIn() } }
            }.onFailure {
                logger.e(
                    "AppContainer",
                    "Cloud token storage unavailable; push re-binding disabled",
                    it
                )
            }
        }
    }

    /**
     * Best-effort sync of the local language selection to the backend's
     * `users.preferred_language` column. Called on every cold start when a
     * cloud session exists, and also right after a real sign-in.
     *
     * Guarantees:
     *  - Never blocks the UI/Application.onCreate thread (runs in [scope] on IO).
     *  - Never throws to the caller (all failures are logged and swallowed).
     *  - Times out quickly if the domain/DNS is down (10s wall-time cap) so
     *    the app stays fully usable offline.
     *  - Uses [nopalito.app.ui.screens.cloud.data.CloudRepository.hasCloudSession]
     *    via [tokenProvider.hasSession] semantics internally (refresh token or
     *    biometric mode counts as a session, not just a live access token) so a
     *    stale access token is still refreshed and retried via [nopalito.app.ui.screens.cloud.network.AuthInterceptor].
     */
    fun syncPreferredLanguageOnStartup() {
        scope.launch(Dispatchers.IO) {
            try {
                val completed = withTimeoutOrNull(10_000L.milliseconds) {
                    // Gate: only when the user is actually logged in. Checking
                    // here avoids creating a CloudRepository / touching OkHttp
                    // when there is no session at all.
                    val hasSession = runCatching {
                        CloudApiClient.getInstance(context).tokenProviderInstance.hasSession()
                    }.getOrDefault(false)
                    if (!hasSession) return@withTimeoutOrNull true

                    val language = languageRepository.selectedLanguage.first()
                    val code = LocaleNormalizer.normalizeForBackend(language.code)

                    val repo = runCatching { CloudRepository(context) }.getOrNull()
                        ?: return@withTimeoutOrNull true

                    val result = repo.updateUserLanguage(code)
                    if (result.isFailure) {
                        val err = result.exceptionOrNull()
                        if (err != null) {
                            logger.e("LanguageSync", "Startup language sync failed for $code", err)
                        } else {
                            android.util.Log.w(
                                "LanguageSync",
                                "Startup language sync failed for $code (unknown error)"
                            )
                        }
                    } else {
                        android.util.Log.d("LanguageSync", "Startup language sync ok for $code")
                    }
                    true
                }
                if (completed == null) {
                    android.util.Log.w(
                        "LanguageSync",
                        "Startup sync timed out (domain not responding), skipping"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("LanguageSync", "Startup language sync error", e)
            }
        }
    }

    private suspend fun installDefaultOcrLanguages() {
        val requiredCodes = getRequiredOcrCodes()
        val installed = ocrLanguageRepository.getInstalledLanguages()
        val toDownload = requiredCodes - installed
        for (code in toDownload) {
            try {
                ocrLanguageRepository.downloadLanguage(code) { /* silent progress */ }
                ocrLanguageRepository.setLanguageEnabled(code, true)
            } catch (e: Exception) {
                logger.e("OcrInit", "Failed to download default language $code", e)
            }
        }
        // Auto-repair: previous versions on validated WiFi enabled 100+ languages
        // (eng+spa+all others) causing Tesseract to take 3-4 min per PDF page.
        // Trim the enabled set back to the minimal base without touching files
        // the user may have installed manually beyond 6 languages.
        try {
            val enabled = ocrLanguageRepository.enabledLanguages.first()
            if (enabled.size > 6) {
                val extra = enabled - requiredCodes
                // Only auto-trim if the bloat looks like the old WiFi bug (many
                // languages enabled at once). Keep user's manual selection if
                // they enabled 7-8 languages deliberately is unlikely, but >10
                // is definitely the bug.
                if (extra.size >= 10) {
                    for (code in extra) {
                        ocrLanguageRepository.setLanguageEnabled(code, false)
                    }
                    android.util.Log.i(
                        "OcrInit",
                        "Trimmed OCR languages from ${enabled.size} to ${requiredCodes.size}"
                    )
                }
            }
        } catch (e: Exception) {
            logger.e("OcrInit", "Failed to trim OCR languages", e)
        }
    }

    /**
     * Determines which OCR languages to auto-install.
     * Only English, Spanish and device language are auto-enabled. Installing
     * 100+ languages on WiFi (previous behavior) made Tesseract init with
     * "afr+amh+ara+..." load dozens of traineddata files, causing PDF export
     * OCR to take 3-4 min per page. All languages remain available for manual
     * download in Settings, but are not auto-enabled.
     */
    private fun getRequiredOcrCodes(): Set<String> {
        val base = mutableSetOf("eng", "spa")
        deviceOcrCode()?.let { base.add(it) }
        return base
    }

    private fun deviceOcrCode(): String? {
        val lang = java.util.Locale.getDefault().language.lowercase(java.util.Locale.ROOT)
        val code = when (lang) {
            "en" -> "eng"
            "es" -> "spa"
            "pt" -> "por"
            "fr" -> "fra"
            "de" -> "deu"
            "it" -> "ita"
            "ar" -> "ara"
            "zh" -> "chi_sim"
            "ja" -> "jpn"
            "ko" -> "kor"
            "ru" -> "rus"
            "tr" -> "tur"
            "pl" -> "pol"
            "nl" -> "nld"
            "hi" -> "hin"
            else -> null
        }
        return code?.takeIf { it in OcrLanguage.AVAILABLE_LANGUAGE_CODES }
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
    val permissionsViewModelFactory =
        viewModelFactory { PermissionsViewModel(it.permissionsRepository) }
    val historyViewModelFactory = viewModelFactory { HistoryViewModel(it) }
    val statsViewModelFactory =
        viewModelFactory { nopalito.app.ui.screens.stats.StatsViewModel(it.statsRepository) }
    val qrScannerViewModelFactory =
        viewModelFactory { QrScannerViewModel(it.qrScanRepository) }
    val qrGeneratorViewModelFactory = viewModelFactory {
        QrGeneratorViewModel(
            context = it.applicationContext as Application,
            repository = CloudRepository(it.applicationContext),
            scanRepository = it.qrScanRepository,
            qrScansDir = it.qrScansDir,
            cloudScanUploader = it.cloudScanUploader,
            statsRepository = it.statsRepository,
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
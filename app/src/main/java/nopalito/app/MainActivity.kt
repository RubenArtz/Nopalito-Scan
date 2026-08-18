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

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.Q
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nopalito.app.data.FileLogger
import nopalito.app.data.ImageRepository
import nopalito.app.data.PermissionsViewModel
import nopalito.app.i18n.AppLanguage
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.LanguageViewModel
import nopalito.app.push.PushActions
import nopalito.app.ui.Navigation
import nopalito.app.ui.Screen
import nopalito.app.ui.Screen.Main.*
import nopalito.app.ui.components.TopActionButtons
import nopalito.app.ui.components.rememberCameraPermissionState
import nopalito.app.ui.screens.LibrariesScreen
import nopalito.app.ui.screens.ResumeScanScreen
import nopalito.app.ui.screens.about.AboutEvent
import nopalito.app.ui.screens.about.AboutScreen
import nopalito.app.ui.screens.about.AboutViewModel
import nopalito.app.ui.screens.about.createEmailWithImageIntent
import nopalito.app.ui.screens.camera.CAMERA_IMPORT_MIME_TYPES
import nopalito.app.ui.screens.camera.CameraEvent
import nopalito.app.ui.screens.camera.CameraScreen
import nopalito.app.ui.screens.camera.CameraViewModel
import nopalito.app.ui.screens.cloud.CloudHost
import nopalito.app.ui.screens.crop.CropScreen
import nopalito.app.ui.screens.document.DocumentScreen
import nopalito.app.ui.screens.export.*
import nopalito.app.ui.screens.history.HistoryScreen
import nopalito.app.ui.screens.history.HistoryViewModel
import nopalito.app.ui.screens.onboarding.OnboardingScreen
import nopalito.app.ui.screens.onboarding.PermissionsOnboardingScreen
import nopalito.app.ui.screens.qr.QrHistoryScreen
import nopalito.app.ui.screens.qr.QrScannerViewModel
import nopalito.app.ui.screens.settings.OcrLanguagesScreen
import nopalito.app.ui.screens.settings.SettingsScreen
import nopalito.app.ui.screens.settings.SettingsUiState
import nopalito.app.ui.screens.settings.SettingsViewModel
import nopalito.app.ui.screens.tools.*
import nopalito.app.ui.screens.tools.convert.ConvertResult
import nopalito.app.ui.screens.tools.convert.ConvertScreen
import nopalito.app.ui.screens.tools.convert.ConvertViewModel
import nopalito.app.ui.screens.tools.core.ToolTransfer
import nopalito.app.ui.screens.tools.deletepages.DeletePagesResult
import nopalito.app.ui.screens.tools.deletepages.DeletePagesScreen
import nopalito.app.ui.screens.tools.deletepages.DeletePagesViewModel
import nopalito.app.ui.screens.tools.extract.ExtractResult
import nopalito.app.ui.screens.tools.extract.ExtractScreen
import nopalito.app.ui.screens.tools.extract.ExtractViewModel
import nopalito.app.ui.screens.tools.organizer.OrganizerResult
import nopalito.app.ui.screens.tools.organizer.OrganizerScreen
import nopalito.app.ui.screens.tools.organizer.OrganizerViewModel
import nopalito.app.ui.screens.tools.passwordprotect.PasswordProtectResult
import nopalito.app.ui.screens.tools.passwordprotect.PasswordProtectScreen
import nopalito.app.ui.screens.tools.passwordprotect.PasswordProtectViewModel
import nopalito.app.ui.screens.tools.qrgenerator.QrGeneratorScreen
import nopalito.app.ui.screens.tools.reorder.ReorderResult
import nopalito.app.ui.screens.tools.reorder.ReorderScreen
import nopalito.app.ui.screens.tools.reorder.ReorderViewModel
import nopalito.app.ui.theme.FairScanTheme
import org.opencv.android.OpenCVLoader
import java.io.File
import java.time.Instant

class MainActivity : FragmentActivity() {

    private lateinit var cameraViewModel: CameraViewModel
    private lateinit var viewModel: MainViewModel
    private lateinit var languageViewModel: LanguageViewModel
    private lateinit var appContainer: AppContainer

    /** Populated once setContent builds the navigation callbacks; used to
     *  deep-link from push notification taps. */
    private var activeNavigation: Navigation? = null

    /** Deep-link action pending from a notification tap (cold start or onNewIntent). */
    private val pendingPush = mutableStateOf<PendingPush?>(null)

    private val logger: FileLogger by lazy {
        (application as NopalitoApp).appContainer.logger
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleOverride.applyTo(newBase, AppLocaleOverride.locale))
    }

    @RequiresApi(Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initLibraries()

        appContainer = (application as NopalitoApp).appContainer
        val launchMode = resolveLaunchMode(intent)

        // Deep-link from a notification tap: navigate once the composition is up.
        pendingPush.value = pushActionFromIntent(intent)

        val sessionViewModel: SessionViewModel by viewModels {
            SessionViewModelFactory(
                application = application,
                launchMode = launchMode,
                appContainer = appContainer
            )
        }

        val imageRepository = sessionViewModel.imageRepository
        viewModel = viewModels<MainViewModel> {
            appContainer.viewModelFactory {
                MainViewModel(
                    imageRepository,
                    sessionViewModel.overlayRepository,
                    appContainer.logger
                )
            }
        }.value
        val exportViewModel: ExportViewModel by viewModels {
            appContainer.viewModelFactory {
                ExportViewModel(appContainer, imageRepository)
            }
        }
        val aboutViewModel: AboutViewModel by viewModels {
            appContainer.viewModelFactory {
                AboutViewModel(appContainer, imageRepository)
            }
        }
        cameraViewModel = viewModels<CameraViewModel> { appContainer.cameraViewModelFactory }.value

        val settingsViewModel: SettingsViewModel
                by viewModels { appContainer.settingsViewModelFactory }
        languageViewModel = viewModels<LanguageViewModel> {
            appContainer.languageViewModelFactory
        }.value
        val permissionsViewModel: PermissionsViewModel
                by viewModels { appContainer.permissionsViewModelFactory }
        val historyViewModel: HistoryViewModel
                by viewModels { appContainer.historyViewModelFactory }
        val toolsViewModelFactory = appContainer.viewModelFactory { ToolsViewModel(it) }
        lifecycleScope.launch(Dispatchers.IO) {
            exportViewModel.cleanUpOldPreparedFiles(1000 * 3600)
        }
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val logger = appContainer.logger
            val context = LocalContext.current
            val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
            val liveAnalysisState by cameraViewModel.liveAnalysisState.collectAsStateWithLifecycle()
            val importState by cameraViewModel.importState.collectAsStateWithLifecycle()
            val document by viewModel.documentUiModel.collectAsStateWithLifecycle()
            val documentUiState by viewModel.documentUiState.collectAsStateWithLifecycle()
            val cropInitialState by viewModel.cropInitState.collectAsStateWithLifecycle()
            val exportUiState by exportViewModel.uiState.collectAsStateWithLifecycle()
            val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val aboutUiState by aboutViewModel.uiState.collectAsStateWithLifecycle()
            val cameraPermission = rememberCameraPermissionState()
            CollectCameraEvents(cameraViewModel, viewModel)
            CollectExportEvents(context, exportViewModel)
            CollectAboutEvents(context, aboutViewModel, imageRepository)

            FairScanTheme {
                val languageState by languageViewModel.uiState.collectAsStateWithLifecycle()
                val permissionsOnboardingDone by
                permissionsViewModel.isOnboardingDone.collectAsStateWithLifecycle()
                if (!languageState.isConfigured || !languageState.isLegalComplete) {
                    var selectedLanguageCode by rememberSaveable {
                        mutableStateOf(languageState.selectedLanguage.code)
                    }
                    var legalAccepted by rememberSaveable { mutableStateOf(false) }
                    OnboardingScreen(
                        languages = AppLanguage.supported,
                        selected = AppLanguage.fromCode(selectedLanguageCode),
                        onLanguageSelected = { selectedLanguageCode = it.code },
                        accepted = legalAccepted,
                        onAcceptedChange = { legalAccepted = it },
                        onContinue = { finishOnboarding(AppLanguage.fromCode(selectedLanguageCode)) },
                        onExit = {
                            if (launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF) {
                                setResult(RESULT_CANCELED)
                            }
                            finish()
                        },
                    )
                } else if (!permissionsOnboardingDone) {
                    // First run after the language selection: ask for the app's
                    // permissions one by one, then go to the main app.
                    PermissionsOnboardingScreen(
                        onComplete = {
                            permissionsViewModel.completeOnboarding()
                            // Notifications permission was (likely) granted in the
                            // last step: register the FCM device. Idempotent.
                            appContainer.fcmTokenSync.syncOnAppStart()
                        },
                    )
                } else {
                    val navigation = navigation(viewModel, launchMode)
                    activeNavigation = navigation
                    val push = pendingPush.value
                    LaunchedEffect(push) {
                        if (push != null) {
                            pendingPush.value = null
                            navigateToPushAction(push)
                        }
                    }
                    val onExportClick = if (launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF) {
                        {
                            lifecycleScope.launch {
                                try {
                                    val result = exportViewModel.generatePdfForExternalCall()
                                    sendActivityResult(result)
                                } catch (e: Exception) {
                                    showToast(getString(R.string.export_failed))
                                    appContainer.logger.e("MainActivity", "Export failed", e)
                                    return@launch
                                }
                                viewModel.startNewDocument()
                                finish()
                            }
                            Unit
                        }
                    } else {
                        navigation.toExportScreen
                    }

                    // Keeps each screen's saveable state (e.g. scroll position)
                    // alive while other overlay screens are on top of the stack.
                    val saveableStateHolder = rememberSaveableStateHolder()

                    when (currentScreen) {
                        null -> {
                            // waiting to load pages to get an initial screen
                        }

                        is ResumeScan -> {
                            ResumeScanScreen(
                                currentDocument = documentUiState,
                                onResumeScan = navigation.toCameraScreen,
                                onStartNewScan = {
                                    viewModel.startNewDocument()
                                    navigation.toCameraScreen()
                                }
                            )
                        }

                        is Camera -> {
                            val pickMultiple = rememberLauncherForActivityResult(
                                OpenMultipleDocuments()
                            ) { uris ->
                                cameraViewModel.importPhotos(uris)
                            }
                            val qrScanMode by cameraViewModel.qrScanMode.collectAsStateWithLifecycle()
                            val ineMode by viewModel.ineMode.collectAsStateWithLifecycle()
                            CameraScreen(
                                viewModel,
                                cameraViewModel,
                                navigation,
                                liveAnalysisState,
                                importState,
                                onImageAnalyzed = { image -> cameraViewModel.liveAnalysis(image) },
                                onFinalizePressed = if (launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF) {
                                    onExportClick
                                } else {
                                    { viewModel.navigateTo(Document()) }
                                },
                                cameraPermission = cameraPermission,
                                onImportClicked = {
                                    cameraViewModel.onImportClicked()
                                    if (qrScanMode || ineMode) {
                                        // QR and INE modes only make sense with photos.
                                        pickMultiple.launch(arrayOf("image/*"))
                                    } else {
                                        pickMultiple.launch(CAMERA_IMPORT_MIME_TYPES)
                                    }
                                },
                                autoDetect = settingsUiState.autoDetect,
                                onAutoDetectChanged = { enabled ->
                                    settingsViewModel.setAutoDetect(
                                        enabled
                                    )
                                },
                                captureMode = settingsUiState.captureMode,
                                onCaptureModeChanged = { mode -> settingsViewModel.setCaptureMode(mode) },
                            )
                        }

                        is EditImage -> {
                            CropScreen(
                                pageId = documentUiState.currentPage?.key?.pageId ?: "",
                                initState = cropInitialState,
                                navigation = navigation,
                                onUpdatePageQuad = { quad -> viewModel.setCurrentPageUserQuad(quad) },
                            )
                        }

                        is Document -> {
                            DocumentScreen(
                                uiState = documentUiState,
                                navigation = navigation,
                                onExportClick = onExportClick,
                                onDeleteImage = { viewModel.deleteCurrentPage() },
                                onRotateImage = { clockwise -> viewModel.rotateCurrentPage(clockwise) },
                                onToggleColorMode = { viewModel.toggleCurrentPageColorMode() },
                                onCropClick = { viewModel.onClickOnCropButton() },
                                onPageReorder = { id, newIndex -> viewModel.movePage(id, newIndex) },
                                onPageSelected = viewModel::onPageSelected,
                                onNewSession = {
                                    viewModel.startNewDocument()
                                    navigation.toCameraScreen()
                                },
                                onRetakePage = { viewModel.retakePage() },
                                onUpdateSignature = { pageId, state, bitmap ->
                                    viewModel.updateSignature(pageId, state, bitmap)
                                },
                                onUpdateDateOverlay = { pageId, dateText, position ->
                                    viewModel.updateDateOverlay(pageId, dateText, position)
                                },
                                onUpdateSignaturePosition = { pageId, position ->
                                    viewModel.updateSignaturePosition(pageId, position)
                                },
                                onUpdateDatePosition = { pageId, position ->
                                    viewModel.updateDatePosition(pageId, position)
                                },
                                onUpdateSignatureScale = { pageId, scale ->
                                    viewModel.updateSignatureScale(pageId, scale)
                                },
                                onUpdateDateScale = { pageId, scale ->
                                    viewModel.updateDateScale(pageId, scale)
                                },
                                onUpdateSignatureRotation = { pageId, degrees ->
                                    viewModel.updateSignatureRotation(pageId, degrees)
                                },
                                onUpdateDateRotation = { pageId, degrees ->
                                    viewModel.updateDateRotation(pageId, degrees)
                                },
                                onUpdateDateStyle = { pageId, style ->
                                    viewModel.updateDateStyle(pageId, style)
                                },
                                onDeleteSignatureOverlay = { pageId ->
                                    viewModel.removeSignatureOverlay(pageId)
                                },
                                onDeleteDateOverlay = { pageId ->
                                    viewModel.removeDateOverlay(pageId)
                                },
                            )
                        }

                        is Export -> {
                            LaunchedEffect(
                                documentUiState.pageOverlays,
                                viewModel.isIneDocument.collectAsState().value
                            ) {
                                exportViewModel.pageOverlays = documentUiState.pageOverlays
                                exportViewModel.setIneDocument(viewModel.isIneDocument.value)
                                exportViewModel.prepareExportIfNeeded()
                            }
                            ExportScreenWrapper(
                                navigation = navigation,
                                uiState = exportUiState,
                                currentDocument = document,
                                exportActions = ExportActions(
                                    prepareExportIfNeeded = exportViewModel::prepareExportIfNeeded,
                                    setFilename = exportViewModel::setFilename,
                                    setFormat = exportViewModel::setFormat,
                                    setQuality = exportViewModel::setQuality,
                                    setProtectWithPassword = exportViewModel::setProtectWithPassword,
                                    setPassword = exportViewModel::setPassword,
                                    generatePassword = exportViewModel::generatePassword,
                                    share = { exportViewModel.onShareClicked() },
                                    save = { exportViewModel.onSaveClicked() },
                                    open = { artifact -> openExportArtifact(artifact, logger) },
                                    cancelPreparationJob = exportViewModel::cancelPreparationJob,
                                    checkCloudAuth = exportViewModel::checkCloudAuth,
                                    uploadToCloud = exportViewModel::uploadToCloud,
                                    setIneExportScale = exportViewModel::setIneExportScale,
                                ),
                                onCloseScan = {
                                    exportViewModel.resetFilename()
                                    viewModel.startNewDocument()
                                    viewModel.navigateTo(Camera)
                                }
                            )
                        }

                        is Screen.Overlay.Libraries -> {
                            LibrariesScreen(onBack = navigation.back)
                        }

                        is Screen.Overlay.Settings -> {
                            SettingsScreenWrapper(
                                settingsUiState,
                                settingsViewModel,
                                navigation,
                                logger
                            )
                        }

                        is Screen.Overlay.OcrLanguages -> {
                            OcrLanguagesScreen(
                                uiState = settingsUiState,
                                onBack = navigation.back,
                                onLanguageClick = settingsViewModel::onLanguageClick,
                                onRemoveLanguage = settingsViewModel::onRemoveLanguage,
                                onCancelOcrDownload = settingsViewModel::cancelOcrDownload,
                            )
                        }

                        is Screen.Overlay.History -> {
                            HistoryScreen(
                                viewModel = historyViewModel,
                                onBack = navigation.back,
                                onOpenArtifact = { artifact ->
                                    openExportArtifact(artifact, logger)
                                },
                            )
                        }

                        is Screen.Overlay.QrHistory -> {
                            val qrViewModel: QrScannerViewModel = viewModel(
                                factory = appContainer.qrScannerViewModelFactory
                            )
                            QrHistoryScreen(
                                viewModel = qrViewModel,
                                onBack = navigation.back,
                            )
                        }

                        is Screen.Overlay.FairScanCloud -> {
                            CloudHost(
                                onBack = navigation.back,
                                cloudSessionManager = appContainer.cloudSessionManager
                            )
                        }

                        is Screen.Overlay.Tools -> saveableStateHolder.SaveableStateProvider("tools") {
                            ToolsScreen(
                                navigation = navigation,
                                onCompressClick = {
                                    viewModel.navigateTo(Screen.Overlay.ToolCompress(CompressTool.PDF))
                                },
                                onProtectPasswordClick = navigation.toPasswordProtectScreen,
                                onConvertClick = navigation.toConvertScreen,
                                onExtractPdfClick = navigation.toExtractScreen,
                                onReorderPdfClick = navigation.toReorderScreen,
                                onDeletePagesClick = navigation.toDeletePagesScreen,
                                onOrganizePagesClick = navigation.toOrganizePagesScreen,
                                onGenerateQrClick = navigation.toQrGeneratorScreen,
                            )
                        }

                        is Screen.Overlay.ToolCompress -> {
                            val compressScreen = currentScreen as Screen.Overlay.ToolCompress
                            val tool = compressScreen.tool
                            val toolsViewModel: ToolsViewModel = viewModel(
                                key = tool.name,
                                factory = toolsViewModelFactory
                            )
                            ToolCompressScreen(
                                viewModel = toolsViewModel,
                                tool = tool,
                                navigation = navigation,
                                onSwitchTool = navigation.switchTool,
                                onFilesPicked = { pickedTool, files ->
                                    appContainer.toolTransfer.request(
                                        ToolTransfer.Request(pickedTool, BatchMode.INDIVIDUAL, files, "")
                                    )
                                    viewModel.replaceCurrentScreen(Screen.Overlay.ToolCompress(pickedTool))
                                },
                                onShare = this::shareCompressedResults,
                                onOpen = this::openCompressedResults,
                            )
                        }

                        is Screen.Overlay.PasswordProtect -> {
                            val protectViewModel: PasswordProtectViewModel = viewModel(
                                factory = appContainer.passwordProtectViewModelFactory
                            )
                            PasswordProtectScreen(
                                viewModel = protectViewModel,
                                onBack = navigation.back,
                                topBarActions = {
                                    TopActionButtons(
                                        navigation = navigation,
                                        tint = Color.White,
                                        circleColor = Color.White.copy(alpha = 0.22f)
                                    )
                                },
                                onSendToCompressor = { request ->
                                    appContainer.toolTransfer.request(request)
                                    navigation.toToolCompress(request.tool)
                                },
                                onGoToCloud = navigation.toCloudScreen,
                                onShare = this::shareProtectedResults,
                                onOpen = this::openProtectedResults,
                            )
                        }

                        is Screen.Overlay.Convert -> {
                            val convertViewModel: ConvertViewModel = viewModel(
                                factory = appContainer.convertViewModelFactory
                            )
                            ConvertScreen(
                                viewModel = convertViewModel,
                                onBack = navigation.back,
                                topBarActions = {
                                    TopActionButtons(
                                        navigation = navigation,
                                        tint = Color.White,
                                        circleColor = Color.White.copy(alpha = 0.22f)
                                    )
                                },
                                onGoToCloud = navigation.toCloudScreen,
                                onShare = this::shareConvertedResults,
                                onOpen = this::openConvertedResults,
                            )
                        }

                        is Screen.Overlay.Extract -> {
                            val extractViewModel: ExtractViewModel = viewModel(
                                factory = appContainer.extractViewModelFactory
                            )
                            ExtractScreen(
                                viewModel = extractViewModel,
                                onBack = navigation.back,
                                onGoToCloud = navigation.toCloudScreen,
                                topBarActions = {
                                    TopActionButtons(
                                        navigation = navigation,
                                        tint = Color.White,
                                        circleColor = Color.White.copy(alpha = 0.22f)
                                    )
                                },
                                onShare = this::shareExtractResults,
                                onOpen = this::openExtractResults,
                            )
                        }

                        is Screen.Overlay.QrGenerator -> {
                            QrGeneratorScreen(navigation = navigation)
                        }

                        is Screen.Overlay.Reorder -> {
                            val reorderViewModel: ReorderViewModel = viewModel(
                                factory = appContainer.reorderViewModelFactory
                            )
                            ReorderScreen(
                                viewModel = reorderViewModel,
                                onBack = navigation.back,
                                topBarActions = {
                                    TopActionButtons(
                                        navigation = navigation,
                                        tint = Color.White,
                                        circleColor = Color.White.copy(alpha = 0.22f)
                                    )
                                },
                                onShare = this::shareReorderResults,
                                onOpen = this::openReorderResults,
                                onGoToCloud = navigation.toCloudScreen,
                            )
                        }

                        is Screen.Overlay.DeletePages -> {
                            val deletePagesViewModel: DeletePagesViewModel = viewModel(
                                factory = appContainer.deletePagesViewModelFactory
                            )
                            DeletePagesScreen(
                                viewModel = deletePagesViewModel,
                                onBack = navigation.back,
                                topBarActions = {
                                    TopActionButtons(
                                        navigation = navigation,
                                        tint = Color.White,
                                        circleColor = Color.White.copy(alpha = 0.22f)
                                    )
                                },
                                onShare = this::shareDeletePagesResults,
                                onOpen = this::openDeletePagesResults,
                                onGoToCloud = navigation.toCloudScreen,
                            )
                        }

                        is Screen.Overlay.OrganizePages -> {
                            val organizeViewModel: OrganizerViewModel = viewModel(
                                factory = appContainer.organizePagesViewModelFactory
                            )
                            OrganizerScreen(
                                viewModel = organizeViewModel,
                                onBack = navigation.back,
                                topBarActions = {
                                    TopActionButtons(
                                        navigation = navigation,
                                        tint = Color.White,
                                        circleColor = Color.White.copy(alpha = 0.22f)
                                    )
                                },
                                onShare = this::shareOrganizerResults,
                                onOpen = this::openOrganizerResults,
                                onGoToCloud = navigation.toCloudScreen,
                            )
                        }

                        Screen.Overlay.About -> {
                            LaunchedEffect(Unit) { aboutViewModel.refreshLastCapturedImageState() }
                            AboutScreen(
                                aboutUiState = aboutUiState,
                                onBack = navigation.back,
                                onCopyLogs = aboutViewModel::onCopyLogsClicked,
                                onContactWithLastImageClicked =
                                    aboutViewModel::onContactWithLastImageClicked,
                                onStartActivity = { startActivity(it) },
                                onViewLibraries = navigation.toLibrariesScreen,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun resolveLaunchMode(intent: Intent?): LaunchMode {
        return when (intent?.action) {
            "nopalito.app.action.SCAN_TO_PDF" -> LaunchMode.EXTERNAL_SCAN_TO_PDF
            "android.intent.action.GET_CONTENT" -> LaunchMode.EXTERNAL_SCAN_TO_PDF
            else -> LaunchMode.NORMAL
        }
    }

    /**
     * Handles cold-start AND restored notifications (taps while the activity is
     * already running / in the background). The pushed action is stored and
     * consumed by the navigation LaunchedEffect once the UI is composed.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pushActionFromIntent(intent)?.let { pendingPush.value = it }
    }

    /** Notification deep-link data carried by a tap. */
    private data class PendingPush(val action: String, val url: String?)

    /** Extracts a whitelisted push action from an intent (extra or FCM action). */
    private fun pushActionFromIntent(intent: Intent?): PendingPush? {
        if (intent == null) return null
        val action = PushActions.normalize(intent.getStringExtra(PushActions.EXTRA_PUSH_ACTION))
            ?: PushActions.normalize(intent.action) ?: return null
        val url = intent.getStringExtra(PushActions.EXTRA_URL)
        return PendingPush(action, url)
    }

    /**
     * Navigates to the screen bound to the notification action; `open_url`
     * opens the browser with the received URL. No-op for [PushActions.OPEN_APP];
     * `navigation` is non-null once setContent starts.
     */
    private fun navigateToPushAction(push: PendingPush) {
        when (push.action) {
            PushActions.OPEN_URL -> {
                if (PushActions.isHttpUrl(push.url)) {
                    startActivity(Intent(Intent.ACTION_VIEW, push.url?.toUri() ?: Uri.EMPTY))
                }
            }

            PushActions.OPEN_CLOUD -> activeNavigation?.toCloudScreen()
            PushActions.OPEN_SETTINGS -> activeNavigation?.toSettingsScreen?.let { it() }
            PushActions.OPEN_QR_HISTORY -> activeNavigation?.toQrHistoryScreen()
            PushActions.OPEN_TOOLS -> activeNavigation?.toToolsScreen()
            PushActions.OPEN_APP -> Unit
        }
    }

    /**
     * Completes the first-run flow: persists the chosen language, records legal
     * acceptance and — only when the chosen locale differs from the one currently
     * applied — recreates the activity to re-base the resources. The writes finish
     * before any recreation so the gate never flashes back to the onboarding screen.
     * The ViewModelStore (and thus the navigation state) survives the recreation.
     */
    private fun finishOnboarding(language: AppLanguage) {
        lifecycleScope.launch {
            appContainer.languageRepository.completeSelection(language)
            appContainer.legalConsentRepository.accept(Instant.now().toString())
            applyLocaleAndRecreate(language)
        }
    }

    /** Applies a language change coming from the Settings screen. */
    private fun changeLanguage(language: AppLanguage) {
        languageViewModel.selectLanguage(language)
        applyLocaleAndRecreate(language)
    }

    private fun applyLocaleAndRecreate(language: AppLanguage) {
        if (AppLocaleOverride.locale != language.locale) {
            AppLocaleOverride.locale = language.locale
            recreate()
        }
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun SettingsScreenWrapper(
        settingsUiState: SettingsUiState,
        settingsViewModel: SettingsViewModel,
        nav: Navigation,
        logger: FileLogger,
    ) {
        val launcher = rememberLauncherForActivityResult(
            contract = OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(uri, flags)
                    settingsViewModel.setExportDirUri(uri.toString())
                } catch (e: Exception) {
                    logger.e("Settings", "Failed to set export dir to $uri", e)
                    showToast(this.getString(R.string.error_file_picker_result))
                }
            }
        }
        LaunchedEffect(Unit) {
            settingsViewModel.refreshExportDirName()
        }
        SettingsScreen(
            settingsUiState,
            onDefaultColorModeChanged = { mode -> settingsViewModel.setDefaultColorMode(mode) },
            onChooseDirectoryClick = {
                try {
                    launcher.launch(null)
                } catch (e: Exception) {
                    val message = getString(R.string.error_file_picker_launch)
                    logger.e("Settings", message, e)
                    showToast(message)
                }
            },
            onResetExportDirClick = { settingsViewModel.setExportDirUri(null) },
            onLanguageSelected = { language ->
                settingsViewModel.setLanguage(language)
                changeLanguage(language)
            },
            navigation = nav,
        )
    }

    @Composable
    private fun CollectAboutEvents(
        context: Context,
        aboutViewModel: AboutViewModel,
        imageRepository: ImageRepository,
    ) {
        val clipboard = LocalClipboard.current
        val msgCopiedLogs = stringResource(R.string.copied_logs)
        LaunchedEffect(Unit) {
            aboutViewModel.events.collect { event ->
                when (event) {
                    is AboutEvent.CopyLogs -> {
                        clipboard.setClipEntry(
                            ClipData.newPlainText("FairScan logs", event.logs).toClipEntry()
                        )
                        showToast(msgCopiedLogs)
                    }

                    is AboutEvent.PrepareEmailWithLastImage -> {
                        val file = imageRepository.lastAddedSourceFile()
                        if (file != null) {
                            startActivity(createEmailWithImageIntent(context, file))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CollectExportEvents(
        context: Context,
        exportViewModel: ExportViewModel,
    ) {
        val storagePermissionLauncher = rememberLauncherForActivityResult(
            RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                exportViewModel.onSaveClicked()
            } else {
                showToast(this.getString(R.string.storage_permission_denied))
            }
        }
        LaunchedEffect(Unit) {
            exportViewModel.events.collect { event ->
                when (event) {
                    ExportEvent.RequestSave -> {
                        checkPermissionThen(storagePermissionLauncher) {
                            exportViewModel.onRequestSave(context)
                        }
                    }

                    is ExportEvent.Share -> {
                        share(event.result)
                    }
                }
            }
        }
    }

    @Composable
    private fun CollectCameraEvents(
        cameraViewModel: CameraViewModel,
        viewModel: MainViewModel,
    ) {
        LaunchedEffect(Unit) {
            cameraViewModel.events.collect { event ->
                when (event) {
                    is CameraEvent.ImageCaptured -> viewModel.handleImageCaptured(event.page)
                    // ImportError is shown as a styled snackbar inside CameraScreen.
                    is CameraEvent.ImportError -> Unit
                }
            }
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun share(result: ExportResult) {
        if (result.files.isEmpty()) return

        val uris = result.files.map(::uriForFile)
        val intent = Intent().apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = result.format.mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        val chooser = Intent.createChooser(intent, getString(R.string.share_document))

        val resolveInfos =
            packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            for (uri in uris) {
                grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        startActivity(chooser)
    }

    private fun sendActivityResult(result: ExportResult?) {
        val pdf = result as? ExportResult.Pdf ?: return

        val uri = uriForFile(pdf.file)
        val resultIntent = Intent().apply {
            data = uri
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        setResult(RESULT_OK, resultIntent)
    }

    private fun uriForFile(file: File): Uri {
        return nopalito.app.ui.uriForFile(this, file)
    }

    private fun checkPermissionThen(
        requestPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
        action: () -> Unit
    ) {
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (SDK_INT < Q && checkSelfPermission(this, permission) != PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(permission)
        } else {
            action()
        }
    }

    /**
     * Opens the final result of an export: the container folder when it exists
     * (photo batch via SAF), or the file otherwise.
     */
    private fun openExportArtifact(artifact: ExportArtifact, logger: FileLogger) {
        if (artifact.type == ExportArtifactType.FOLDER && artifact.folderUri != null) {
            openFolderUri(artifact.folderUri, artifact.uri, logger)
        } else if (artifact.uri != null) {
            openUri(artifact.uri, artifact.format.mimeType, logger)
        } else {
            showToast(getString(R.string.error_occurred))
        }
    }

    private fun openFolderUri(folderUri: Uri, fallbackChildUri: Uri?, logger: FileLogger) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                // newRawUri does not query the provider (ClipData.newUri does), avoiding a
                // SecurityException when we do not have the folder's SAF grant.
                clipData = ClipData.newRawUri("folder", folderUri)
            }
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            openFolderFallback(fallbackChildUri, logger)
        } catch (e: SecurityException) {
            logger.e("OpenFolder", "No permission to open folder $folderUri", e)
            openFolderFallback(fallbackChildUri, logger)
        }
    }

    private fun openFolderFallback(fallbackChildUri: Uri?, logger: FileLogger) {
        if (fallbackChildUri != null) {
            openUri(fallbackChildUri, "image/jpeg", logger)
        } else {
            showToast(getString(R.string.error_no_app))
        }
    }

    private fun openUri(fileUri: Uri?, mimeType: String, logger: FileLogger) {
        if (fileUri == null) return
        val uriToOpen: Uri =
            if (fileUri.scheme == ContentResolver.SCHEME_CONTENT) {
                fileUri
            } else {
                uriForFile(fileUri.toFile())
            }
        try {
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriToOpen, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "file", uriToOpen)
            }
            val chooser = Intent.createChooser(openIntent, getString(R.string.open_file)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            showToast(getString(R.string.error_no_app))
        } catch (e: Exception) {
            val errorMessage =
                "Failed to open URI, scheme=${uriToOpen.scheme}, authority=${uriToOpen.authority}"
            logger.e("OpenUri", errorMessage, e)
            showToast(getString(R.string.error_occurred))
        }
    }

    private fun shareCompressedResults(results: List<CompressedResult>) {
        val uris = results.mapNotNull { it.shareUri }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeForCompressed(results.first().fileName)
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "FairScan", uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_document)))
    }

    private fun openCompressedResults(results: List<CompressedResult>) {
        // Batch of files → open the folder that contains them.
        val folderUri = results.firstOrNull()?.batchFolderUri
        if (results.size > 1 && folderUri != null) {
            val fallbackChild = results.firstNotNullOfOrNull { it.shareUri }
            if (folderUri.scheme == ContentResolver.SCHEME_FILE) {
                openLocalFolder(folderUri) { fallbackChild }
            } else {
                openFolderUri(folderUri, fallbackChild, logger)
            }
            return
        }
        val result = results.firstOrNull() ?: return
        val uri = result.shareUri ?: return
        try {
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeForCompressed(result.fileName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "FairScan", uri)
            }
            startActivity(Intent.createChooser(openIntent, getString(R.string.open_file)))
        } catch (_: ActivityNotFoundException) {
            showToast(getString(R.string.error_no_app))
        }
    }

    private fun shareProtectedResults(results: List<PasswordProtectResult>) {
        val uris = results.mapNotNull { it.outputUri }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeForCompressed(results.first().fileName)
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "FairScan", uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_document)))
    }

    private fun openProtectedResults(results: List<PasswordProtectResult>) {
        // Batch of files → open the folder that contains them.
        val folderUri = results.firstOrNull()?.batchFolderUri
        if (results.size > 1 && folderUri != null) {
            val fallbackChild = results.firstNotNullOfOrNull { it.outputUri }
            if (folderUri.scheme == ContentResolver.SCHEME_FILE) {
                openLocalFolder(folderUri) { fallbackChild }
            } else {
                openFolderUri(folderUri, fallbackChild, logger)
            }
            return
        }
        val result = results.firstOrNull() ?: return
        val uri = result.outputUri ?: return
        openUri(uri, mimeForCompressed(result.fileName), logger)
    }

    private fun shareConvertedResults(results: List<ConvertResult>) {
        val uris = results.mapNotNull { it.outputUri }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeForCompressed(results.first().fileName)
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "FairScan", uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_document)))
    }

    private fun openConvertedResults(results: List<ConvertResult>) {
        // Batch of files → open the folder that contains them.
        val folderUri = results.firstOrNull()?.batchFolderUri
        if (results.size > 1 && folderUri != null) {
            val fallbackChild = results.firstNotNullOfOrNull { it.outputUri }
            if (folderUri.scheme == ContentResolver.SCHEME_FILE) {
                openLocalFolder(folderUri) { fallbackChild }
            } else {
                openFolderUri(folderUri, fallbackChild, logger)
            }
            return
        }
        val result = results.firstOrNull() ?: return
        val uri = result.outputUri ?: return
        openUri(uri, mimeForCompressed(result.fileName), logger)
    }

    private fun shareExtractResults(results: List<ExtractResult>) {
        val uris = results.mapNotNull { it.outputUri }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeForCompressed(results.first().fileName)
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "FairScan", uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_document)))
    }

    private fun openExtractResults(results: List<ExtractResult>) {
        // Image export → open the folder that contains the pages.
        val folderUri = results.firstOrNull()?.batchFolderUri
        if (results.size > 1 && folderUri != null) {
            val fallbackChild = results.firstNotNullOfOrNull { it.outputUri }
            if (folderUri.scheme == ContentResolver.SCHEME_FILE) {
                openLocalFolder(folderUri) { fallbackChild }
            } else {
                openFolderUri(folderUri, fallbackChild, logger)
            }
            return
        }
        val result = results.firstOrNull() ?: return
        val uri = result.outputUri ?: return
        openUri(uri, mimeForCompressed(result.fileName), logger)
    }

    private fun shareReorderResults(results: List<ReorderResult>) {
        val uris = results.mapNotNull { it.outputUri }
        if (uris.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeForCompressed(results.first().fileName)
            putExtra(Intent.EXTRA_STREAM, uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "FairScan", uris[0])
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_document)))
    }

    private fun openReorderResults(results: List<ReorderResult>) {
        val result = results.firstOrNull() ?: return
        val uri = result.outputUri ?: return
        openUri(uri, mimeForCompressed(result.fileName), logger)
    }

    private fun shareDeletePagesResults(results: List<DeletePagesResult>) {
        val uris = results.mapNotNull { it.outputUri }
        if (uris.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeForCompressed(results.first().fileName)
            putExtra(Intent.EXTRA_STREAM, uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "FairScan", uris[0])
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_document)))
    }

    private fun openDeletePagesResults(results: List<DeletePagesResult>) {
        val result = results.firstOrNull() ?: return
        val uri = result.outputUri ?: return
        openUri(uri, mimeForCompressed(result.fileName), logger)
    }

    private fun shareOrganizerResults(results: List<OrganizerResult>) {
        val uris = results.mapNotNull { it.outputUri }
        if (uris.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeForCompressed(results.first().fileName)
            putExtra(Intent.EXTRA_STREAM, uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "FairScan", uris[0])
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_document)))
    }

    private fun openOrganizerResults(results: List<OrganizerResult>) {
        val result = results.firstOrNull() ?: return
        val uri = result.outputUri ?: return
        openUri(uri, mimeForCompressed(result.fileName), logger)
    }

    /**
     * Opens a folder stored on primary external storage without exposing a raw file:// URI
     * (which would trigger FileUriExposedException). Routes it through the system Files
     * provider; if that fails, falls back to opening the first contained file.
     */
    private fun openLocalFolder(
        folderUri: Uri,
        fallbackChild: () -> Uri?,
    ) {
        val docUri = externalDocumentUri(folderUri)
        if (docUri != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("folder", docUri)
                }
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // fall through to fallback
            } catch (_: SecurityException) {
                // fall through to fallback
            }
        }
        val child = fallbackChild()
        if (child != null) {
            val type = contentResolver.getType(child) ?: "application/octet-stream"
            openUri(child, type, logger)
        } else {
            showToast(getString(R.string.error_no_app))
        }
    }

    /** Maps a file:// uri under external storage to a DocumentsProvider document uri. */
    @SuppressLint("SdCardPath")
    private fun externalDocumentUri(fileUri: Uri): Uri? {
        val raw = fileUri.path ?: return null
        val prefixes = listOf("/storage/emulated/0/", "/storage/self/primary/", "/sdcard/")
        val relative = prefixes.firstOrNull { raw.startsWith(it) }
            ?.let { raw.removePrefix(it) }
            ?: return null
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:$relative"
        )
    }

    private fun mimeForCompressed(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }

    private fun initLibraries() {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)

        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "Initialization failed")
        } else {
            Log.d("OpenCV", "Initialization successful")
        }
    }

    private fun navigation(viewModel: MainViewModel, launchMode: LaunchMode): Navigation =
        Navigation(
            toCameraScreen = { viewModel.navigateTo(Camera) },
            toEditImageScreen = { viewModel.navigateTo(EditImage) },
            toDocumentScreen = { viewModel.navigateTo(Document()) },
            toExportScreen = { viewModel.navigateTo(Export) },
            toAboutScreen = { viewModel.navigateTo(Screen.Overlay.About) },
            toLibrariesScreen = { viewModel.navigateTo(Screen.Overlay.Libraries) },
            toSettingsScreen = if (launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF) null else {
                {
                    viewModel.navigateTo(Screen.Overlay.Settings)
                }
            },
            toOcrLanguagesScreen = { viewModel.navigateTo(Screen.Overlay.OcrLanguages) },
            toHistoryScreen = { viewModel.navigateTo(Screen.Overlay.History) },
            toQrHistoryScreen = { viewModel.navigateTo(Screen.Overlay.QrHistory) },
            toCloudScreen = { viewModel.navigateTo(Screen.Overlay.FairScanCloud) },
            toToolsScreen = { viewModel.navigateTo(Screen.Overlay.Tools) },
            toToolCompress = { tool -> viewModel.navigateTo(Screen.Overlay.ToolCompress(tool)) },
            switchTool = { tool ->
                viewModel.replaceCurrentScreen(Screen.Overlay.ToolCompress(tool))
            },
            toPasswordProtectScreen = { viewModel.navigateTo(Screen.Overlay.PasswordProtect) },
            toConvertScreen = { viewModel.navigateTo(Screen.Overlay.Convert) },
            toExtractScreen = { viewModel.navigateTo(Screen.Overlay.Extract) },
            toReorderScreen = { viewModel.navigateTo(Screen.Overlay.Reorder) },
            toDeletePagesScreen = { viewModel.navigateTo(Screen.Overlay.DeletePages) },
            toOrganizePagesScreen = { viewModel.navigateTo(Screen.Overlay.OrganizePages) },
            toQrGeneratorScreen = { viewModel.navigateTo(Screen.Overlay.QrGenerator) },
            back = {
                val origin = viewModel.currentScreen.value
                viewModel.navigateBack()
                val destination = viewModel.currentScreen.value
                if (destination == origin && launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF) {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            },
            shouldDisplayBackButton = {
                viewModel.currentScreen.value !is Camera
                        || launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF
            }
        )

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            if (viewModel.currentScreen.value is Camera) {
                cameraViewModel.onVolumeKeyPressed()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
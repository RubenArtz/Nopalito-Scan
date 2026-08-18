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

package nopalito.app.ui.screens.cloud

import android.app.Application
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nopalito.app.ui.screens.cloud.data.CloudSessionManager
import nopalito.app.ui.screens.cloud.data.CloudSessionState
import nopalito.app.ui.screens.cloud.navigation.CloudRecoverMode
import nopalito.app.ui.screens.cloud.navigation.CloudScreen
import nopalito.app.ui.screens.cloud.screens.*
import nopalito.app.ui.screens.cloud.viewmodel.*

private const val TAG = "CloudHost"

/**
 * CloudHost entry point.
 *
 * Instead of always performing a fresh HTTP session check every time the user
 * taps the Cloud button, it leverages [CloudSessionManager] which runs a
 * background check when the app starts. This means:
 *
 * - If already authenticated → navigates directly to Home (no splash delay)
 * - If unauthenticated → navigates directly to EmailLogin
 * - If still Checking → shows a lightweight Splash (no HTTP request, just UI)
 * - If Error → shows Splash with retry button
 *
 * AUTH GUARD + BACKSTACK CLEANUP:
 * 1. LaunchedEffect reacts to sessionState changes from ANY screen (not just Splash),
 *    so if the session expires while browsing Home/FileList/Trash, we redirect to
 *    EmailLogin before any API call shows "Session expired".
 * 2. onLogout calls onBack() FIRST to pop the Cloud overlay, THEN clears the session
 *    synchronously via CloudSessionManager. No flash of EmailLogin before the overlay
 *    closes. On next Cloud tap, sessionState is already Unauthenticated → shows EmailLogin.
 * 3. CloudSessionManager.logout() sets state=Unauthenticated synchronously (not inside a
 *    coroutine), so any immediate re-composition sees the correct state.
 * 4. initialScreen is derived from sessionState at composition time — if already
 *    Unauthenticated, shows EmailLogin immediately without loading Home/Splash.
 *
 * VIEWMODEL GENERATION COUNTER (prevents stale ViewModel reuse after logout):
 * emailViewModelGen lives at FILE level (not inside remember {}), so it survives
 * CloudHost destruction/recreation. Without this, remember{} resets the counter to 0
 * on each CloudHost mount, reusing stale ViewModels with isSuccess=true from the
 * Activity's ViewModelStore, which causes automatic navigation without OTP verification.
 *
 * MAINTENANCE MODE:
 * When the backend is in maintenance mode, CloudHost shows CloudMaintenanceScreen
 * and blocks access to all cloud features. The user can only exit cloud via back button.
 */

/** Survives CloudHost composition lifecycle. Incremented on logout to force fresh ViewModels. */
private var emailViewModelGen = 0

/** Incremented on every CloudHost mount so the Splash ViewModel is never reused
 * with stale state from a previous session (e.g. Unauthenticated from before a
 * successful login), which would wrongly redirect to EmailLogin on re-entry. */
private var splashVmMountGen = 0

/** Incremented on every CloudHost mount so the BiometricGate ViewModel is never
 * reused with a stale Unlocked outcome from a previous entry. */
private var gateVmMountGen = 0

@Composable
fun CloudHost(
    onBack: () -> Unit,
    cloudSessionManager: CloudSessionManager
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = remember { context.applicationContext as Application }
    val factory = remember { CloudViewModelFactory(app) }

    // Maintenance ViewModel — checks backend maintenance status on creation
    val maintenanceVm: CloudMaintenanceViewModel = viewModel(factory = factory)
    val maintenanceStatus by maintenanceVm.maintenanceState.collectAsStateWithLifecycle()
    val isMaintenanceChecking by maintenanceVm.isChecking.collectAsStateWithLifecycle()

    // Determine if maintenance is active (blocks all cloud features)
    val isMaintenanceActive = maintenanceStatus?.maintenanceActive == true
    // Log only non-sensitive fields: never title/message/reason (raw server text).
    Log.d(
        TAG,
        "Maintenance check: maintenanceActive=${maintenanceStatus?.maintenanceActive}, maintenanceScheduled=${maintenanceStatus?.maintenanceScheduled}, id=${
            maintenanceStatus?.id?.take(
                36
            )
        }, isMaintenanceActive=$isMaintenanceActive, isChecking=$isMaintenanceChecking"
    )

    // Observe the pre-loaded session state from CloudSessionManager (StateFlow).
    // This runs in background from app startup, so no fresh API call is needed.
    // initialValue = current state: when CloudHost is re-opened while already
    // authenticated, the very first frame reflects Authenticated (→ Home) instead
    // of flashing the Splash/EmailLogin from the forced "Checking" placeholder.
    val sessionState by cloudSessionManager.state.collectAsStateWithLifecycle(
        initialValue = cloudSessionManager.state.collectAsState().value
    )
    Log.d(TAG, "sessionState observed = $sessionState")

    // Determine initial screen based on pre-loaded session state.
    val initialScreen: CloudScreen = when (sessionState) {
        is CloudSessionState.Authenticated -> CloudScreen.Home
        is CloudSessionState.Unauthenticated -> CloudScreen.EmailLogin
        is CloudSessionState.Checking -> CloudScreen.Splash
        is CloudSessionState.NeedsUnlock -> CloudScreen.Gate
        is CloudSessionState.Error -> CloudScreen.Splash
    }
    Log.d(TAG, "initialScreen = $initialScreen  (sessionState=$sessionState)")

    // Internal cloud navigation state (starts from pre-loaded state)
    var currentScreen by remember { mutableStateOf(initialScreen) }
    // Shared email across the auth flow
    var currentEmail by remember { mutableStateOf("") }
    // [ponytail]: using file-level var (not remember{}) to survive CloudHost recreation.
    // recall: remember{} resets to 0 on each mount, reusing stale ViewModels from ViewModelStore.
    var emailViewModelGeneration by remember { mutableIntStateOf(emailViewModelGen) }


    // Flag that prevents LaunchedEffect(sessionState) from redirecting to EmailLogin
    // while onLogout is in progress (onBack() + logout()). Navigation is handled by onBack().
    var isLoggingOut by remember { mutableStateOf(false) }

    // FIX: react to sessionState transitions from ANY screen, not only Splash.
    // This ensures that if the token expires while the user is on Home/FileList/Trash
    // (e.g. after a 401 from an API call -> TokenProvider.clearTokens() -> state = Unauthenticated),
    // we redirect to EmailLogin before any further API call hits a stale session.
    LaunchedEffect(sessionState) {
        Log.d(
            TAG,
            "LaunchedEffect(sessionState=$sessionState)  currentScreen=$currentScreen isLoggingOut=$isLoggingOut"
        )
        // During an explicit logout, onBack() handles navigation.
        // We ignore the state change to prevent LaunchedEffect from setting
        // currentScreen = EmailLogin before the composition is destroyed.
        if (isLoggingOut) return@LaunchedEffect
        when {
            // If we are already authenticated, we belong on Home — no matter what
            // screen we're on (Splash, a stale EmailLogin from a previous session,
            // OTP just verified). This is the single source of truth for "logged in".
            sessionState is CloudSessionState.Authenticated &&
                    currentScreen !is CloudScreen.Home -> {
                Log.i(TAG, "Authenticated → navigating Home (was $currentScreen)")
                currentScreen = CloudScreen.Home
            }

            sessionState is CloudSessionState.Unauthenticated &&
                    currentScreen !is CloudScreen.EmailLogin &&
                    currentScreen !is CloudScreen.Splash -> {
                Log.i(TAG, "Redirecting to EmailLogin (unauth, was $currentScreen)")
                currentScreen = CloudScreen.EmailLogin
                emailViewModelGeneration++
            }

            currentScreen is CloudScreen.Splash -> {
                when (sessionState) {
                    // Authenticated cannot reach this branch: Splash is not Home,
                    // so the Authenticated rule above already navigated to Home.
                    is CloudSessionState.Unauthenticated -> {
                        Log.i(TAG, "Splash → Unauthenticated, navigating EmailLogin")
                        currentScreen = CloudScreen.EmailLogin
                    }

                    else -> { /* stay on Splash for Checking / Error */
                    }
                }
            }
        }
    }

    // MAINTENANCE GATE: when backend reports maintenance active, block all cloud features
    // and show the maintenance screen. User can only exit via back button.
    if (isMaintenanceActive) {
        BackHandler(enabled = true) { onBack() }
        CloudMaintenanceScreen(
            viewModel = maintenanceVm
        )
        return // Don't render any cloud screens below
    }

    // BackHandler ALWAYS enabled while CloudHost exists.
    // If disabled conditionally, during AnimatedContent transitions
    // the system back gesture propagates to the Activity, which on Android 13+
    // with enableEdgeToEdge() minimizes/closes the app instead of navigating internally.
    //
    // NOTE: EmailLogin -> onBack() (not Splash). Navigating to Splash from EmailLogin
    // causes CloudSplashViewModel.checkSession() to detect Unauthenticated and fire
    // onUnauthenticated which returns to EmailLogin -- infinite loop. Since we already
    // know there is no session, ArrowBack must exit cloud directly.
    BackHandler(enabled = true) {
        when (currentScreen) {
            is CloudScreen.Home,
            is CloudScreen.Splash,
            is CloudScreen.EmailLogin,
            is CloudScreen.Gate -> onBack() // exit cloud
            is CloudScreen.OtpVerify,
            is CloudScreen.Register,
            is CloudScreen.Recover -> currentScreen = CloudScreen.EmailLogin

            is CloudScreen.QrTrash -> currentScreen = CloudScreen.QrHistory // QR, not a file
            is CloudScreen.Storage,
            is CloudScreen.UploadFile,
            is CloudScreen.Trash,
            is CloudScreen.QrHistory -> currentScreen = CloudScreen.Home
        }
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            (slideInHorizontally { width -> width } + fadeIn()) togetherWith
                    (slideOutHorizontally { width -> -width } + fadeOut())
        },
        label = "cloud_nav"
    ) { screen ->
        // Guard: AnimatedContent with togetherWith keeps BOTH screens
        // composed during the transition. If a callback from the outgoing
        // screen fires after currentScreen has already changed, it causes
        // double navigation. This local function only navigates if the
        // captured screen is still the current one -- prevents tap accumulation.
        fun navigateTo(target: CloudScreen) {
            if (currentScreen == screen) currentScreen = target
        }

        when (screen) {
            is CloudScreen.Splash -> {
                // key() forces a fresh CloudSplashViewModel on each CloudHost mount:
                // a reused one keeps its stale state from a previous session (e.g.
                // Unauthenticated from before a successful login) and would wrongly
                // redirect to EmailLogin on re-entry even though we ARE authenticated.
                val splashVmKey = remember { "splash_${splashVmMountGen++}" }
                key(splashVmKey) {
                    val vm: CloudSplashViewModel = viewModel(
                        key = splashVmKey,
                        factory = factory
                    )
                    CloudSplashScreen(
                        viewModel = vm,
                        onAuthenticated = { navigateTo(CloudScreen.Home) },
                        onUnauthenticated = { navigateTo(CloudScreen.EmailLogin) },
                        onNavigateBack = onBack
                    )
                }
            }

            is CloudScreen.Gate -> {
                // Fresh ViewModel per mount so a stale Unlocked outcome from a
                // previous entry cannot auto-navigate.
                val gateVmKey = remember { "gate_${gateVmMountGen++}" }
                key(gateVmKey) {
                    val vm: BiometricGateViewModel = viewModel(
                        key = gateVmKey,
                        factory = factory
                    )
                    BiometricGateScreen(
                        viewModel = vm,
                        onUnlocked = {
                            Log.i(TAG, "BiometricGate → unlocked, marking session authenticated")
                            cloudSessionManager.markAuthenticated()
                        },
                        onUseAnotherAccount = {
                            Log.i(TAG, "BiometricGate → use another account, logging out")
                            emailViewModelGen++
                            emailViewModelGeneration++
                            cloudSessionManager.logout()
                        },
                        onBack = onBack
                    )
                }
            }

            is CloudScreen.EmailLogin -> {
                // key() + viewModel(key=) forces fresh ViewModel when generation changes
                // (e.g. after logout), preventing stale isSuccess from auto-navigating
                // and clearing previously typed email.
                key(emailViewModelGeneration) {
                    val vm: CloudEmailViewModel = viewModel(
                        key = "email_$emailViewModelGeneration",
                        factory = factory
                    )
                    CloudEmailScreen(
                        viewModel = vm,
                        onLoginSuccess = { email ->
                            currentEmail = email
                            // Step 1 OK: the password was accepted; a fresh
                            // email code must be verified before tokens are
                            // issued (higher security on sign-in).
                            navigateTo(CloudScreen.OtpVerify(email, isLogin = true))
                        },
                        onCreateAccount = {
                            navigateTo(CloudScreen.Register)
                        },
                        onRecoverPassword = {
                            navigateTo(CloudScreen.Recover(CloudRecoverMode.FORGOT))
                        },
                        // ArrowBack exits cloud directly.
                        // Going to Splash here causes a loop: Splash -> Unauthenticated -> EmailLogin -> ...
                        onBack = onBack
                    )
                }
            }

            is CloudScreen.Register -> {
                key(emailViewModelGeneration) {
                    val vm: CloudRegisterViewModel = viewModel(
                        key = "register_$emailViewModelGeneration",
                        factory = factory
                    )
                    CloudRegisterScreen(
                        viewModel = vm,
                        initialEmail = currentEmail,
                        onCodeSent = { email ->
                            currentEmail = email
                            navigateTo(CloudScreen.OtpVerify(email, isLogin = false))
                        },
                        onBack = { navigateTo(CloudScreen.EmailLogin) }
                    )
                }
            }

            is CloudScreen.Recover -> {
                key(emailViewModelGeneration) {
                    val vm: CloudRecoverViewModel = viewModel(
                        key = "recover_${screen.mode}_$emailViewModelGeneration",
                        factory = factory
                    )
                    CloudRecoverPasswordScreen(
                        viewModel = vm,
                        mode = screen.mode,
                        // Success = auto-login (the code proved email ownership):
                        // markAuthenticated redirects to Home via the session effect.
                        onDone = { cloudSessionManager.markAuthenticated() },
                        onBack = { navigateTo(CloudScreen.EmailLogin) }
                    )
                }
            }

            is CloudScreen.OtpVerify -> {
                val vm: CloudOtpViewModel = viewModel(
                    key = "otp_$emailViewModelGeneration",
                    factory = factory
                )
                CloudOtpScreen(
                    viewModel = vm,
                    email = screen.email,
                    isLogin = screen.isLogin,
                    onVerified = {
                        cloudSessionManager.markAuthenticated()
                        navigateTo(CloudScreen.Home)
                    },
                    onBack = { navigateTo(CloudScreen.EmailLogin) }
                )
            }

            is CloudScreen.Home -> {
                val vm: CloudHomeViewModel = viewModel(factory = factory)
                val fileListVm: CloudFileListViewModel = viewModel(factory = factory)
                LaunchedEffect(Unit) { fileListVm.refresh() }
                CloudHomeScreen(
                    viewModel = vm,
                    fileListViewModel = fileListVm,
                    onNavigateToUpload = { navigateTo(CloudScreen.UploadFile) },
                    onNavigateToTrash = { navigateTo(CloudScreen.Trash) },
                    onNavigateToQrHistory = { navigateTo(CloudScreen.QrHistory) },
                    onNavigateToStorage = { navigateTo(CloudScreen.Storage) },
                    onBack = onBack,
                    onLogout = {
                        Log.i(TAG, "onLogout: incrementing generation, calling onBack + cloudSessionManager.logout()")
                        emailViewModelGen++
                        emailViewModelGeneration++

                        // FIX: exit CloudHost FIRST (onBack pops the overlay to Camera),
                        // then clear session synchronously.  When CloudHost is re-created
                        // the sessionState is already Unauthenticated → initialScreen = EmailLogin.
                        // DO NOT set currentScreen = EmailLogin here – it creates a flash
                        // of the login screen before the overlay is removed.
                        isLoggingOut = true
                        onBack()
                        cloudSessionManager.logout()
                        // We do not reset isLoggingOut. The composition will be destroyed
                        // when onBack() removes CloudHost from the tree. If we reset it,
                        // LaunchedEffect(sessionState) could redirect to EmailLogin
                        // before the recomposition destroys this composable.
                    }
                )
            }

            is CloudScreen.UploadFile -> {
                val vm: CloudUploadViewModel = viewModel(factory = factory)
                CloudUploadScreen(
                    viewModel = vm,
                    onBack = {
                        vm.resetState()
                        navigateTo(CloudScreen.Home)
                    },
                    onUploadSuccess = {
                        vm.resetState()
                        navigateTo(CloudScreen.Home)
                    },
                    onOpenPremium = {
                        vm.resetState()
                        navigateTo(CloudScreen.Storage)
                    }
                )
            }

            is CloudScreen.Storage -> {
                val vm: CloudStorageViewModel = viewModel(factory = factory)
                CloudStorageScreen(
                    viewModel = vm,
                    onBack = { navigateTo(CloudScreen.Home) }
                )
            }

            is CloudScreen.Trash -> {
                val vm: CloudTrashViewModel = viewModel(factory = factory)
                LaunchedEffect(Unit) { vm.refresh() }
                CloudTrashScreen(
                    viewModel = vm,
                    onBack = { navigateTo(CloudScreen.Home) }
                )
            }

            is CloudScreen.QrHistory -> {
                val vm: CloudQrHistoryViewModel = viewModel(factory = factory)
                LaunchedEffect(Unit) { vm.refresh() }
                CloudQrHistoryScreen(
                    viewModel = vm,
                    onBack = { navigateTo(CloudScreen.Home) },
                    onNavigateToTrash = { navigateTo(CloudScreen.QrTrash) },
                )
            }

            is CloudScreen.QrTrash -> {
                val vm: CloudQrTrashViewModel = viewModel(factory = factory)
                LaunchedEffect(Unit) { vm.refresh() }
                CloudQrTrashScreen(
                    viewModel = vm,
                    onBack = { navigateTo(CloudScreen.QrHistory) }
                )
            }
        }
    }
}
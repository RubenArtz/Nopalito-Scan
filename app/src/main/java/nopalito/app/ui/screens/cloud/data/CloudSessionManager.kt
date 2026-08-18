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

package nopalito.app.ui.screens.cloud.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.network.LogoutException

/**
 * Singleton that checks the cloud session in background when the app starts,
 * so that CloudHost and ExportScreen already know the authentication state
 * without needing to make a fresh API call.
 *
 * Exposes a StateFlow<CloudSessionState> that any component can observe.
 */
sealed class CloudSessionState {
    data object Checking : CloudSessionState()
    data object Authenticated : CloudSessionState()
    data object Unauthenticated : CloudSessionState()

    /**
     * Biometric mode is on and the refresh token sits in the auth-bound blob:
     * the session must be unlocked with the OS prompt before any API call.
     * Only ever emitted in biometric mode; normal mode never sees it.
     */
    data object NeedsUnlock : CloudSessionState()

    data class Error(val message: String) : CloudSessionState()
}

class CloudSessionManager private constructor(val appContext: Context) {

    private val repository = CloudRepository(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initializeJob: Job? = null

    private val _state = MutableStateFlow<CloudSessionState>(CloudSessionState.Checking)
    val state: StateFlow<CloudSessionState> = _state.asStateFlow()

    init {
        Log.d(TAG, "init: registering onTokensCleared callback")
        repository.onTokensCleared {
            Log.w(TAG, "onTokensCleared callback FIRED — setting state=Unauthenticated")
            _state.value = CloudSessionState.Unauthenticated
        }
    }

    /**
     * Whether the user has a valid cloud session.
     * Can be checked synchronously from any thread.
     */
    fun isAuthenticated(): Boolean {
        val result = _state.value == CloudSessionState.Authenticated
        Log.d(TAG, "isAuthenticated() → $result  (current state=${_state.value})")
        return result
    }

    /**
     * Mark the session as authenticated after a successful email/OTP verification.
     * Called from CloudHost when the OTP verification succeeds.
     */
    fun markAuthenticated() {
        Log.i(TAG, "markAuthenticated() — state → Authenticated")
        _state.value = CloudSessionState.Authenticated
    }

    /**
     * Start background session check.
     * Called once from AppContainer.init()
     */
    fun initialize() {
        initializeJob?.cancel()
        Log.d(TAG, "initialize() — hasSession=${repository.hasSession()}")
        initializeJob = scope.launch {
            // 1. No tokens at all → unauthenticated immediately
            if (!repository.hasSession()) {
                Log.i(TAG, "initialize: no session tokens → Unauthenticated")
                _state.value = CloudSessionState.Unauthenticated
                return@launch
            }

            // 2. Biometric mode: the refresh token is locked in the blob and no
            // API call can succeed without the OS prompt, which cannot be shown
            // before the UI is up. Let the gate drive the unlock.
            if (repository.isBiometricMode()) {
                Log.i(TAG, "initialize: biometric mode active → NeedsUnlock (no network)")
                _state.value = CloudSessionState.NeedsUnlock
                return@launch
            }

            // 3. Try GET /api/auth/me (fast path)
            Log.d(TAG, "initialize: calling getMe()")
            val meResult = repository.getMe()
            if (meResult.isSuccess) {
                // Verify nobody deleted the session while getMe() was traveling
                if (!repository.hasSession()) {
                    Log.w(TAG, "initialize: getMe() OK but session was cleared meanwhile → Unauthenticated")
                    _state.value = CloudSessionState.Unauthenticated
                    return@launch
                }
                Log.i(TAG, "initialize: getMe() SUCCESS → Authenticated")
                _state.value = CloudSessionState.Authenticated
                return@launch
            }
            Log.w(TAG, "initialize: getMe() FAILED → trying refreshToken")

            // 3. Try refresh token
            val refreshResult = repository.refreshToken()
            if (refreshResult.isSuccess) {
                if (!repository.hasSession()) {
                    Log.w(TAG, "initialize: refresh OK but session was cleared meanwhile → Unauthenticated")
                    _state.value = CloudSessionState.Unauthenticated
                    return@launch
                }
                Log.i(TAG, "initialize: refreshToken() SUCCESS → Authenticated")
                _state.value = CloudSessionState.Authenticated
                return@launch
            }
            Log.e(TAG, "initialize: refreshToken() also FAILED")

            // 4. Both failed
            val error = refreshResult.exceptionOrNull()
            if (error is LogoutException) {
                Log.i(TAG, "initialize: LogoutException → clearSession + Unauthenticated")
                repository.clearSession()
                _state.value = CloudSessionState.Unauthenticated
            } else {
                val message = when {
                    error is java.net.SocketTimeoutException ->
                        appContext.stringFor(R.string.cloud_connection_timeout, AppLocaleOverride.locale)

                    error is java.net.UnknownHostException ->
                        appContext.stringFor(R.string.cloud_connection_error, AppLocaleOverride.locale)

                    error != null -> CloudErrorPresenter.message(
                        appContext,
                        error,
                        R.string.cloud_connection_generic_error
                    )

                    else -> appContext.stringFor(R.string.cloud_connection_error, AppLocaleOverride.locale)
                }
                Log.e(TAG, "initialize: Error state → $message")
                _state.value = CloudSessionState.Error(message)
            }
        }
    }

    /**
     * Full logout: captures refresh token BEFORE clearing, clears local
     * IMMEDIATELY (synchronous), then invalidates backend session with captured
     * token (async, best-effort).
     *
     * Critical order:
     * 1. Capture refresh token (only chance before deletion)
     * 2. Emit Unauthenticated state (SYNCHRONOUS) — ensures subsequent reads
     *    of [state] see the unauthenticated state immediately, even before
     *    the async cleanup coroutine starts.  This prevents a race where a
     *    re-composition of CloudHost sees stale `Authenticated`.
     * 3. Clear local tokens (synchronous)
     * 4. Invalidate backend session with captured token (async, best-effort)
     *
     * Invalidation is best-effort; if it fails, the token remains valid server-side
     * until its natural expiration.
     */
    fun logout() {
        // Cancel pending background check — prevents initialize() from setting
        // Authenticated after logout() already cleared the session.
        initializeJob?.cancel()
        val capturedRefreshToken = repository.getRefreshToken()
        val wasBiometricMode = repository.isBiometricMode()
        Log.i(
            TAG, "logout() — refreshToken present=${capturedRefreshToken != null}, " +
                    "biometricMode=$wasBiometricMode, setting state=Unauthenticated, clearing local session"
        )
        _state.value = CloudSessionState.Unauthenticated
        repository.clearSession()
        if (capturedRefreshToken != null) {
            Log.d(TAG, "logout: invalidating backend session (async)")
            scope.launch {
                repository.logoutWithToken(capturedRefreshToken)
            }
        } else if (wasBiometricMode) {
            // The refresh token only existed in the auth-bound blob, which
            // clearSession() just wiped. It cannot be invalidated server-side
            // without an unlock prompt; it expires server-side on its own.
            Log.w(TAG, "logout: biometric mode — refresh token wiped locally, no backend invalidation")
        } else {
            Log.d(TAG, "logout: no refresh token to invalidate")
        }
    }

    companion object {
        private const val TAG = "CloudSession"

        @Volatile
        private var instance: CloudSessionManager? = null

        fun getInstance(context: Context): CloudSessionManager {
            val result = instance ?: synchronized(this) {
                instance ?: CloudSessionManager(context.applicationContext).also {
                    Log.d(TAG, "getInstance: created new instance")
                    instance = it
                }
            }
            Log.d(TAG, "getInstance: returning instance")
            return result
        }
    }
}
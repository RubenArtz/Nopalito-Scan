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

package nopalito.app.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import nopalito.app.ui.screens.cloud.model.RegisterFcmTokenRequest
import nopalito.app.ui.screens.cloud.network.CloudApiClient

private const val TAG = "FcmTokenSync"

/**
 * Keeps the backend in sync with this install's FCM registration token.
 *
 * The device registers as soon as the notification permission is granted
 * (Android 13+; always on older versions), WITHOUT requiring a cloud session:
 * the backend stores the device under the anonymous user (registration with a
 * deviceId + rate limit). When the user signs in, the same call carries the
 * Bearer JWT and the backend REBINDS the device to the real user.
 *
 * Flow:
 *  - App start / permission granted → POST /api/devices/fcm-token (no auth).
 *  - Real sign-in                  → POST again (the JWT upgrades the binding).
 *  - onNewToken                    → POST again (FCM rotated the token).
 *
 * The Firebase SDK reads its config from google-services.json embedded in the
 * APK — that file is public app configuration, NOT a credential. The Firebase
 * service account (the real secret) never touches this app.
 */
@Suppress("DEPRECATION")
class FcmTokenSync private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val deviceApi = CloudApiClient.getInstance(appContext).devices
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Optional destination for user-facing diagnostics (wired to the app log). */
    private var sink: ((String) -> Unit)? = null

    fun setDiagnosticSink(sink: ((String) -> Unit)?) {
        this.sink = sink
    }

    private fun diag(line: String) {
        Log.w(TAG, line)
        sink?.invoke(line)
    }

    /**
     * Called at app start and when the notification permission is granted.
     * Gated on the permission (Android 13+), as requested: no permission, no
     * registration. On Android < 13 there is no permission to ask.
     */
    fun syncOnAppStart() {
        if (!notificationPermissionGranted(appContext)) {
            diag("syncOnAppStart: no notifications permission (Android 13+), does not register")
            return
        }
        scope.launch { uploadFcmToken() }
    }

    /** Called by FirebaseMessagingService when FCM rotates the token. */
    fun onNewToken(token: String) {
        diag("onNewToken: FCM rotated registration token")
        scope.launch { uploadFcmToken(token) }
    }

    /** Called after a real sign-in via TokenProvider.onLogin (rebinds to user). */
    fun onSignedIn() {
        diag("onSignedIn: registering/binding device with session")
        scope.launch { uploadFcmToken() }
    }

    private fun notificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun uploadFcmToken(tokenOverride: String? = null) {
        // Fetching is skipped when a token was passed (onNewToken already has it).
        val token = tokenOverride ?: runCatching {
            FirebaseMessaging.getInstance().token.await()
        }.getOrNull()
        if (token.isNullOrEmpty()) {
            diag("uploadFcmToken: Could not get a Firebase token (valid google-services.json?). It is omitted.")
            return
        }

        val request = RegisterFcmTokenRequest(
            deviceId = DeviceIdentity.getOrCreate(appContext),
            fcmToken = token,
            platform = "android",
        )

        runCatching { deviceApi.registerFcmToken(request) }
            .onSuccess { response ->
                if (response.isSuccessful) {
                    diag("uploadFcmToken: token registered in backend (200)")
                } else {
                    diag("uploadFcmToken: rejected by backend (${response.code()})")
                }
            }
            .onFailure { e ->
                diag("uploadFcmToken: network/client error (${e.javaClass.simpleName})")
            }
    }

    companion object {
        @Volatile
        private var instance: FcmTokenSync? = null

        fun getInstance(context: Context): FcmTokenSync {
            return instance ?: synchronized(this) {
                instance ?: FcmTokenSync(context.applicationContext).also { instance = it }
            }
        }
    }
}
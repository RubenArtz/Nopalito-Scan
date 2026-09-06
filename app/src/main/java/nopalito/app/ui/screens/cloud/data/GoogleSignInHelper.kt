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
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import nopalito.app.BuildConfig
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shared helper for Google Sign-In via Credential Manager.
 *
 * Centralizes safe diagnostics and explicit Sign-In fallback for both
 * login (CloudEmailScreen) and account linking (LinkGoogleCard).
 * No sensitive data (tokens, emails, names) is ever logged.
 */
object GoogleSignInHelper {

    private const val TAG = "GoogleSignIn"

    /**
     * SHA-256 prefix of the configured client ID for safe diagnostics.
     * Returns "none" if blank or "hash_err" on failure. Never returns the raw ID.
     */
    private fun clientIdHashPrefix(clientId: String): String {
        if (clientId.isBlank()) return "none"
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(clientId.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }.take(8)
        } catch (_: Exception) {
            "hash_err"
        }
    }

    /**
     * Requests a Google ID token via Credential Manager.
     *
     * Performs validation of BuildConfig.GOOGLE_SERVER_CLIENT_ID, logs each
     * non-sensitive stage, and uses GetSignInWithGoogleOption as fallback
     * when GetGoogleIdOption yields NoCredentialException (explicit button).
     *
     * @return raw ID token string (never logged)
     * @throws GetCredentialCancellationException if user cancelled
     * @throws NoCredentialException if no Google account available
     * @throws GetCredentialException for transport/config errors
     * @throws GoogleIdTokenParsingException if parsing fails
     */
    suspend fun requestGoogleIdToken(context: Context): String {
        val raw = BuildConfig.GOOGLE_SERVER_CLIENT_ID
        val trimmed = raw.trim()
        val isBlank = trimmed.isBlank()
        val hashPrefix = clientIdHashPrefix(trimmed)

        Log.d(
            TAG,
            "Google sign-in requested: clientIdConfigured=${!isBlank}, clientIdHashPrefix=$hashPrefix, filterByAuthorizedAccounts=false, autoSelect=false"
        )
        if (isBlank) {
            Log.e(TAG, "GOOGLE_SERVER_CLIENT_ID is blank - aborting Credential Manager request")
            throw IllegalStateException("GOOGLE_SERVER_CLIENT_ID is blank")
        }

        val credentialManager = CredentialManager.create(context)

        // Primary: Use GetSignInWithGoogleOption directly for explicit button.
        // On Samsung One UI 7 (SM-S928B) the OEM CredentialSelector for GetGoogleIdOption
        // hangs/crashes (window NO_INPUT_CHANNEL, ActivityManager kills 6399).
        // GetSignInWithGoogleOption shows Google's own picker (GoogleSignInActivity)
        // and is stable. Keep GetGoogleIdOption only as fallback with timeout.
        try {
            Log.d(TAG, "Requesting credential with GetSignInWithGoogleOption (primary)")
            val signInOption = GetSignInWithGoogleOption.Builder(trimmed).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()
            val result = withTimeout(60000L.milliseconds) {
                credentialManager.getCredential(context, request)
            }
            Log.d(
                TAG,
                "CredentialManager returned: credentialClass=${result.credential::class.java.simpleName}, isCustom=${result.credential is CustomCredential}"
            )
            if (result.credential is CustomCredential) {
                val custom = result.credential as CustomCredential
                Log.d(TAG, "CustomCredential type=${custom.type}")
                if (custom.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleCredential = GoogleIdTokenCredential.createFrom(custom.data)
                        val received = googleCredential.idToken.isNotBlank()
                        Log.d(TAG, "GoogleIdTokenCredential parsed: idTokenReceived=$received")
                        if (received) {
                            Log.d(TAG, "ID token obtained successfully: idTokenReceived=true")
                            return googleCredential.idToken
                        } else {
                            Log.w(
                                TAG,
                                "ID token is blank after parsing GetSignInWithGoogleOption credential"
                            )
                            throw IllegalStateException("Blank ID token")
                        }
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e(
                            TAG,
                            "GoogleIdTokenParsingException: class=${e::class.java.simpleName} message=${e.message} cause=${e.cause?.javaClass?.simpleName}:${e.cause?.message}",
                            e
                        )
                        throw e
                    }
                } else {
                    Log.w(TAG, "Unexpected CustomCredential type: ${custom.type}")
                    throw IllegalStateException("Unexpected credential type: ${custom.type}")
                }
            } else {
                Log.w(
                    TAG,
                    "Credential is not CustomCredential: ${result.credential::class.java.name}"
                )
                throw IllegalStateException("Not CustomCredential")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout after 60s waiting for GetSignInWithGoogleOption", e)
            throw IllegalStateException("CredentialManager timeout after 60s")
        } catch (e: GetCredentialCancellationException) {
            Log.d(
                TAG,
                "GetCredentialCancellationException (user cancelled): class=${e::class.java.simpleName} type=${e.type} message=${e.message}",
                e
            )
            throw e
        } catch (e: NoCredentialException) {
            Log.w(
                TAG,
                "NoCredentialException on GetSignInWithGoogleOption: class=${e::class.java.simpleName} type=${e.type} message=${e.message} cause=${e.cause?.javaClass?.simpleName}:${e.cause?.message}",
                e
            )
            // Fallback: try GetGoogleIdOption (One Tap) with timeout
            Log.d(TAG, "Attempting fallback with GetGoogleIdOption")
            try {
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(trimmed)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
                val fallbackRequest = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val fallbackResult = withTimeout(60000L.milliseconds) {
                    credentialManager.getCredential(context, fallbackRequest)
                }
                Log.d(
                    TAG,
                    "Fallback CredentialManager returned: credentialClass=${fallbackResult.credential::class.java.simpleName}, isCustom=${fallbackResult.credential is CustomCredential}"
                )
                if (fallbackResult.credential is CustomCredential) {
                    val custom = fallbackResult.credential as CustomCredential
                    Log.d(TAG, "Fallback CustomCredential type=${custom.type}")
                    try {
                        val googleCredential = GoogleIdTokenCredential.createFrom(custom.data)
                        val received = googleCredential.idToken.isNotBlank()
                        Log.d(
                            TAG,
                            "Fallback GoogleIdTokenCredential parsed: idTokenReceived=$received"
                        )
                        if (received) {
                            Log.d(TAG, "ID token obtained via fallback: idTokenReceived=true")
                            return googleCredential.idToken
                        } else {
                            Log.w(TAG, "Fallback ID token is blank")
                            throw IllegalStateException("Blank fallback ID token")
                        }
                    } catch (e2: GoogleIdTokenParsingException) {
                        Log.e(
                            TAG,
                            "Fallback GoogleIdTokenParsingException: class=${e2::class.java.simpleName} message=${e2.message}",
                            e2
                        )
                        throw e2
                    }
                } else {
                    Log.w(
                        TAG,
                        "Fallback credential is not CustomCredential: ${fallbackResult.credential::class.java.name}"
                    )
                    throw IllegalStateException("Fallback not CustomCredential")
                }
            } catch (fallbackEx: GetCredentialCancellationException) {
                Log.d(
                    TAG,
                    "Fallback GetCredentialCancellationException: class=${fallbackEx::class.java.simpleName} type=${fallbackEx.type}",
                    fallbackEx
                )
                throw fallbackEx
            } catch (fallbackEx: NoCredentialException) {
                Log.w(
                    TAG,
                    "Fallback NoCredentialException: class=${fallbackEx::class.java.simpleName} type=${fallbackEx.type} message=${fallbackEx.message}",
                    fallbackEx
                )
                throw fallbackEx
            } catch (fallbackEx: GetCredentialException) {
                Log.e(
                    TAG,
                    "Fallback GetCredentialException: class=${fallbackEx::class.java.simpleName} type=${fallbackEx.type} message=${fallbackEx.message} cause=${fallbackEx.cause?.javaClass?.simpleName}:${fallbackEx.cause?.message}",
                    fallbackEx
                )
                throw fallbackEx
            } catch (fallbackEx: Exception) {
                Log.e(
                    TAG,
                    "Fallback unexpected exception: class=${fallbackEx::class.java.simpleName} message=${fallbackEx.message}",
                    fallbackEx
                )
                throw fallbackEx
            }
        } catch (e: GetCredentialException) {
            Log.e(
                TAG,
                "GetCredentialException: class=${e::class.java.simpleName} type=${e.type} message=${e.message} cause=${e.cause?.javaClass?.simpleName}:${e.cause?.message}",
                e
            )
            throw e
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(
                TAG,
                "GoogleIdTokenParsingException: class=${e::class.java.simpleName} message=${e.message}",
                e
            )
            throw e
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Unexpected exception during Google credential request: class=${e::class.java.simpleName} message=${e.message}",
                e
            )
            throw e
        }
    }

    /**
     * Classifies a GetCredential failure into user-facing categories without exposing
     * technical details. Returns a string resource name for logging.
     */
    fun classifyException(e: Throwable): String {
        return when (e) {
            is GetCredentialCancellationException -> "CANCELLED"
            is NoCredentialException -> "NO_CREDENTIAL"
            is GoogleIdTokenParsingException -> "PARSING_ERROR"
            is GetCredentialException -> {
                val msg = (e.message ?: "").lowercase()
                val type = e.type.lowercase()
                when {
                    msg.contains("network") || msg.contains("unavailable") || msg.contains("timeout") || type.contains(
                        "network"
                    ) -> "NETWORK"

                    msg.contains("configuration") || msg.contains("client") || msg.contains("audience") || msg.contains(
                        "oauth"
                    ) || msg.contains("developer") || msg.contains("403") -> "CONFIG"

                    type.contains("no_credential") || msg.contains("no credential") -> "NO_CREDENTIAL"
                    else -> "UNKNOWN_GET_CREDENTIAL"
                }
            }

            is IllegalStateException -> {
                val msg = (e.message ?: "").lowercase()
                when {
                    msg.contains("blank") && msg.contains("client") -> "CONFIG_BLANK"
                    else -> "GENERIC"
                }
            }

            else -> "GENERIC"
        }
    }
}
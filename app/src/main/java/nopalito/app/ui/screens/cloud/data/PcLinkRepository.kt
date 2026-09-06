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
import nopalito.app.ui.screens.cloud.model.ApiResponse
import nopalito.app.ui.screens.cloud.model.ApproveByPinRequest
import nopalito.app.ui.screens.cloud.model.ApproveQrRequest
import nopalito.app.ui.screens.cloud.model.CloudLinkSession
import nopalito.app.ui.screens.cloud.model.PcLinkError
import nopalito.app.ui.screens.cloud.network.CloudApiClient
import nopalito.app.ui.screens.cloud.network.CloudLinkApi
import java.io.IOException

private const val TAG = "PcLinkRepository"

/**
 * Cloud Link pairing (Receive scans on your PC, Phase 2).
 *
 * The app only APPROVES pairings created by the web panel: it never sees the
 * panel service key, the PIN hash or any token. Calls run under the mobile
 * JWT added by AuthInterceptor. Failures surface as [PcLinkError] (UI text
 * resolution lives in ErrorCodeMapper); the pairing identifiers persist in
 * [PcLinkDataStore], never tokens or secrets.
 */
class PcLinkRepository(
    private val api: CloudLinkApi,
    private val store: PcLinkDataStore
) {
    constructor(context: Context) : this(
        CloudApiClient.getInstance(context.applicationContext).cloudLinkApi,
        PcLinkDataStore.getInstance(context.applicationContext)
    )

    /** Approve by scanning the QR shown on the PC. */
    suspend fun approveQr(intentId: String, qrNonce: String): Result<Unit> {
        Log.i(TAG, "approveQr: intent=${intentId.take(8)}")
        return safeUnitCall { api.approveQr(ApproveQrRequest(intentId, qrNonce)) }
    }

    /** Approve by typing the 6-digit PIN shown on the PC. */
    suspend fun approveByPin(pin: String, intentId: String? = null): Result<Unit> {
        Log.i(TAG, "approveByPin: intent=${intentId?.take(8) ?: "auto"}")
        return safeUnitCall { api.approveByPin(ApproveByPinRequest(pin, intentId)) }
    }

    /** Browsers currently linked to this account (user scoped server-side). */
    suspend fun listSessions(): Result<List<CloudLinkSession>> {
        return safeCall({ api.listSessions() }) { it.sessions }
    }

    /** Unlink one browser remotely. */
    suspend fun revokeSession(sessionId: String): Result<Unit> {
        Log.i(TAG, "revokeSession: ${sessionId.take(8)}")
        return safeUnitCall { api.revokeSession(sessionId) }
    }

    suspend fun saveLinkSession(sessionId: String, webDeviceId: String) {
        store.saveLinkSession(sessionId, webDeviceId)
    }

    suspend fun getLinkSession(): Pair<String, String>? = store.getLinkSession()

    suspend fun clearLinkSession() {
        store.clearLinkSession()
    }

    fun linkState() = store.state

    private suspend fun safeUnitCall(
        call: suspend () -> retrofit2.Response<ApiResponse<Unit>>
    ): Result<Unit> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(mapHttpError(response.code(), errorBodyOf(response)))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: IOException) {
            Result.failure(PcLinkError.NetworkError)
        } catch (_: Exception) {
            Result.failure(PcLinkError.Unknown(null))
        }
    }

    private suspend fun <T, R> safeCall(
        call: suspend () -> retrofit2.Response<ApiResponse<T>>,
        transform: (T) -> R
    ): Result<R> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                val data = response.body()?.data
                if (data != null) Result.success(transform(data))
                else Result.failure(PcLinkError.Unknown(null))
            } else {
                Result.failure(mapHttpError(response.code(), errorBodyOf(response)))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: IOException) {
            Result.failure(PcLinkError.NetworkError)
        } catch (_: Exception) {
            Result.failure(PcLinkError.Unknown(null))
        }
    }

    private fun errorBodyOf(response: retrofit2.Response<*>): String? {
        return try {
            response.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /**
         * Maps an HTTP failure to [PcLinkError] from the stable backend code
         * first and the status second. Pure function, unit-testable.
         */
        fun mapHttpError(statusCode: Int, rawBody: String?): PcLinkError {
            val parsed = ErrorParser.parse(statusCode, rawBody)
            return when (parsed.code) {
                "LINK_INTENT_NOT_FOUND", "LINK_SESSION_NOT_FOUND" -> PcLinkError.IntentNotFound
                "LINK_INTENT_EXPIRED", "LINK_SESSION_EXPIRED" -> PcLinkError.IntentExpired
                "LINK_INTENT_BLOCKED" -> PcLinkError.IntentBlocked
                "LINK_INTENT_INVALID_STATE", "LINK_PIN_INVALID", "LINK_NONCE_MISMATCH",
                "LINK_REFRESH_INVALID" -> PcLinkError.IntentInvalidState

                "LINK_OWNER_MISMATCH" -> PcLinkError.UserMismatch
                "LINK_SESSION_REVOKED" -> PcLinkError.Unauthorized
                else -> when (statusCode) {
                    401 -> PcLinkError.Unauthorized
                    403 -> PcLinkError.UserMismatch
                    404 -> PcLinkError.IntentNotFound
                    410 -> PcLinkError.IntentExpired
                    429 -> PcLinkError.RateLimited
                    else -> PcLinkError.Unknown(parsed.code)
                }
            }
        }

        /** Fallback for an empty 2xx body where data was expected. */
        fun missingData(): PcLinkError = PcLinkError.Unknown(null)
    }
}

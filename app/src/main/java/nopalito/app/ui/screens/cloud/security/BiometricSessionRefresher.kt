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

package nopalito.app.ui.screens.cloud.security

import android.util.Log
import nopalito.app.ui.screens.cloud.data.NeedsBiometricUnlockException
import nopalito.app.ui.screens.cloud.model.TokenData
import nopalito.app.ui.screens.cloud.network.LogoutException

private const val TAG = "BiometricSessionRefresher"

/**
 * Orchestrates a biometric-mode token refresh:
 *
 * 1. decrypt the stored refresh token with Tier-2 (in memory, no prompt);
 * 2. exchange it via [refreshCall];
 * 3. re-encrypt the rotated token with Tier-2 and persist it atomically
 *    (again no prompt).
 *
 * Error handling:
 * - `LogoutException` (HTTP 401): Tier-2 is destroyed immediately; the caller
 *   (session manager / repository) clears the persisted session.
 * - any other failure (e.g. HTTP 500, network): the session and the persisted
 *   blob stay intact; the caller may retry.
 * - a failed atomic save keeps the previous blob; the session continues in
 *   memory and the blob is re-rotated on the next successful refresh.
 *
 * All logs are sanitized — no token material ever reaches a log line.
 */
class BiometricSessionRefresher(
    private val session: BiometricUnlockSession,
    private val refreshCall: suspend (refreshToken: String) -> Result<TokenData>,
) {

    suspend fun refresh(): Result<TokenData> {
        val current = session.decryptRefreshToken()
            ?: return Result.failure(NeedsBiometricUnlockException())
        val result = refreshCall(current)
        if (result.isFailure) {
            if (result.exceptionOrNull() is LogoutException) {
                Log.i(TAG, "refresh: backend rejected the session; wiping Tier-2")
                session.wipe()
            }
            return result
        }
        val rotated = result.getOrThrow().refreshToken
        if (!session.rotateRefreshToken(rotated)) {
            Log.w(TAG, "refresh: rotated token could not be persisted; session continues in memory")
        }
        return result
    }
}
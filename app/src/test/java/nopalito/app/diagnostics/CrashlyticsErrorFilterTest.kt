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

package nopalito.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private class LogoutException(message: String) : Exception(message)
private class NeedsBiometricUnlockException(message: String) : Exception(message)
private class ApiException(message: String) : Exception(message)

/** Simulates R8-obfuscated LogoutException: simpleName no longer matches. */
private class p94(message: String) : IOException(message)

class CrashlyticsErrorFilterTest {

    @Test
    fun `reports unexpected runtime exceptions`() {
        val decision = CrashlyticsErrorFilter.shouldReport(RuntimeException("boom"))
        assertTrue(decision.report)
    }

    @Test
    fun `reports unexpected IO failures`() {
        val decision = CrashlyticsErrorFilter.shouldReport(IOException("disk full"))
        assertTrue(decision.report)
    }

    @Test
    fun `filters coroutine cancellation`() {
        val decision = CrashlyticsErrorFilter.shouldReport(
            kotlinx.coroutines.CancellationException("scope cancelled")
        )
        assertFalse(decision.report)
    }

    @Test
    fun `filters offline host errors`() {
        assertFalse(CrashlyticsErrorFilter.shouldReport(UnknownHostException()).report)
    }

    @Test
    fun `filters timeouts`() {
        assertFalse(CrashlyticsErrorFilter.shouldReport(SocketTimeoutException()).report)
    }

    @Test
    fun `filters refused connections`() {
        assertFalse(CrashlyticsErrorFilter.shouldReport(ConnectException()).report)
    }

    @Test
    fun `filters logout flow signals`() {
        assertFalse(CrashlyticsErrorFilter.shouldReport(LogoutException("expired")).report)
    }

    @Test
    fun `filters biometric unlock flow signals`() {
        assertFalse(
            CrashlyticsErrorFilter.shouldReport(
                NeedsBiometricUnlockException("locked")
            ).report
        )
    }

    @Test
    fun `filters backend business errors`() {
        assertFalse(CrashlyticsErrorFilter.shouldReport(ApiException("INVALID")).report)
    }

    @Test
    fun `filters wrapped logout flow signals`() {
        val wrapped = RuntimeException("Startup language sync failed for es-419", LogoutException("expired"))
        val decision = CrashlyticsErrorFilter.shouldReport(wrapped)
        assertFalse(decision.report)
    }

    @Test
    fun `filters obfuscated refresh failures by message`() {
        val decision = CrashlyticsErrorFilter.shouldReport(
            p94("Refresh already failed in another thread")
        )
        assertFalse(decision.report)
    }

    @Test
    fun `reports unrelated IO failures with similar shape`() {
        val decision = CrashlyticsErrorFilter.shouldReport(IOException("disk full"))
        assertTrue(decision.report)
    }

    @Test
    fun `isSessionFlowSignal matches wrapped and obfuscated signals`() {
        assertTrue(
            CrashlyticsErrorFilter.isSessionFlowSignal(
                RuntimeException("sync failed", LogoutException("expired"))
            )
        )
        assertTrue(
            CrashlyticsErrorFilter.isSessionFlowSignal(
                p94("Refresh already failed in another thread")
            )
        )
        assertFalse(CrashlyticsErrorFilter.isSessionFlowSignal(RuntimeException("boom")))
        assertFalse(CrashlyticsErrorFilter.isSessionFlowSignal(ApiException("INVALID")))
    }
}
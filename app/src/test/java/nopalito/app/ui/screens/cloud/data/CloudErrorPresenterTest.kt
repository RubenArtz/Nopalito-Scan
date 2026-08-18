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

import nopalito.app.R
import nopalito.app.ui.screens.cloud.network.LogoutException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Block: CloudErrorPresenter — the ViewModel-facing message resolver.
 *
 * Enforces the flow `HTTP/body → ErrorParser → ApiException → ErrorCodeMapper →
 * @StringRes → localized string → UI`, the fallback order, that the backend
 * message is never the first choice, and that only legacy (no code, no
 * localized resolution) messages fall back to a sanitized backend string.
 *
 * Pure (no Context): [CloudErrorPresenter.resolve] and [CloudErrorPresenter.sanitizeLegacy]
 * are exercised directly; `@StringRes` ids are compared as ints against R.
 * Final string production is a thin wrapper already covered by ErrorCodeMapper
 * (Etapa 4/5) + the `stringFor` locale mechanism.
 */
class CloudErrorPresenterTest {

    // ---- Specific / group / status / unknown (fallback order 1-4) ----

    @Test
    fun knownSpecificCodeResolvesToItsResource() {
        val e = ApiException("QUOTA_EXCEEDED", null, 400, "You are over quota")
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.cloud_error_quota_exceeded),
            CloudErrorPresenter.resolve(e, R.string.cloud_error_load_files)
        )
    }

    @Test
    fun knownGroupCodeResolvesToGroupResource() {
        val e = ApiException("RATE_LIMIT_EXCEEDED", null, 500, "Slow down")
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.cloud_error_rate_limit),
            CloudErrorPresenter.resolve(e, R.string.cloud_error_load_files)
        )
    }

    @Test
    fun unknownCodeWithKnownStatusResolvesToStatusResource() {
        val e = ApiException("WEIRD_INTERNAL", null, 500, "boom")
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.cloud_error_500),
            CloudErrorPresenter.resolve(e, R.string.cloud_error_load_files)
        )
    }

    @Test
    fun unknownCodeAndStatusFallsToDefaultRes() {
        val e = ApiException("MYSTERY_CODE", null, 418, "teapot")
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.cloud_error_load_files),
            CloudErrorPresenter.resolve(e, R.string.cloud_error_load_files)
        )
    }

    @Test
    fun knownSpecificCodeTakesPriorityOverBackendMessage() {
        val e = ApiException("MAINTENANCE_ACTIVE", null, 200, "Maintenance")
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.cloud_error_maintenance_active),
            CloudErrorPresenter.resolve(e, R.string.error_unknown)
        )
    }

    // ---- Legacy backend message (fallback 5, only when no code + no localization) ----

    @Test
    fun legacyNoCodeWithBackendMessageReturnsSanitizedLegacy() {
        val e = ApiException(null, null, 200, "Server hiccup")
        val resolved = CloudErrorPresenter.resolve(e, R.string.error_unknown)
        assertEquals(CloudErrorPresenter.Resolved.Legacy("Server hiccup"), resolved)
    }

    @Test
    fun legacyNoCodeWithBlankBackendMessageFallsToDefault() {
        val e = ApiException(null, null, 200, "   ")
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.error_unknown),
            CloudErrorPresenter.resolve(e, R.string.error_unknown)
        )
    }

    @Test
    fun codedErrorNeverFallsBackToBackendMessage() {
        val e = ApiException("SOME_CODE", null, 599, "Should never be shown raw")
        val resolved = CloudErrorPresenter.resolve(e, R.string.error_unknown)
        assertTrue("coded error must resolve to a resource, not Legacy", resolved is CloudErrorPresenter.Resolved.Res)
    }

    @Test
    fun nullThrowableFallsToDefault() {
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.cloud_error_load_files),
            CloudErrorPresenter.resolve(null, R.string.cloud_error_load_files)
        )
    }

    // ---- Network / non-API errors ----

    @Test
    fun socketTimeoutMapsToConnectionTimeout() {
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.cloud_connection_timeout),
            CloudErrorPresenter.resolve(SocketTimeoutException(), R.string.error_unknown)
        )
    }

    @Test
    fun unknownHostMapsToConnectionError() {
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.cloud_connection_error),
            CloudErrorPresenter.resolve(UnknownHostException(), R.string.error_unknown)
        )
    }

    @Test
    fun connectExceptionMapsToConnectionError() {
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.cloud_connection_error),
            CloudErrorPresenter.resolve(ConnectException(), R.string.error_unknown)
        )
    }

    @Test
    fun genericIoExceptionMapsToConnectionError() {
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.cloud_connection_error),
            CloudErrorPresenter.resolve(IOException("timeout"), R.string.error_unknown)
        )
    }

    @Test
    fun logoutExceptionFallsToDefaultNotConnection() {
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.error_unknown),
            CloudErrorPresenter.resolve(LogoutException("logged out"), R.string.error_unknown)
        )
    }

    @Test
    fun unrelatedRuntimeExceptionFallsToDefault() {
        assertEquals(
            CloudErrorPresenter.Resolved.Res(R.string.cloud_error_load_files),
            CloudErrorPresenter.resolve(IllegalStateException("x"), R.string.cloud_error_load_files)
        )
    }

    // ---- sanitizeLegacy ----

    @Test
    fun sanitizeStripsHtmlAndControlCharacters() {
        val cleaned = CloudErrorPresenter.sanitizeLegacy("<b>alert</b>\n\r\u0000boom\u0007")
        assertEquals("alert boom", cleaned)
    }

    @Test
    fun sanitizeStripsUrlsTokensAndPaths() {
        val dirty = "Failed https://example.com/auth?t=1 Bearer eyJhbGci token and /home/user/file.txt"
        val cleaned = CloudErrorPresenter.sanitizeLegacy(dirty)
        assertTrue(cleaned != null && !cleaned.contains("https://"))
        assertTrue(cleaned != null && !cleaned.contains("Bearer"))
        assertTrue(cleaned != null && !cleaned.contains("eyJhbGci"))
        assertTrue(cleaned != null && !cleaned.contains("/home/user"))
    }

    @Test
    fun sanitizeStripsStackFramesAndCollapsesWhitespace() {
        val dirty = "boom   at java.lang.Throwable.run(x.java:12)   done"
        val cleaned = CloudErrorPresenter.sanitizeLegacy(dirty)
        assertEquals("boom done", cleaned)
    }

    @Test
    fun sanitizeCapsLengthAtTwoHundred() {
        val long = "m".repeat(500)
        val cleaned = CloudErrorPresenter.sanitizeLegacy(long)
        assertTrue(cleaned != null && cleaned.length <= 201)
    }

    @Test
    fun sanitizeReturnsNullForBlank() {
        assertNull(CloudErrorPresenter.sanitizeLegacy("  \n  "))
        assertNull(CloudErrorPresenter.sanitizeLegacy(null))
    }

    // ---- No raw details ----

    @Test
    fun legacyNeverRendersRawDetailsMap() {
        val e = ApiException(null, mapOf("count" to 3, "waitSeconds" to 60), 200, "too many")
        val resolved = CloudErrorPresenter.resolve(e, R.string.error_unknown)
        val text = (resolved as CloudErrorPresenter.Resolved.Legacy).text
        assertTrue(!text.contains("waitSeconds"))
        assertTrue(!text.contains("{"))
    }
}
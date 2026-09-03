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

package nopalito.app.ui.screens.cloud.screens

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import nopalito.app.R
import nopalito.app.ui.screens.cloud.data.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * authAccountDialog maps account-level codes to the error modal and keeps
 * everything else inline (returns null).
 */
class CloudAuthDialogTest {

    private val application = mockk<Application>().apply {
        every { resources } returns mockk<Resources>().apply {
            every { configuration } returns Configuration()
        }
        every { createConfigurationContext(any()) } answers {
            val context = mockk<android.content.Context>()
            every { context.resources } returns mockk<Resources>().apply {
                every { getString(any()) } answers { "str:" + firstArg<Int>() }
                every { getString(any(), *anyVararg()) } answers { "str:" + firstArg<Int>() }
            }
            context
        }
    }

    private fun resIdOf(text: String): Int = text.removePrefix("str:").toInt()

    @Test
    fun suspendedAccountMapsToSuspensionDialog() {
        val dialog =
            authAccountDialog(ApiException(ApiException.AUTH_ACCOUNT_SUSPENDED, "x"), application)
        assertNotNull(dialog)
        assertEquals(R.string.cloud_dialog_account_suspended_title, resIdOf(dialog!!.title))
        assertEquals(R.string.cloud_dialog_account_suspended_body, resIdOf(dialog.message))
    }

    @Test
    fun passwordResetBlockedForSuspendedAccountMapsToSuspensionDialog() {
        val dialog = authAccountDialog(
            ApiException(ApiException.AUTH_PASSWORD_RESET_BLOCKED_SUSPENDED, "x"),
            application
        )
        assertNotNull(dialog)
        assertEquals(R.string.cloud_dialog_account_suspended_title, resIdOf(dialog!!.title))
        assertEquals(R.string.cloud_dialog_account_suspended_body, resIdOf(dialog.message))
    }

    @Test
    fun accountNotFoundStillMapsToItsOwnDialog() {
        val dialog =
            authAccountDialog(ApiException(ApiException.ACCOUNT_NOT_FOUND, "x"), application)
        assertNotNull(dialog)
        assertEquals(R.string.cloud_dialog_account_not_found_title, resIdOf(dialog!!.title))
    }

    @Test
    fun registerBlockCodesStayInline() {
        assertNull(
            authAccountDialog(
                ApiException(ApiException.AUTH_REGISTER_IP_LIMIT_REACHED, "x"),
                application
            )
        )
        assertNull(
            authAccountDialog(
                ApiException(ApiException.AUTH_REGISTER_VPN_NOT_ALLOWED, "x"),
                application
            )
        )
        assertNull(
            authAccountDialog(
                ApiException(ApiException.AUTH_ACCOUNT_STATUS_UNKNOWN, "x"),
                application
            )
        )
    }

    @Test
    fun nonApiExceptionsAndUnknownCodesReturnNull() {
        assertNull(authAccountDialog(IOException("network"), application))
        assertNull(authAccountDialog(ApiException("SOME_OTHER_CODE", "x"), application))
        assertNull(authAccountDialog(ApiException(null, "no code"), application))
    }
}
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

package nopalito.app.share

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShareDocumentTest {

    private fun uri(path: String): Uri = Uri.parse("content://nopalito.app.fileprovider/$path")

    @Test
    fun `empty list yields no intent`() {
        assertNull(ShareDocument.createSendIntent(emptyList(), "application/pdf"))
    }

    @Test
    fun `single uri uses send with stream and read grant`() {
        val shared = uri("pdfs/doc.pdf")
        val intent =
            requireNotNull(ShareDocument.createSendIntent(listOf(shared), "application/pdf"))

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/pdf", intent.type)
        assertEquals(shared, intent.extras?.get(Intent.EXTRA_STREAM))
        assertNotNull(intent.clipData)
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    @Test
    fun `multiple uris use send multiple with stream list`() {
        val first = uri("pdfs/a.pdf")
        val second = uri("pdfs/b.pdf")
        val intent = requireNotNull(ShareDocument.createSendIntent(listOf(first, second), "*/*"))

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("*/*", intent.type)
        assertEquals(arrayListOf(first, second), intent.extras?.get(Intent.EXTRA_STREAM))
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}
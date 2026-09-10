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

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedImportParserTest {

    private fun uri(path: String): Uri = Uri.parse("content://provider/$path")

    @Test
    fun `null and unrelated actions yield nothing`() {
        assertTrue(SharedImportParser.extractUris(null).isEmpty())
        assertTrue(SharedImportParser.extractUris(Intent(Intent.ACTION_MAIN)).isEmpty())
    }

    @Test
    fun `send with stream extra yields single uri`() {
        val shared = uri("doc.pdf")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, shared)
        }
        assertEquals(listOf(shared), SharedImportParser.extractUris(intent))
    }

    @Test
    fun `view with data yields uri`() {
        val shared = uri("photo.jpg")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(shared, "image/jpeg")
        }
        assertEquals(listOf(shared), SharedImportParser.extractUris(intent))
    }

    @Test
    fun `send multiple deduplicates stream list data and clip`() {
        val first = uri("a.jpg")
        val second = uri("b.jpg")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
            clipData = ClipData.newRawUri(null, second)
        }
        assertEquals(listOf(first, second), SharedImportParser.extractUris(intent))
    }
}
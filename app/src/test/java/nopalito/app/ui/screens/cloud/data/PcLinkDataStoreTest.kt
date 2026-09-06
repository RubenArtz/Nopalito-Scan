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

import android.app.Application
import android.content.Context
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.test.runTest
import nopalito.app.ui.screens.cloud.model.PcLinkSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * DataStore round-trip for the PC-link pairing receipt. Each test resets the
 * singleton (fresh in-memory state) and wipes the preferences file, so cases
 * never leak into each other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PcLinkDataStoreTest {

    private fun newStore(): PcLinkDataStore {
        resetSingleton()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("pc_link", Context.MODE_PRIVATE).edit().clear().commit()
        // DataStore files live apart from SharedPreferences; drop them too.
        context.filesDir.resolve("../shared_prefs").listFiles()
            ?.filter { it.name.startsWith("pc_link") }
            ?.forEach { it.delete() }
        context.dataStoreFile("pc_link.preferences_pb").delete()
        return PcLinkDataStore.getInstance(context)
    }

    @Test
    fun `save and read round-trip`() = runTest {
        val store = newStore()
        store.saveLinkSession("intent-1", "nonce-1")
        assertEquals("intent-1" to "nonce-1", store.getLinkSession())
    }

    @Test
    fun `clear removes the pairing`() = runTest {
        val store = newStore()
        store.saveLinkSession("intent-1", "nonce-1")
        store.clearLinkSession()
        assertNull(store.getLinkSession())
    }

    @Test
    fun `upload status is recorded`() = runTest {
        val store = newStore()
        store.recordUpload(PcLinkSyncStatus.SYNCED, atMs = 123L)
        val state = store.reload()
        assertEquals(PcLinkSyncStatus.SYNCED, state.lastSyncStatus)
        assertEquals(123L, state.lastUploadAt)
    }

    private fun resetSingleton() {
        val field: Field = PcLinkDataStore::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }
}

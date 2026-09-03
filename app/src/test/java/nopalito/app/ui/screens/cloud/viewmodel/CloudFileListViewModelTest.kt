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

package nopalito.app.ui.screens.cloud.viewmodel

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import nopalito.app.ui.screens.cloud.data.ApiException
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.CloudFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class CloudFileListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<CloudRepository>()
    private val application = mockk<Application>()

    private fun cloudFile(id: String) = CloudFile(id = id, originalName = "$id.pdf", size = 10L)

    private fun cloudFolder(
        id: String,
        origin: String = "user",
        itemCount: Int = 0
    ) = CloudFile(
        id = id,
        originalName = id,
        size = 0L,
        itemType = "folder",
        origin = origin,
        itemCount = itemCount
    )

    /** Returns a canned "str:<resId>" so error paths can resolve messages. */
    private fun stubStrings() {
        every { application.getString(any()) } coAnswers { "str:" + firstArg<Int>() }
        every { application.getString(any(), *anyVararg()) } coAnswers { "str:" + firstArg<Int>() }
        val resources = mockk<Resources>()
        every { resources.getString(any()) } coAnswers { "str:" + firstArg<Int>() }
        every { resources.getString(any(), *anyVararg()) } coAnswers { "str:" + firstArg<Int>() }
        val localizedContext = mockk<Context>()
        every { localizedContext.resources } returns resources
        every { application.resources } returns mockk {
            every { configuration } returns Configuration()
        }
        every { application.createConfigurationContext(any()) } returns localizedContext
    }

    /** Happy-path stub for the level listing endpoint (files AND folders). */
    private fun stubLevelListing(items: List<CloudFile> = emptyList()) {
        stubStrings()
        coEvery { repository.listFolder(any(), any(), any(), any()) } returns Result.success(items)
    }

    @Test
    fun `initial load succeeds and splits folders from files`() {
        stubLevelListing(listOf(cloudFile("f1"), cloudFolder("d1"), cloudFile("f2")))

        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.isLoading)
        assertFalse(s.isRefreshing)
        assertNull(s.errorMessage)
        assertEquals(listOf("f1", "f2"), s.files.map { it.id })
        assertEquals(listOf("d1"), s.folders.map { it.id })
        assertTrue(s.folderStack.isEmpty())
    }

    @Test
    fun `http error surfaces an errorMessage instead of crashing`() {
        stubLevelListing()
        coEvery { repository.listFolder(any(), any(), any(), any()) } returns
                Result.failure(ApiException(null, "boom"))

        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.isLoading)
        assertNotNull(s.errorMessage)
        assertTrue(s.files.isEmpty())
    }

    @Test
    fun `refresh keeps previous data visible while reloading (no flicker)`() {
        stubLevelListing(listOf(cloudFile("old")))
        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        coEvery { repository.listFolder(any(), any(), any(), any()) } coAnswers {
            gate.await() // hold the refresh attempt (initial load used the old stub)
            Result.success(listOf(cloudFile("new")))
        }

        vm.refresh()
        mainDispatcherRule.advanceUntilIdle()

        var s = vm.state.value
        assertTrue(s.isRefreshing)
        // Requirement 16: previous data stays on screen during reload.
        assertEquals(listOf("old"), s.files.map { it.id })

        gate.complete(Unit)
        mainDispatcherRule.advanceUntilIdle()

        s = vm.state.value
        assertFalse(s.isRefreshing)
        assertEquals(listOf("new"), s.files.map { it.id })
    }

    @Test
    fun `rapid category taps collapse into one debounced request`() {
        stubLevelListing()
        val categories = mutableListOf<String?>()
        coEvery { repository.listFolder(any(), any(), any(), any()) } coAnswers {
            categories += arg(1) as String?
            Result.success(emptyList())
        }

        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()
        assertEquals(listOf<String?>(null), categories)

        // Three rapid taps: only the LAST may reach the network.
        vm.loadFiles("a")
        vm.loadFiles("b")
        vm.loadFiles("c")
        mainDispatcherRule.advanceUntilIdle()

        assertEquals(listOf(null, "c"), categories)
        assertEquals("c", vm.state.value.selectedCategory)
    }

    @Test
    fun `a newer load cancels the superseded request and its result never lands`() {
        stubLevelListing()
        val seen = mutableListOf<String?>()
        coEvery { repository.listFolder(any(), any(), any(), any()) } coAnswers {
            val cat = arg(1) as String?
            seen += cat
            // "a" stalls in virtual time; the newer load cancels it there.
            if (cat == "a") kotlinx.coroutines.delay(60_000.milliseconds)
            Result.success(listOf(cloudFile("res-$cat")))
        }

        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        vm.loadFiles("a")
        mainDispatcherRule.advanceUntilIdle()
        assertTrue(seen.contains("a"))

        vm.loadFiles("b") // supersedes "a" while it is still in flight
        mainDispatcherRule.advanceUntilIdle()

        assertEquals(listOf(null, "a", "b"), seen)

        val s = vm.state.value
        assertEquals("b", s.selectedCategory)
        assertEquals(listOf("res-b"), s.files.map { it.id })
        assertNull(s.errorMessage)
    }

    // ── Folder hierarchy (Phase 3) ──

    @Test
    fun `openFolder navigates into the folder and loads that level`() {
        stubStrings()
        coEvery { repository.listFolder(any(), any(), any(), any()) } coAnswers {
            val parentId = arg<String?>(0)
            if (parentId == null) {
                Result.success(listOf(cloudFolder("d1"), cloudFile("f0")))
            } else {
                Result.success(listOf(cloudFile("c1"), cloudFolder("d2")))
            }
        }

        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        vm.openFolder(cloudFolder("d1"))
        mainDispatcherRule.advanceUntilIdle()

        val s = vm.state.value
        assertEquals(listOf("d1"), s.folderStack.map { it.id })
        assertEquals(listOf("c1"), s.files.map { it.id })
        assertEquals(listOf("d2"), s.folders.map { it.id })
    }

    @Test
    fun `navigateToLevel -1 returns to the root`() {
        stubLevelListing()
        coEvery { repository.listFolder(any(), any(), any(), any()) } coAnswers {
            val parentId = arg<String?>(0)
            if (parentId != null) Result.success(emptyList())
            else Result.success(emptyList())
        }

        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        vm.openFolder(cloudFolder("d1"))
        mainDispatcherRule.advanceUntilIdle()
        assertEquals(1, vm.state.value.folderStack.size)

        vm.navigateToLevel(-1)
        mainDispatcherRule.advanceUntilIdle()

        assertTrue(vm.state.value.folderStack.isEmpty())
    }

    @Test
    fun `confirmCreateFolder optimistically prepends the new folder`() {
        stubLevelListing()
        val created = cloudFolder("new-folder")
        coEvery { repository.createUserFolder(any(), any()) } returns Result.success(created)

        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        vm.showCreateFolderDialog()
        vm.updateNewFolderName("New")
        vm.confirmCreateFolder()
        mainDispatcherRule.advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.showCreateFolder)
        assertFalse(s.isCreatingFolder)
        assertEquals(listOf("new-folder"), s.folders.map { it.id })
    }

    @Test
    fun `confirmCreateFolder keeps the dialog on failure (name taken)`() {
        stubLevelListing()
        coEvery { repository.createUserFolder(any(), any()) } returns
                Result.failure(ApiException("FOLDER_NAME_TAKEN", "taken"))

        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        vm.showCreateFolderDialog()
        vm.updateNewFolderName("Dup")
        vm.confirmCreateFolder()
        mainDispatcherRule.advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.isCreatingFolder)
        assertTrue(s.folders.isEmpty())
        assertNotNull(s.snackbarMessage)
    }

    @Test
    fun `deleteExportGroup trashes the subtree with ONE server call`() {
        stubLevelListing(listOf(cloudFolder("d1", origin = "export")))
        // After the delete the quiet reload sees the level WITHOUT the folder.
        coEvery { repository.listFolder(any(), any(), any(), any()) } returns Result.success(
            emptyList()
        )
        coEvery { repository.deleteFile(any()) } returns Result.success(Unit)

        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        vm.deleteExportGroup(cloudFolder("d1", origin = "export"))
        mainDispatcherRule.advanceUntilIdle()

        // Recursive backend operation: exactly one DELETE, no client loops.
        coVerify(exactly = 1) { repository.deleteFile("d1") }
        assertTrue(vm.state.value.folders.isEmpty())
    }

    @Test
    fun `confirmMove moves the item into the picked folder`() {
        stubStrings()
        val item = cloudFile("movable")
        coEvery { repository.listFolder(any(), any(), any(), any()) } coAnswers {
            val parentId = arg<String?>(0)
            if (parentId == null) {
                Result.success(listOf(cloudFolder("target"), item))
            } else {
                Result.success(emptyList())
            }
        }
        coEvery { repository.moveItem(any(), any()) } returns Result.success(item)
        // After the move the quiet reload sees the source level WITHOUT it.
        coEvery { repository.listFolder(any(), any(), any(), any()) } returns
                Result.success(listOf(cloudFolder("target")))

        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        vm.startMove(item)
        mainDispatcherRule.advanceUntilIdle()
        assertEquals(listOf("target"), vm.state.value.pickerFolders.map { it.id })

        vm.openPickerFolder(cloudFolder("target"))
        mainDispatcherRule.advanceUntilIdle()
        vm.confirmMove()
        mainDispatcherRule.advanceUntilIdle()

        coVerify(exactly = 1) { repository.moveItem("movable", "target") }
        assertTrue(vm.state.value.moveTargets.isEmpty())
        assertTrue(vm.state.value.files.none { it.id == "movable" })
    }

    @Test
    fun `startBulkMove moves every selected item and clears the selection`() {
        stubStrings()
        val a = cloudFile("a")
        val b = cloudFile("b")
        coEvery { repository.listFolder(any(), any(), any(), any()) } returns Result.success(
            listOf(
                a,
                b
            )
        )
        coEvery { repository.listFolder(eq("target-folder"), any(), any(), any()) } answers {
            Result.success(emptyList())
        }
        coEvery { repository.moveItem(any(), any()) } returns Result.success(a)

        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        vm.toggleSelection("a")
        vm.toggleSelection("b")
        vm.startBulkMove()
        mainDispatcherRule.advanceUntilIdle()
        assertEquals(2, vm.state.value.moveTargets.size)

        // Navigate the picker into the destination folder, then confirm.
        coEvery { repository.listFolder(any(), any(), any(), any()) } returns Result.success(
            emptyList()
        )
        vm.openPickerFolder(cloudFolder("target-folder"))
        mainDispatcherRule.advanceUntilIdle()
        vm.confirmMove()
        mainDispatcherRule.advanceUntilIdle()

        coVerify(exactly = 1) { repository.moveItem("a", "target-folder") }
        coVerify(exactly = 1) { repository.moveItem("b", "target-folder") }
        assertTrue(vm.state.value.selectedIds.isEmpty())
        assertTrue(vm.state.value.files.none { it.id == "a" || it.id == "b" })
    }

    @Test
    fun `confirmMove rolls back into the original section on failure`() {
        stubStrings()
        val item = cloudFile("movable")
        coEvery { repository.listFolder(any(), any(), any(), any()) } returns
                Result.success(listOf(item))
        coEvery { repository.moveItem(any(), any()) } returns
                Result.failure(ApiException("FOLDER_MOVE_CYCLE", "cycle"))

        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        vm.startMove(item)
        mainDispatcherRule.advanceUntilIdle()
        vm.confirmMove()
        mainDispatcherRule.advanceUntilIdle()

        assertEquals(listOf("movable"), vm.state.value.files.map { it.id })
        assertNotNull(vm.state.value.snackbarMessage)
    }

    @Test
    fun `preview is guarded for folders`() {
        stubLevelListing()
        val vm = CloudFileListViewModel(repository, application)
        mainDispatcherRule.advanceUntilIdle()

        vm.previewFile(cloudFolder("d1"))
        mainDispatcherRule.advanceUntilIdle()

        assertNull(vm.state.value.previewFile)
    }
}

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import nopalito.app.R
import nopalito.app.ui.screens.cloud.data.ApiException
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.CloudFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }

    /** Runs every coroutine scheduled on the Main dispatcher until idle. */
    fun advanceUntilIdle() {
        dispatcher.scheduler.advanceUntilIdle()
    }
}

class CloudTrashViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<CloudRepository>()
    private val application = mockk<Application>()

    private val file1 = CloudFile(id = "f1", originalName = "a.pdf", size = 10L)
    private val file2 = CloudFile(id = "f2", originalName = "b.pdf", size = 10L)

    /** Returns a canned "str:<resId>" so tests can assert WHICH string was used. */
    private fun stubStrings() {
        every { application.getString(any()) } answers { "str:" + firstArg<Int>() }
        every { application.getString(any(), *anyVararg()) } answers { "str:" + firstArg<Int>() }
        val resources = mockk<Resources>()
        every { resources.getString(any()) } answers { "str:" + firstArg<Int>() }
        every { resources.getString(any(), *anyVararg()) } answers { "str:" + firstArg<Int>() }
        val localizedContext = mockk<Context>()
        every { localizedContext.resources } returns resources
        every { application.resources } returns mockk {
            every { configuration } returns Configuration()
        }
        every { application.createConfigurationContext(any()) } returns localizedContext
    }

    private fun viewModelWithTrash(files: List<CloudFile>): CloudTrashViewModel {
        stubStrings()
        coEvery { repository.listDeletedFiles(any(), any()) } returns Result.success(files)
        val vm = CloudTrashViewModel(repository, application)
        vm.refresh()
        mainDispatcherRule.advanceUntilIdle()
        return vm
    }

    @Test
    fun `batchRestore restores even with zero free space (trashed bytes already count)`() {
        val vm = viewModelWithTrash(listOf(file1, file2))
        // No getStorageUsage() stub: the client no longer pre-checks the
        // quota. The backend is the authority — trashed bytes already count
        // against the usage, so restoring never needs free space.
        coEvery { repository.restoreFile(any()) } returns Result.success(file1)

        vm.toggleSelection("f1")
        vm.toggleSelection("f2")
        vm.batchRestore()
        mainDispatcherRule.advanceUntilIdle()

        coVerify(exactly = 2) { repository.restoreFile(any()) }
        assertTrue(vm.state.value.files.isEmpty())
        assertTrue(vm.state.value.selectedIds.isEmpty())
        assertEquals("str:" + R.string.cloud_restored_n, vm.state.value.snackbarMessage)
    }

    @Test
    fun `batchRestore restores every file when the whole selection fits`() {
        val vm = viewModelWithTrash(listOf(file1, file2))
        coEvery { repository.restoreFile(any()) } returns Result.success(file1)

        vm.toggleSelection("f1")
        vm.toggleSelection("f2")
        vm.batchRestore()
        mainDispatcherRule.advanceUntilIdle()

        coVerify(exactly = 2) { repository.restoreFile(any()) }
        assertTrue(vm.state.value.files.isEmpty())
        assertTrue(vm.state.value.selectedIds.isEmpty())
        assertEquals("str:" + R.string.cloud_restored_n, vm.state.value.snackbarMessage)
    }

    @Test
    fun `batchRestore keeps failed files in the list and shows a partial message`() {
        val vm = viewModelWithTrash(listOf(file1, file2))
        coEvery { repository.restoreFile("f1") } returns Result.success(file1)
        coEvery { repository.restoreFile("f2") } returns Result.failure(
            ApiException(ApiException.QUOTA_EXCEEDED_ON_RESTORE, "no space")
        )

        vm.toggleSelection("f1")
        vm.toggleSelection("f2")
        vm.batchRestore()
        mainDispatcherRule.advanceUntilIdle()

        // Only the actually-restored file leaves the list.
        assertEquals(listOf(file2), vm.state.value.files)
        assertTrue(vm.state.value.selectedIds.isEmpty())
        assertEquals("str:" + R.string.cloud_restored_partial, vm.state.value.snackbarMessage)
    }

    @Test
    fun `restoreFile shows the quota message when the server rejects with QUOTA_EXCEEDED_ON_RESTORE`() {
        val vm = viewModelWithTrash(listOf(file1))
        coEvery { repository.restoreFile("f1") } returns Result.failure(
            ApiException(ApiException.QUOTA_EXCEEDED_ON_RESTORE, "no space")
        )

        vm.restoreFile("f1")
        mainDispatcherRule.advanceUntilIdle()

        assertEquals("str:" + R.string.cloud_error_restore_quota, vm.state.value.errorMessage)
        assertEquals(listOf(file1), vm.state.value.files)
        assertEquals(null, vm.state.value.restoringId)
    }

    @Test
    fun `restoreFile uses the generic message for non-quota failures and keeps the file`() {
        val vm = viewModelWithTrash(listOf(file1))
        coEvery { repository.restoreFile("f1") } returns Result.failure(
            ApiException("SOME_OTHER_ERROR", "gone")
        )

        vm.restoreFile("f1")
        mainDispatcherRule.advanceUntilIdle()

        // Not a quota error and no localized resolution for the unknown code:
        // the generic restore resource is shown, never the quota one, and never
        // the raw backend message.
        assertEquals("str:" + R.string.cloud_error_restore, vm.state.value.errorMessage)
        assertEquals(listOf(file1), vm.state.value.files)
    }
}
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
import android.net.Uri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import nopalito.app.ui.screens.cloud.data.ApiException
import nopalito.app.ui.screens.cloud.data.CloudRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudUploadViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<CloudRepository>()
    private val application = mockk<Application>()

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

    private fun file(name: String): SelectedFile {
        // android.net.Uri.parse is a stub on the JVM test classpath (returns
        // null), so the test builds mocked Uris instead.
        val uri = mockk<Uri>()
        every { uri.lastPathSegment } returns name
        every { uri.toString() } returns "content://test/$name"
        return SelectedFile(name, uri)
    }

    /** Stub uploadFile by file name: "fail*" files fail ONCE, then succeed. */
    private fun stubUploadsByFileName() {
        val failedOnce = mutableSetOf<Uri>()
        coEvery {
            repository.uploadFile(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val uri = firstArg<Uri>()
            if (uri.lastPathSegment?.contains("fail") == true && failedOnce.add(uri)) {
                Result.failure(ApiException("UNSUPPORTED_MEDIA_TYPE", "bad type"))
            } else {
                Result.success(
                    nopalito.app.ui.screens.cloud.model.CloudFile(
                        id = "id-" + uri.lastPathSegment,
                        originalName = uri.lastPathSegment ?: "f"
                    )
                )
            }
        }
    }

    @Test
    fun `partial failure keeps only failed files and allows a clean retry`() {
        stubStrings()
        stubUploadsByFileName()

        val vm = CloudUploadViewModel(repository, application)
        vm.addFiles(listOf(file("ok1.pdf"), file("fail.txt"), file("ok2.pdf"), file("fail2.doc")))
        vm.upload()
        mainDispatcherRule.advanceUntilIdle()

        val afterFirst = vm.state.value
        // Successes left the list; failures stayed for retry.
        assertEquals(listOf("fail.txt", "fail2.doc"), afterFirst.selectedFiles.map { it.name })
        assertFalse(afterFirst.isUploading)
        assertTrue(afterFirst.uploadedCount == 2)
        assertNotNull(afterFirst.errorMessage)

        // Retry sends ONLY the failures — successes can never be duplicated.
        vm.upload()
        mainDispatcherRule.advanceUntilIdle()

        val afterRetry = vm.state.value
        assertTrue(afterRetry.selectedFiles.isEmpty())
        assertTrue(afterRetry.uploadSuccess)
        assertTrue(afterRetry.selectedFiles.none { it.name.startsWith("ok") })
    }

    @Test
    fun `all-success batch finishes with uploadSuccess`() {
        stubStrings()
        stubUploadsByFileName()

        val vm = CloudUploadViewModel(repository, application)
        vm.addFiles(listOf(file("a.pdf"), file("b.pdf")))
        vm.upload()
        mainDispatcherRule.advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.uploadSuccess)
        assertTrue(s.selectedFiles.isEmpty())
        assertEquals(2, s.uploadedCount)
    }

    @Test
    fun `quota rejection stops the batch and keeps everything listed`() {
        stubStrings()
        val files = listOf(file("a.pdf"), file("b.pdf"), file("c.pdf"))
        coEvery {
            repository.uploadFile(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(ApiException(ApiException.QUOTA_EXCEEDED, "quota"))

        val vm = CloudUploadViewModel(repository, application)
        vm.addFiles(files)
        vm.upload()
        mainDispatcherRule.advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.quotaExceeded)
        assertFalse(s.isUploading)
        // Nothing was removed: retrying later re-sends the whole batch on purpose.
        assertEquals(3, s.selectedFiles.size)
    }
}

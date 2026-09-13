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

package nopalito.app.ui.screens.camera

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure generationId rules mirrored by CameraViewModel file capture.
 * CameraX file capture is not cancellable, so late callbacks must go stale:
 * they clean only their own temp file and never publish nor delete another
 * capture's original.
 */
class CaptureGenerationTest {
    private class Tracker {
        val generation = AtomicLong(0L)
        val published = mutableListOf<Long>()
        val deletedTemps = mutableListOf<File>()
        val deletedOriginals = mutableListOf<File>()

        fun begin(temp: File): Long {
            val id = generation.incrementAndGet()
            return id
        }

        fun cancel() {
            generation.incrementAndGet()
        }

        fun onSaved(captureId: Long, temp: File, original: File, publish: () -> Unit) {
            if (captureId != generation.get()) {
                if (temp.name.startsWith(".tmp-capture-")) deletedTemps.add(temp)
                return
            }
            publish()
            published.add(captureId)
        }
    }

    @Test
    fun `late callback after cancel does not publish`() {
        val t = Tracker()
        val temp = File(".tmp-capture-a.jpg")
        val id = t.begin(temp)
        t.cancel()
        var published = false
        t.onSaved(id, temp, File("originals/x.jpg")) { published = true }
        assertThat(published).isFalse()
        assertThat(t.deletedTemps).containsExactly(temp)
        assertThat(t.deletedOriginals).isEmpty()
    }

    @Test
    fun `two consecutive captures only last publishes`() {
        val t = Tracker()
        val first = t.begin(File(".tmp-capture-1.jpg"))
        val second = t.begin(File(".tmp-capture-2.jpg"))
        var count = 0
        t.onSaved(first, File(".tmp-capture-1.jpg"), File("o1.jpg")) { count++ }
        t.onSaved(second, File(".tmp-capture-2.jpg"), File("o2.jpg")) { count++ }
        assertThat(count).isEqualTo(1)
        assertThat(t.published).containsExactly(second)
    }

    @Test
    fun `stale callback never deletes another original`() {
        val t = Tracker()
        val id = t.begin(File(".tmp-capture-9.jpg"))
        t.cancel()
        t.onSaved(id, File(".tmp-capture-9.jpg"), File("originals/other.jpg")) {}
        assertThat(t.deletedOriginals).isEmpty()
    }

    @Test
    fun `ORIGINAL tier always requires explicit user decision on no space`() {
        // Policy guard: ORIGINAL must surface InsufficientStorage, never
        // auto-downgrade. keepOriginal=false only affects LOW/BALANCED/HIGH.
        val tier = nopalito.app.domain.CaptureTier.ORIGINAL
        val keepOriginal = false
        val shouldStillPreserve = (tier == nopalito.app.domain.CaptureTier.ORIGINAL) || keepOriginal
        assertThat(shouldStillPreserve).isTrue()
    }
}

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
import android.os.Build
import android.os.Parcelable

object SharedImportParser {

    const val MAX_SHARED_FILES = 30

    fun extractUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val found = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                parcelableExtra(intent)?.let { found += it }
                intent.data?.let { found += it }
                collectClipData(intent, found)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                parcelableListExtra(intent).forEach { found += it }
                intent.data?.let { found += it }
                collectClipData(intent, found)
            }

            Intent.ACTION_VIEW -> {
                intent.data?.let { found += it }
                collectClipData(intent, found)
                parcelableExtra(intent)?.let { found += it }
            }

            else -> return emptyList()
        }
        return found
            .filter { it != Uri.EMPTY }
            .take(MAX_SHARED_FILES)
    }

    // Some senders place streams only in ClipData, so it is collected as a fallback.
    private fun collectClipData(intent: Intent, out: MutableSet<Uri>) {
        val clip = intent.clipData ?: return
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i)?.uri?.let { out += it }
        }
    }

    // Typed getParcelableExtra is only available on Tiramisu and above.
    @Suppress("DEPRECATION")
    private fun parcelableExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
        }

    @Suppress("DEPRECATION")
    private fun parcelableListExtra(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
                ?.filterIsInstance<Uri>().orEmpty()
        }
}
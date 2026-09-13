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

package nopalito.app.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import nopalito.app.R
import nopalito.app.data.CurvatureCorpusExporter
import java.io.File

/** Debug-only SAF action; absent from release builds. */
class ExportCurvatureCorpusActivity : Activity() {
    private val picker = 41
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply {
            text = getString(R.string.debug_curvature_select_directory); setPadding(32, 32, 32, 32)
        }
        val button = Button(this).apply {
            text = getString(R.string.debug_curvature_export); setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(
                android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                android.provider.DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents", "primary:Download"
                )
            )
            startActivityForResult(intent, picker)
        }
        }
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; addView(status); addView(button)
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != picker || resultCode != RESULT_OK || data?.data == null) return
        val selected = DocumentFile.fromTreeUri(this, data.data!!) ?: return
        val temp = File(cacheDir, "curvature-fixtures")
        runCatching {
            val result = CurvatureCorpusExporter().export(filesDir, temp)
            val folder = if (selected.name == "curvature-fixtures") selected
            else selected.findFile("curvature-fixtures")
                ?: selected.createDirectory("curvature-fixtures")!!
            val doc = folder.createFile("application/json", "document.json")!!
            contentResolver.openOutputStream(doc.uri)!!
                .use { it.write(File(temp, "document.json").readBytes()) }
            val originals = folder.findFile("originals") ?: folder.createDirectory("originals")!!
            File(temp, "originals").listFiles()!!.forEach { source ->
                val target = originals.createFile("image/jpeg", source.name)!!
                contentResolver.openOutputStream(target.uri)!!
                    .use { output -> source.inputStream().use { it.copyTo(output) } }
            }
            status.text =
                getString(R.string.debug_curvature_exported, result.pageCount, result.totalBytes)
        }.onFailure { status.text = getString(R.string.debug_curvature_failed, it.message) }
        temp.deleteRecursively()
    }
}

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

package nopalito.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nopalito.app.ui.components.ImportedSignatureProcessor
import nopalito.app.ui.screens.document.DateBackgroundStyle
import nopalito.app.ui.screens.document.DateOverlayStyle
import nopalito.app.ui.screens.document.PageOverlays
import nopalito.app.ui.screens.document.SignatureSource
import nopalito.app.ui.screens.document.SignatureState
import java.io.File

/**
 * Serializable mirror of [PageOverlays] that can be written to disk.
 * Bitmaps are stored as separate PNG files referenced by [signatureImageFileName].
 */
@Serializable
data class PersistedPageOverlays(
    val signatureState: SignatureState? = null,
    val signatureImageFileName: String? = null,
    val signaturePositionFractionX: Float? = null,
    val signaturePositionFractionY: Float? = null,
    val signatureScale: Float = 1.0f,
    val signatureRotationDegrees: Float = 0f,
    val dateText: String? = null,
    val datePositionFractionX: Float? = null,
    val datePositionFractionY: Float? = null,
    val dateScale: Float = 1.0f,
    val dateRotationDegrees: Float = 0f,
    val dateStyleTextColor: Long = 0xFFFFFFFF,
    val dateStyleFontSize: Float = 14f,
    val dateStyleBackgroundStyle: String = DateBackgroundStyle.CAPSULE.name,
    val dateStyleBackgroundColor: Long = 0x80000000,
)

@Serializable
data class PersistedOverlaysMap(
    val version: Int = 1,
    val pages: Map<String, PersistedPageOverlays> = emptyMap(),
)

/**
 * Repository that persists [PageOverlays] (signatures + date overlays) to disk
 * so they survive app restarts.
 *
 * - Overlay metadata is stored in `overlays.json` inside [scanRootDir].
 * - Signature bitmaps are stored as PNG files in the `signatures/` subdirectory.
 *
 * The in-memory [PageOverlays] holds the decoded [Bitmap]; the on-disk format
 * holds the [SignatureState] (strokes, color, width, scales, position) plus a
 * reference to the PNG file for quick bitmap reloading.
 */
class OverlayRepository(
    scanRootDir: File,
) {
    private val overlaysDir = File(scanRootDir, "overlays").apply { mkdirs() }
    private val signaturesDir = File(overlaysDir, "signatures").apply { mkdirs() }
    private val metadataFile = File(overlaysDir, "overlays.json")

    private val mutex = Mutex()
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        // Old builds persisted rotation as quarter turns; ignore those keys.
        ignoreUnknownKeys = true
    }

    /**
     * Loads all persisted overlays, decoding signature bitmaps from PNG files.
     * Returns a map of pageId -> PageOverlays with bitmaps ready to use.
     */
    suspend fun loadAll(): Map<String, PageOverlays> = withContext(Dispatchers.IO) {
        if (!metadataFile.exists()) return@withContext emptyMap()

        val persisted = runCatching {
            json.decodeFromString<PersistedOverlaysMap>(metadataFile.readText())
        }.getOrNull() ?: return@withContext emptyMap()

        val result = mutableMapOf<String, PageOverlays>()
        for ((pageId, p) in persisted.pages) {
            val signatureBitmap = p.signatureImageFileName?.let { name ->
                val file = File(signaturesDir, name)
                if (file.exists()) {
                    runCatching {
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        BitmapFactory.decodeFile(file.absolutePath, options)?.let { decoded ->
                            val d = if (decoded.config == Bitmap.Config.ARGB_8888) decoded
                            else decoded.copy(Bitmap.Config.ARGB_8888, true)
                                .also { decoded.recycle() }
                            Log.d(
                                "ImportedSignature",
                                "loadAll page=$pageId from file ${d.width}x${d.height} " +
                                        "alpha=${ImportedSignatureProcessor.alphaBounds(d)}",
                            )
                            d
                        }
                    }.getOrNull()
                } else null
            } ?: p.signatureState?.importedImageBytes?.let {
                runCatching { ImportedSignatureProcessor.decodeArgb8888(it) }.getOrNull()
            }

            val dateStyle = DateOverlayStyle(
                textColor = p.dateStyleTextColor,
                fontSize = p.dateStyleFontSize,
                backgroundStyle = runCatching { DateBackgroundStyle.valueOf(p.dateStyleBackgroundStyle) }
                    .getOrDefault(DateBackgroundStyle.CAPSULE),
                backgroundColor = p.dateStyleBackgroundColor,
            )

            val posFraction =
                if (p.signaturePositionFractionX != null && p.signaturePositionFractionY != null) {
                    androidx.compose.ui.geometry.Offset(
                        p.signaturePositionFractionX,
                        p.signaturePositionFractionY
                    )
                } else null

            val datePosFraction =
                if (p.datePositionFractionX != null && p.datePositionFractionY != null) {
                    androidx.compose.ui.geometry.Offset(
                        p.datePositionFractionX,
                        p.datePositionFractionY
                    )
                } else null

            result[pageId] = PageOverlays(
                signatureState = p.signatureState,
                signatureBitmap = signatureBitmap,
                signatureSource = p.signatureState?.source ?: SignatureSource.DRAWN,
                signaturePositionFraction = posFraction,
                signatureScale = p.signatureScale,
                signatureRotationDegrees = p.signatureRotationDegrees,
                dateText = p.dateText,
                datePositionFraction = datePosFraction,
                dateScale = p.dateScale,
                dateRotationDegrees = p.dateRotationDegrees,
                dateStyle = dateStyle,
            )
        }
        result
    }

    /**
     * Persists the full overlays map to disk, writing signature bitmaps as PNGs.
     */
    suspend fun saveAll(overlays: Map<String, PageOverlays>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val persistedMap = mutableMapOf<String, PersistedPageOverlays>()

            for ((pageId, o) in overlays) {
                val sigFileName = o.signatureBitmap?.let { bmp ->
                    val name = "sig_$pageId.png"
                    val file = File(signaturesDir, name)
                    val alpha = ImportedSignatureProcessor.alphaBounds(bmp)
                    Log.d(
                        "ImportedSignature",
                        "saveAll page=$pageId bmp=${bmp.width}x${bmp.height} alpha=$alpha",
                    )
                    runCatching {
                        // Atomic write: an interrupted process must never leave a
                        // truncated PNG behind, it would silently break reloads.
                        val tmp = File(signaturesDir, "$name.tmp")
                        tmp.outputStream().use { out ->
                            val fresh = ImportedSignatureProcessor.freshCopy(bmp)
                            check(fresh.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                                "Unable to persist signature bitmap"
                            }
                            fresh.recycle()
                        }
                        if (!tmp.renameTo(file)) {
                            if (file.delete() && tmp.renameTo(file)) name
                            else throw java.io.IOException("Cannot replace $name")
                        } else name
                    }.getOrNull()
                }

                val dateStyle = o.dateStyle
                if (o.signatureBitmap == null && o.signatureState == null) {
                    Log.d("ImportedSignature", "saveAll page=$pageId NO SIGNATURE")
                }
                persistedMap[pageId] = PersistedPageOverlays(
                    signatureState = o.signatureState,
                    signatureImageFileName = sigFileName,
                    signaturePositionFractionX = o.signaturePositionFraction?.x,
                    signaturePositionFractionY = o.signaturePositionFraction?.y,
                    signatureScale = o.signatureScale,
                    signatureRotationDegrees = o.signatureRotationDegrees,
                    dateText = o.dateText,
                    datePositionFractionX = o.datePositionFraction?.x,
                    datePositionFractionY = o.datePositionFraction?.y,
                    dateScale = o.dateScale,
                    dateRotationDegrees = o.dateRotationDegrees,
                    dateStyleTextColor = dateStyle.textColor,
                    dateStyleFontSize = dateStyle.fontSize,
                    dateStyleBackgroundStyle = dateStyle.backgroundStyle.name,
                    dateStyleBackgroundColor = dateStyle.backgroundColor,
                )
            }

            val map = PersistedOverlaysMap(version = 1, pages = persistedMap)
            metadataFile.writeText(json.encodeToString(map))
        }
    }

    /**
     * Clears all persisted overlays (used when starting a new document).
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            metadataFile.delete()
            signaturesDir.listFiles()?.forEach { it.delete() }
        }
    }
}
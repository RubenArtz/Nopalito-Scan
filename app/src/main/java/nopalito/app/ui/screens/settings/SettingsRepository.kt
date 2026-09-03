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

package nopalito.app.ui.screens.settings

import android.content.Context
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nopalito.app.R
import nopalito.imageprocessing.ColorMode

class SettingsRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {
    private val DEFAULT_COLOR_MODE = stringPreferencesKey("default_color_mode")
    private val EXPORT_DIR_URI = stringPreferencesKey("export_dir_uri")
    private val DOWNLOAD_DIR_URI = stringPreferencesKey("download_dir_uri")
    private val AUTO_DETECT = booleanPreferencesKey("auto_detect")
    private val CAPTURE_MODE = stringPreferencesKey("capture_mode")
    private val QR_SCAN_MODE = booleanPreferencesKey("qr_scan_mode")

    val defaultColorMode: Flow<DefaultColorMode> =
        dataStore.data.map { prefs ->
            when (prefs[DEFAULT_COLOR_MODE]) {
                "AUTO" -> DefaultColorMode.AUTO
                "COLOR" -> DefaultColorMode.COLOR
                "GRAYSCALE" -> DefaultColorMode.GRAYSCALE
                else -> DefaultColorMode.AUTO
            }
        }

    val exportDirUri: Flow<String?> =
        dataStore.data.map { prefs ->
            prefs[EXPORT_DIR_URI]
        }

    /** User-selected folder for downloads (cloud, export history, QR). */
    val downloadDirUri: Flow<String?> =
        dataStore.data.map { prefs ->
            prefs[DOWNLOAD_DIR_URI]
        }

    suspend fun setDownloadDirUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(DOWNLOAD_DIR_URI)
            } else {
                prefs[DOWNLOAD_DIR_URI] = uri
            }
        }
    }

    fun resolveExportDirName(uri: String): String? {
        return DocumentFile.fromTreeUri(context, uri.toUri())?.name
    }

    val autoDetect: Flow<Boolean> =
        dataStore.data.map { prefs ->
            prefs[AUTO_DETECT] ?: false
        }

    val captureMode: Flow<CaptureMode> =
        dataStore.data.map { prefs ->
            when (prefs[CAPTURE_MODE]) {
                "INDIVIDUAL" -> CaptureMode.INDIVIDUAL
                "BATCH", null -> CaptureMode.BATCH
                else -> CaptureMode.BATCH
            }
        }

    suspend fun setDefaultColorMode(mode: DefaultColorMode) {
        dataStore.edit { prefs ->
            prefs[DEFAULT_COLOR_MODE] = mode.name
        }
    }

    suspend fun setExportDirUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(EXPORT_DIR_URI)
            } else {
                prefs[EXPORT_DIR_URI] = uri
            }
        }
    }

    suspend fun setAutoDetect(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[AUTO_DETECT] = enabled
        }
    }

    suspend fun setCaptureMode(mode: CaptureMode) {
        dataStore.edit { prefs ->
            prefs[CAPTURE_MODE] = mode.name
        }
    }

    val qrScanModeEnabled: Flow<Boolean> =
        dataStore.data.map { prefs ->
            prefs[QR_SCAN_MODE] ?: false
        }

    suspend fun setQrScanModeEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[QR_SCAN_MODE] = enabled
        }
    }
}

enum class DefaultColorMode(val colorMode: ColorMode?, val labelResource: Int) {
    AUTO(null, R.string.color_mode_auto),
    COLOR(ColorMode.COLOR, R.string.color_mode_color),
    GRAYSCALE(ColorMode.GRAYSCALE, R.string.color_mode_grayscale),
}

enum class CaptureMode {
    INDIVIDUAL,
    BATCH,
}
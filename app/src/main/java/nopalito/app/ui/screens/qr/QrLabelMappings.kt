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

package nopalito.app.ui.screens.qr

import androidx.annotation.StringRes
import nopalito.app.R

/**
 * Maps the persistent WiFi-security tokens to their localized label resources.
 *
 * The tokens ("WPA", "WEP", "Abierta") are part of the storage/serialization
 * contract ([QrDetected.encodeQrType] / [QrDetected.decodeQrType] and the
 * generator's `encryption` field), so they never change; only the *display*
 * goes through this mapping. Unknown tokens (legacy data, third-party scans)
 * fall back to the "open network" label.
 */
@StringRes
fun wifiSecurityLabel(token: String?): Int = when (token) {
    "WPA" -> R.string.qr_wifi_security_wpa
    "WEP" -> R.string.qr_wifi_security_wep
    else -> R.string.qr_wifi_security_open
}

/**
 * Maps the persistent QR module-shape tokens to their localized label
 * resources. The tokens ("square", "rounded", "circle", "diamond") are the
 * backend `design.moduleShape` contract and never change; only the *display*
 * goes through this mapping.
 */
@StringRes
fun moduleShapeLabel(token: String): Int = when (token) {
    "square" -> R.string.qr_shape_square
    "rounded" -> R.string.qr_shape_rounded
    "circle" -> R.string.qr_shape_circle
    "diamond" -> R.string.qr_shape_diamond
    else -> R.string.qr_shape_square
}
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import nopalito.app.R

/** Single source of truth for the icon shown next to every QR/barcode type. */
fun qrTypeIcon(type: QrDetected.Type): ImageVector = when (type) {
    is QrDetected.Type.Wifi -> Icons.Default.Wifi
    is QrDetected.Type.Url -> Icons.Default.Link
    is QrDetected.Type.Email -> Icons.Default.Email
    is QrDetected.Type.Phone -> Icons.Default.Phone
    is QrDetected.Type.Sms -> Icons.AutoMirrored.Filled.Message
    is QrDetected.Type.Geo -> Icons.Default.LocationOn
    is QrDetected.Type.Contact -> Icons.Default.Person
    is QrDetected.Type.Event -> Icons.Default.Event
    is QrDetected.Type.Text -> Icons.Default.QrCode
}

/** Localized human-readable title for a QR/barcode type. */
@StringRes
fun qrTypeLabel(type: QrDetected.Type): Int = when (type) {
    is QrDetected.Type.Wifi -> R.string.qr_type_wifi
    is QrDetected.Type.Url -> R.string.qr_type_url
    is QrDetected.Type.Email -> R.string.qr_type_email
    is QrDetected.Type.Phone -> R.string.qr_type_phone
    is QrDetected.Type.Sms -> R.string.qr_type_sms
    is QrDetected.Type.Geo -> R.string.qr_type_geo
    is QrDetected.Type.Contact -> R.string.qr_type_contact
    is QrDetected.Type.Event -> R.string.qr_type_event
    is QrDetected.Type.Text -> R.string.qr_type_text
}

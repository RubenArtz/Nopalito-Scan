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

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.common.Barcode

data class QrDetected(
    val content: String,
    val format: String?,
    val bitmap: Bitmap,
    val type: Type = Type.Text,
) {
    sealed interface Type {
        data object Text : Type
        data class Url(val url: String) : Type
        data class Wifi(val ssid: String?, val password: String?, val security: String) : Type
        data class Email(val address: String, val subject: String?, val body: String?) : Type
        data class Phone(val number: String) : Type
        data class Sms(val number: String, val message: String?) : Type
        data class Geo(val lat: Double, val lng: Double) : Type
    }
}

fun formatName(format: Int): String? = when (format) {
    Barcode.FORMAT_QR_CODE -> "QR Code"
    Barcode.FORMAT_AZTEC -> "Aztec"
    Barcode.FORMAT_CODABAR -> "Codabar"
    Barcode.FORMAT_CODE_39 -> "Code 39"
    Barcode.FORMAT_CODE_93 -> "Code 93"
    Barcode.FORMAT_CODE_128 -> "Code 128"
    Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
    Barcode.FORMAT_EAN_8 -> "EAN-8"
    Barcode.FORMAT_EAN_13 -> "EAN-13"
    Barcode.FORMAT_ITF -> "ITF"
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_UPC_A -> "UPC-A"
    Barcode.FORMAT_UPC_E -> "UPC-E"
    else -> null
}

/** Compact tab-separated serialization of the parsed type, persisted with each scan. */
fun encodeQrType(type: QrDetected.Type): String? = when (type) {
    is QrDetected.Type.Text -> null
    is QrDetected.Type.Url -> "U\t${type.url}"
    is QrDetected.Type.Wifi -> "W\t${type.ssid.orEmpty()}\t${type.password.orEmpty()}\t${type.security}"
    is QrDetected.Type.Email -> "E\t${type.address}\t${type.subject.orEmpty()}\t${type.body.orEmpty()}"
    is QrDetected.Type.Phone -> "P\t${type.number}"
    is QrDetected.Type.Sms -> "S\t${type.number}\t${type.message.orEmpty()}"
    is QrDetected.Type.Geo -> "G\t${type.lat}\t${type.lng}"
}

fun decodeQrType(data: String?): QrDetected.Type {
    val parts = data?.split("\t").orEmpty()
    return when (parts.firstOrNull()) {
        "U" -> QrDetected.Type.Url(parts.getOrNull(1) ?: "")
        "W" -> QrDetected.Type.Wifi(
            ssid = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
            password = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
            security = parts.getOrNull(3) ?: "Abierta",
        )

        "E" -> QrDetected.Type.Email(
            address = parts.getOrNull(1) ?: "",
            subject = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
            body = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
        )

        "P" -> QrDetected.Type.Phone(parts.getOrNull(1) ?: "")
        "S" -> QrDetected.Type.Sms(
            number = parts.getOrNull(1) ?: "",
            message = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
        )

        "G" -> QrDetected.Type.Geo(
            lat = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
            lng = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
        )

        else -> QrDetected.Type.Text
    }
}
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
        data class Contact(
            val name: String?,
            val phones: List<String>,
            val emails: List<String>,
            val organization: String?,
            val title: String?,
        ) : Type

        data class Event(
            val summary: String?,
            val start: String?,
            val end: String?,
            val location: String?,
            val description: String?,
        ) : Type
    }
}

/**
 * Lightweight view model for the compact popup and the expanded sheet.
 * Unlike [QrDetected] it carries no bitmap, so it can be built for every
 * active track without copying camera frames.
 */
data class QrSheetItem(
    val content: String,
    val format: String?,
    val type: QrDetected.Type,
)

fun QrDetected.toSheetItem(): QrSheetItem = QrSheetItem(content, format, type)

fun QrTrack.toSheetItem(): QrSheetItem = QrSheetItem(content, format, type)

/**
 * A QR found in an imported photo, kept together with the photo it came
 * from so the user can still import that photo as a document page
 * (a sheet with a QR on it is both things at once).
 */
data class StillQrResult(
    val item: QrSheetItem,
    val sourceUri: android.net.Uri,
)

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

/** Separator for repeated fields (phones, emails) inside a tab-separated record. */
private const val LIST_SEPARATOR = '\u001F'

private fun String.asRecordField(): String = replace('\t', ' ').replace('\n', ' ')

private fun List<String>.asRecordList(): String =
    joinToString(LIST_SEPARATOR.toString()) { it.asRecordField() }

private fun String.recordList(): List<String> =
    split(LIST_SEPARATOR).filter { it.isNotBlank() }

/** Compact tab-separated serialization of the parsed type, persisted with each scan. */
fun encodeQrType(type: QrDetected.Type): String? = when (type) {
    is QrDetected.Type.Text -> null
    is QrDetected.Type.Url -> "U\t${type.url.asRecordField()}"
    is QrDetected.Type.Wifi ->
        "W\t${type.ssid.orEmpty().asRecordField()}\t${
            type.password.orEmpty().asRecordField()
        }\t${type.security}"

    is QrDetected.Type.Email ->
        "E\t${type.address.asRecordField()}\t${
            type.subject.orEmpty().asRecordField()
        }\t${type.body.orEmpty().asRecordField()}"

    is QrDetected.Type.Phone -> "P\t${type.number.asRecordField()}"
    is QrDetected.Type.Sms -> "S\t${type.number.asRecordField()}\t${
        type.message.orEmpty().asRecordField()
    }"

    is QrDetected.Type.Geo -> "G\t${type.lat}\t${type.lng}"
    is QrDetected.Type.Contact -> "C\t" +
            "${type.name.orEmpty().asRecordField()}\t" +
            "${type.phones.asRecordList()}\t" +
            "${type.emails.asRecordList()}\t" +
            "${type.organization.orEmpty().asRecordField()}\t" +
            "${type.title.orEmpty().asRecordField()}"

    is QrDetected.Type.Event -> "V\t" +
            "${type.summary.orEmpty().asRecordField()}\t" +
            "${type.start.orEmpty().asRecordField()}\t" +
            "${type.end.orEmpty().asRecordField()}\t" +
            "${type.location.orEmpty().asRecordField()}\t" +
            "${type.description.orEmpty().asRecordField()}"
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

        "C" -> QrDetected.Type.Contact(
            name = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
            phones = parts.getOrNull(2).orEmpty().recordList(),
            emails = parts.getOrNull(3).orEmpty().recordList(),
            organization = parts.getOrNull(4)?.takeIf { it.isNotEmpty() },
            title = parts.getOrNull(5)?.takeIf { it.isNotEmpty() },
        )

        "V" -> QrDetected.Type.Event(
            summary = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
            start = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
            end = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
            location = parts.getOrNull(4)?.takeIf { it.isNotEmpty() },
            description = parts.getOrNull(5)?.takeIf { it.isNotEmpty() },
        )

        else -> QrDetected.Type.Text
    }
}
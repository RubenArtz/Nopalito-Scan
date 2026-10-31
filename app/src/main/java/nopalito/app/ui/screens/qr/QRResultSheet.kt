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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import nopalito.app.R
import nopalito.app.ui.components.rememberHapticManager
import java.net.URI
import java.util.Locale

/** Intent/action callbacks for the expanded QR sheet. */
data class QrSheetActions(
    val onCopy: () -> Unit,
    val onShare: () -> Unit,
    val onOpenLink: () -> Unit = {},
    val onConnectWifi: (QrDetected.Type.Wifi) -> Unit = {},
    val onComposeEmail: (QrDetected.Type.Email) -> Unit = {},
    val onCall: (String) -> Unit = {},
    val onSendSms: (QrDetected.Type.Sms) -> Unit = {},
    val onOpenMap: (QrDetected.Type.Geo) -> Unit = {},
    val onAddCalendar: (QrDetected.Type.Event) -> Unit = {},
)

/**
 * Shared intent wiring for every QR sheet (live camera, imported photos,
 * history). Each callback captures [item], with haptic confirmation.
 */
@Composable
fun rememberQrSheetActions(
    item: QrSheetItem,
    onConnectWifi: (QrDetected.Type.Wifi) -> Unit = {},
): QrSheetActions {
    val context = LocalContext.current
    val haptics = rememberHapticManager()
    return QrSheetActions(
        onCopy = {
            haptics.click()
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("QR", item.content))
            Toast.makeText(context, R.string.qr_copied, Toast.LENGTH_SHORT).show()
        },
        onShare = {
            haptics.click()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, item.content)
            }
            runCatching { context.startActivity(Intent.createChooser(send, null)) }
        },
        onOpenLink = {
            haptics.click()
            val url = (item.type as? QrDetected.Type.Url)?.url ?: item.content
            val withScheme = if (url.contains("://")) url else "https://$url"
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, withScheme.toUri()))
            }
        },
        onConnectWifi = onConnectWifi,
        onComposeEmail = { email ->
            haptics.click()
            val uri = "mailto:${email.address}".toUri().buildUpon()
                .apply {
                    email.subject?.let { appendQueryParameter("subject", it) }
                    email.body?.let { appendQueryParameter("body", it) }
                }.build()
            runCatching {
                context.startActivity(Intent(Intent.ACTION_SENDTO, uri))
            }
        },
        onCall = { number ->
            haptics.click()
            runCatching {
                context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$number".toUri()))
            }
        },
        onSendSms = { sms ->
            haptics.click()
            val uri = "smsto:${sms.number}".toUri().buildUpon()
                .apply {
                    sms.message?.let { appendQueryParameter("sms_body", it) }
                }.build()
            runCatching {
                context.startActivity(Intent(Intent.ACTION_SENDTO, uri))
            }
        },
        onOpenMap = { geo ->
            haptics.click()
            runCatching {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        "geo:${geo.lat},${geo.lng}?q=${geo.lat},${geo.lng}".toUri()
                    )
                )
            }
        },
        onAddCalendar = { event ->
            haptics.click()
            val intent = Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .apply {
                    event.summary?.let { putExtra(CalendarContract.Events.TITLE, it) }
                    event.location?.let {
                        putExtra(CalendarContract.Events.EVENT_LOCATION, it)
                    }
                    event.description?.let {
                        putExtra(CalendarContract.Events.DESCRIPTION, it)
                    }
                }
            runCatching { context.startActivity(intent) }
        },
    )
}

/**
 * One popup per live code, stacked above the camera controls. The last live
 * list is kept through the tracker's grace period, so a short detection miss
 * fades instead of flickering. Slots are stable: each popup is keyed by track
 * id and the order only changes when codes appear/disappear, never on every
 * frame, so texts never swap places.
 *
 * Popup models recompute only when membership or primary changes — the fast
 * tracker ticks (new corner objects every tick) must not recompose N cards.
 */
@Composable
fun QrPopupHost(
    state: QrDetectionState,
    onExpand: (QrSheetItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fresh = state.freshTracks
    var lastFresh by remember { mutableStateOf<List<QrTrack>>(emptyList()) }
    // Order-insensitive key: same set in any order must not restart the effect.
    val idsKey = fresh.map { it.id }.sorted().joinToString(",")
    LaunchedEffect(idsKey) {
        if (fresh.isNotEmpty()) lastFresh = fresh
    }
    val display = if (fresh.isNotEmpty()) fresh else lastFresh
    val primaryId = display.firstOrNull { it.isPrimary }?.id
    // Stable value models: identical content while only corners/stability churn.
    val models = remember(idsKey, primaryId) {
        display.map { PopupModel(it.id, it.toSheetItem(), it.isPrimary) }
    }
    val latestExpand by rememberUpdatedState(onExpand)

    AnimatedVisibility(
        visible = fresh.isNotEmpty(),
        enter = fadeIn() + slideInVertically { height -> height / 3 },
        exit = fadeOut() + slideOutVertically { height -> height / 3 },
        modifier = modifier,
    ) {
        // LazyColumn virtualizes: with 20 codes only the visible popups
        // compose, instead of measuring them all on every tick.
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 216.dp),
        ) {
            items(models, key = { it.id }) { model ->
                val expand = remember(model.item.content) { { latestExpand(model.item) } }
                QrCompactPopup(
                    item = model.item,
                    isPrimary = model.isPrimary,
                    onClick = expand,
                )
            }
        }
    }
}

private data class PopupModel(
    val id: Long,
    val item: QrSheetItem,
    val isPrimary: Boolean,
)

@Composable
private fun QrCompactPopup(
    item: QrSheetItem,
    isPrimary: Boolean,
    onClick: () -> Unit,
) {
    val typeLabel = stringResource(qrTypeLabel(item.type))
    val summary = qrItemSummary(item)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp)
            .heightIn(min = 60.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (isPrimary) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (isPrimary) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = qrTypeIcon(item.type),
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.qr_sheet_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Expanded bottom sheet with the full content, parsed fields and the actions
 * relevant to the detected type. Other codes visible at the same time can be
 * selected with the chips at the top. [onImportAsPage] is only set for codes
 * coming from an imported photo: a sheet with a QR on it can still be
 * imported as a document page. [onSaveImage] is only set by the history,
 * where the stored thumbnail can be re-downloaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrResultSheet(
    item: QrSheetItem,
    others: List<QrSheetItem>,
    onSelectOther: (QrSheetItem) -> Unit,
    actions: QrSheetActions,
    onDismiss: () -> Unit,
    onImportAsPage: ((QrSheetItem) -> Unit)? = null,
    onSaveImage: (() -> Unit)? = null,
    downloadFormats: List<String> = emptyList(),
    downloadingFormat: String? = null,
    onDownloadFormat: ((String) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = qrTypeIcon(item.type),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(qrTypeLabel(item.type)),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    item.format?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (others.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.qr_other_codes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        others.forEach { other ->
                            FilterChip(
                                selected = other.content == item.content,
                                onClick = { onSelectOther(other) },
                                label = {
                                    Text(
                                        text = qrItemSummary(other),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = qrTypeIcon(other.type),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                modifier = Modifier.widthIn(max = 190.dp),
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (val type = item.type) {
                            is QrDetected.Type.Url -> QrContentBlock(type.url)
                            is QrDetected.Type.Text -> QrContentBlock(item.content)

                            is QrDetected.Type.Wifi -> {
                                type.ssid?.let {
                                    QrField(stringResource(R.string.qr_wifi_ssid), it)
                                }
                                type.password?.let {
                                    QrField(stringResource(R.string.qr_wifi_password), it)
                                }
                                QrField(
                                    stringResource(R.string.qr_wifi_security),
                                    stringResource(wifiSecurityLabel(type.security)),
                                )
                            }

                            is QrDetected.Type.Email -> {
                                QrField(stringResource(R.string.qr_email_address), type.address)
                                type.subject?.let {
                                    QrField(stringResource(R.string.qr_email_subject), it)
                                }
                                type.body?.let {
                                    QrField(stringResource(R.string.qr_email_body), it)
                                }
                            }

                            is QrDetected.Type.Phone ->
                                QrField(stringResource(R.string.qr_phone_number), type.number)

                            is QrDetected.Type.Sms -> {
                                QrField(stringResource(R.string.qr_sms_number), type.number)
                                type.message?.let {
                                    QrField(stringResource(R.string.qr_sms_message), it)
                                }
                            }

                            is QrDetected.Type.Geo -> {
                                QrField(
                                    stringResource(R.string.qr_geo_lat),
                                    String.format(Locale.US, "%.6f", type.lat),
                                )
                                QrField(
                                    stringResource(R.string.qr_geo_lng),
                                    String.format(Locale.US, "%.6f", type.lng),
                                )
                            }

                            is QrDetected.Type.Contact -> {
                                type.name?.let {
                                    QrField(stringResource(R.string.qr_contact_name), it)
                                }
                                if (type.phones.isNotEmpty()) {
                                    QrField(
                                        stringResource(R.string.qr_contact_phones),
                                        type.phones.joinToString("\n"),
                                    )
                                }
                                if (type.emails.isNotEmpty()) {
                                    QrField(
                                        stringResource(R.string.qr_contact_emails),
                                        type.emails.joinToString("\n"),
                                    )
                                }
                                type.organization?.let {
                                    QrField(stringResource(R.string.qr_contact_organization), it)
                                }
                                type.title?.let {
                                    QrField(stringResource(R.string.qr_contact_title), it)
                                }
                            }

                            is QrDetected.Type.Event -> {
                                type.summary?.let {
                                    QrField(stringResource(R.string.qr_event_summary), it)
                                }
                                type.start?.let {
                                    QrField(stringResource(R.string.qr_event_start), it)
                                }
                                type.end?.let {
                                    QrField(stringResource(R.string.qr_event_end), it)
                                }
                                type.location?.let {
                                    QrField(stringResource(R.string.qr_event_location), it)
                                }
                                type.description?.let {
                                    QrField(stringResource(R.string.qr_event_description), it)
                                }
                            }
                        }
                    }
                }
            }

            primaryAction(item, actions)?.let { action ->
                Button(
                    onClick = action.onClick,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(action.label))
                }
            }

            onImportAsPage?.let { onImport ->
                Button(
                    onClick = { onImport(item) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.qr_import_as_page),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            onSaveImage?.let { onSave ->
                OutlinedButton(
                    onClick = onSave,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.qr_save))
                }
            }

            if (onDownloadFormat != null && downloadFormats.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.qr_redownload_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        downloadFormats.forEach { fmt ->
                            Button(
                                onClick = { onDownloadFormat(fmt) },
                                enabled = downloadingFormat == null || downloadingFormat == fmt,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (downloadingFormat == fmt) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(fmt.uppercase())
                                }
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = actions.onCopy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.qr_copy))
                }
                OutlinedButton(
                    onClick = actions.onShare,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.qr_share))
                }
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.qr_close))
            }
        }
    }
}

@Composable
private fun QrField(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QrContentBlock(value: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

private data class QrPrimaryAction(
    @StringRes val label: Int,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

private fun primaryAction(
    item: QrSheetItem,
    actions: QrSheetActions,
): QrPrimaryAction? = when (val type = item.type) {
    is QrDetected.Type.Url -> QrPrimaryAction(
        R.string.qr_open_link,
        Icons.AutoMirrored.Filled.OpenInNew,
        actions.onOpenLink,
    )

    is QrDetected.Type.Wifi -> QrPrimaryAction(
        R.string.qr_connect,
        Icons.Default.Wifi,
        { actions.onConnectWifi(type) },
    )

    is QrDetected.Type.Email -> QrPrimaryAction(
        R.string.qr_compose_email,
        Icons.Default.Email,
        { actions.onComposeEmail(type) },
    )

    is QrDetected.Type.Phone -> QrPrimaryAction(
        R.string.qr_call,
        Icons.Default.Phone,
        { actions.onCall(type.number) },
    )

    is QrDetected.Type.Sms -> QrPrimaryAction(
        R.string.qr_send_sms,
        Icons.AutoMirrored.Filled.Message,
        { actions.onSendSms(type) },
    )

    is QrDetected.Type.Geo -> QrPrimaryAction(
        R.string.qr_open_map,
        Icons.Default.LocationOn,
        { actions.onOpenMap(type) },
    )

    is QrDetected.Type.Contact -> when {
        type.phones.isNotEmpty() -> QrPrimaryAction(
            R.string.qr_call,
            Icons.Default.Phone,
            { actions.onCall(type.phones.first()) },
        )

        type.emails.isNotEmpty() -> QrPrimaryAction(
            R.string.qr_compose_email,
            Icons.Default.Email,
            {
                actions.onComposeEmail(
                    QrDetected.Type.Email(type.emails.first(), subject = null, body = null)
                )
            },
        )

        else -> null
    }

    is QrDetected.Type.Event -> QrPrimaryAction(
        R.string.qr_add_calendar,
        Icons.Default.Event,
        { actions.onAddCalendar(type) },
    )

    is QrDetected.Type.Text -> null
}

/** Short, human-friendly line shown in the compact popup and code chips. */
fun qrItemSummary(item: QrSheetItem): String = when (val type = item.type) {
    is QrDetected.Type.Url -> qrUrlDomain(type.url) ?: type.url
    is QrDetected.Type.Wifi -> type.ssid ?: item.content
    is QrDetected.Type.Email -> type.address
    is QrDetected.Type.Phone -> type.number
    is QrDetected.Type.Sms -> type.number
    is QrDetected.Type.Geo -> String.format(Locale.US, "%.5f, %.5f", type.lat, type.lng)
    is QrDetected.Type.Contact ->
        type.name ?: type.phones.firstOrNull() ?: type.emails.firstOrNull() ?: item.content

    is QrDetected.Type.Event -> type.summary ?: type.start ?: item.content
    is QrDetected.Type.Text -> item.content
}

/** Extracts just the host (e.g. "openai.com") for the compact popup. */
fun qrUrlDomain(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    return runCatching { URI(withScheme).host }
        .getOrNull()
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }
}

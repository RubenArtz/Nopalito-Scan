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

package nopalito.app.ui.screens.tools.qrgenerator

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nopalito.app.NopalitoApp
import nopalito.app.R
import nopalito.app.ui.Navigation
import nopalito.app.ui.components.GradientHeroHeader
import nopalito.app.ui.components.TopActionButtons
import nopalito.app.ui.screens.qr.mimeFor
import nopalito.app.ui.screens.qr.moduleShapeLabel
import nopalito.app.ui.screens.qr.saveQrToDownloads
import nopalito.app.ui.screens.qr.shareQr
import nopalito.app.ui.screens.qr.wifiSecurityLabel

private val FOREGROUND_PRESETS =
    listOf("#000000", "#0F172A", "#1D4ED8", "#047857", "#B91C1C", "#6D28D9", "#DB2777", "#C2410C")
private val BACKGROUND_PRESETS =
    listOf("#FFFFFF", "#F1F5F9", "#E2E8F0", "#FEF3C7", "#FCE7F3", "#000000", "#0F172A", "#1E293B")

@Composable
fun QrGeneratorScreen(navigation: Navigation) {
    val context = LocalContext.current
    val appContainer = (context.applicationContext as NopalitoApp).appContainer
    val vm: QrGeneratorViewModel = viewModel(factory = appContainer.qrGeneratorViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    var showSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cloudError = stringResource(R.string.qr_saved_cloud_error)

    LaunchedEffect(state.cloudSync) {
        when (state.cloudSync) {
            is CloudSyncResult.FAILED -> {
                Toast.makeText(context, cloudError, Toast.LENGTH_LONG).show()
            }

            CloudSyncResult.IDLE -> Unit
            CloudSyncResult.NOT_AUTHENTICATED -> showSaved = true
            CloudSyncResult.PUSHED -> showSaved = true
        }
        vm.consumeCloudSync()
    }

    // Android 8/9 (API < 29): save via Storage Access Framework so no legacy
    // WRITE_EXTERNAL_STORAGE runtime permission is needed.
    var pendingSaveBytes by remember { mutableStateOf<ByteArray?>(null) }
    val saverLauncher = rememberLauncherForActivityResult(
        CreateDocument("todo/todo")
    ) { uri ->
        val bytes = pendingSaveBytes
        pendingSaveBytes = null
        if (uri != null && bytes != null) {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }.isSuccess
                withContext(Dispatchers.Main) {
                    if (ok) showSaved = true
                    else Toast.makeText(context, R.string.qr_save_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    BackHandler { navigation.back() }

    Column(modifier = Modifier.fillMaxSize()) {
        GradientHeroHeader(
            title = stringResource(R.string.qr_generate),
            subtitle = stringResource(R.string.qr_generate_desc),
            onBack = { navigation.back() },
            actions = {
                TopActionButtons(
                    navigation = navigation,
                    tint = Color.White,
                    circleColor = Color.White.copy(alpha = 0.22f)
                )
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            InternetInfoCard()

            // ── Content type ──
            SectionTitle(stringResource(R.string.qr_content))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QrContentType.entries.forEach { type ->
                    FilterChip(
                        selected = state.contentType == type,
                        onClick = { vm.setContentType(type) },
                        label = { Text(stringResource(type.labelRes())) },
                    )
                }
            }

            // ── Content fields ──
            when (state.contentType) {
                QrContentType.URL -> QrTextField(
                    stringResource(R.string.qr_type_url),
                    state.data,
                    vm::setData,
                    KeyboardType.Uri
                )

                QrContentType.TEXT -> QrTextField(
                    stringResource(R.string.qr_content),
                    state.data,
                    vm::setData
                )

                QrContentType.WIFI -> {
                    QrTextField(
                        stringResource(R.string.qr_wifi_ssid),
                        state.wifiSsid,
                        vm::setWifiSsid
                    )
                    QrTextField(
                        stringResource(R.string.qr_wifi_password),
                        state.wifiPassword,
                        vm::setWifiPassword
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("WPA", "WEP", "Abierta").forEach { sec ->
                            FilterChip(
                                selected = state.wifiSecurity == sec,
                                onClick = { vm.setWifiSecurity(sec) },
                                label = { Text(stringResource(wifiSecurityLabel(sec))) },
                            )
                        }
                    }
                }

                QrContentType.EMAIL -> {
                    QrTextField(
                        stringResource(R.string.qr_email_address),
                        state.emailAddress,
                        vm::setEmailAddress,
                        KeyboardType.Email
                    )
                    QrTextField(
                        stringResource(R.string.qr_email_subject),
                        state.emailSubject,
                        vm::setEmailSubject
                    )
                    QrTextField(
                        stringResource(R.string.qr_email_body),
                        state.emailBody,
                        vm::setEmailBody
                    )
                }

                QrContentType.SMS -> {
                    QrTextField(
                        stringResource(R.string.qr_sms_number),
                        state.smsNumber,
                        vm::setSmsNumber,
                        KeyboardType.Phone
                    )
                    QrTextField(
                        stringResource(R.string.qr_sms_message),
                        state.smsMessage,
                        vm::setSmsMessage
                    )
                }

                QrContentType.PHONE -> QrTextField(
                    stringResource(R.string.qr_phone_number),
                    state.phoneNumber,
                    vm::setPhoneNumber,
                    KeyboardType.Phone
                )
            }

            // ── Predefined styles ──
            if (state.styles.isNotEmpty()) {
                SectionTitle(stringResource(R.string.qr_styles))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.selectedStyleId == null,
                        onClick = { vm.selectStyle(null) },
                        label = { Text(stringResource(R.string.qr_style_none)) },
                    )
                    state.styles.forEach { style ->
                        FilterChip(
                            selected = state.selectedStyleId == style.id,
                            onClick = { vm.selectStyle(style.id) },
                            label = { Text(style.name) },
                        )
                    }
                }
            }

            // ── Design card ──
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(14.dp),
                ) {
                    SectionTitle(stringResource(R.string.qr_design))

                    ColorRow(
                        stringResource(R.string.qr_foreground),
                        state.foregroundColor,
                        FOREGROUND_PRESETS,
                        vm::setForegroundColor
                    )
                    ColorRow(
                        stringResource(R.string.qr_background),
                        state.backgroundColor,
                        BACKGROUND_PRESETS,
                        vm::setBackgroundColor
                    )

                    ChipRow(
                        stringResource(R.string.qr_module_shape),
                        state.moduleShape,
                        listOf("square", "rounded", "circle", "diamond"),
                        vm::setModuleShape,
                        optionLabel = { stringResource(moduleShapeLabel(it)) },
                    )
                    ChipRow(
                        stringResource(R.string.qr_error_correction),
                        state.errorCorrection,
                        listOf("L", "M", "Q", "H"),
                        vm::setErrorCorrection
                    )
                    ChipRow(
                        stringResource(R.string.qr_format),
                        state.format,
                        listOf("png", "svg", "pdf"),
                        vm::setFormat
                    )

                    Column {
                        Text(
                            text = stringResource(R.string.qr_size, state.size),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = state.size.toFloat(),
                            onValueChange = { vm.setSize(it.toInt()) },
                            valueRange = 128f..1024f,
                            steps = 14,
                        )
                    }

                    QrTextField(
                        stringResource(R.string.qr_frame_text),
                        state.frameText,
                        vm::setFrameText
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.qr_scan_check),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                stringResource(R.string.qr_scan_check_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.scanCheck,
                            onCheckedChange = { vm.toggleScanCheck() })
                    }
                }
            }

            // ── Generate ──
            Button(
                onClick = vm::generate,
                enabled = !state.isGenerating,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (state.isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.qr_generating))
                } else {
                    Icon(Icons.Default.QrCode2, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.qr_generate), fontWeight = FontWeight.SemiBold)
                }
            }

            state.error?.let { error ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            // ── Result ──
            state.result?.let { result ->
                val bytes = state.resultBytes
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(14.dp),
                    ) {
                        state.resultBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(R.string.qr_generate),
                                modifier = Modifier
                                    .size(220.dp)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(12.dp)
                                    ),
                            )
                        }
                        result.warnings?.takeIf { it.isNotEmpty() }?.forEach { warning ->
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = {
                                    val bytes = state.resultBytes
                                    if (bytes != null) {
                                        vm.saveCurrent()
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            scope.launch(Dispatchers.IO) {
                                                val uri =
                                                    saveQrToDownloads(context, bytes, result.format)
                                                withContext(Dispatchers.Main) {
                                                    if (uri != null) showSaved = true
                                                    else Toast.makeText(
                                                        context,
                                                        R.string.qr_save_error,
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        } else {
                                            pendingSaveBytes = bytes
                                            saverLauncher.launch(mimeFor(result.format))
                                        }
                                    }
                                },
                                enabled = bytes != null,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(
                                    Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.qr_save),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    val bytes = state.resultBytes
                                    if (bytes != null) {
                                        scope.launch(Dispatchers.IO) {
                                            val ok = shareQr(context, bytes, result.format)
                                            withContext(Dispatchers.Main) {
                                                if (!ok) Toast.makeText(
                                                    context,
                                                    R.string.qr_share_error,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                enabled = bytes != null,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.share),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showSaved) {
        AlertDialog(
            onDismissRequest = { showSaved = false },
            title = { Text(stringResource(R.string.qr_saved)) },
            text = { Text(stringResource(R.string.qr_saved_desc)) },
            confirmButton = {
                TextButton(onClick = { showSaved = false }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}

private fun QrContentType.labelRes(): Int = when (this) {
    QrContentType.URL -> R.string.qr_type_url
    QrContentType.TEXT -> R.string.qr_type_text
    QrContentType.WIFI -> R.string.qr_type_wifi
    QrContentType.EMAIL -> R.string.qr_type_email
    QrContentType.SMS -> R.string.qr_type_sms
    QrContentType.PHONE -> R.string.qr_type_phone
}

@Composable
private fun InternetInfoCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.cv_internet_required),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun QrTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColorRow(
    label: String,
    selected: String,
    presets: List<String>,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            presets.forEach { hex ->
                val color = runCatching { Color(hex.toColorInt()) }.getOrDefault(Color.Gray)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(color, CircleShape)
                        .border(
                            width = if (selected.equals(hex, ignoreCase = true)) 3.dp else 1.dp,
                            color = if (selected.equals(hex, ignoreCase = true)) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        )
                        .clickable { onSelect(hex) },
                )
            }
        }
    }
}

@Composable
private fun ChipRow(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    optionLabel: @Composable (String) -> String = { it },
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option)) },
                )
            }
        }
    }
}

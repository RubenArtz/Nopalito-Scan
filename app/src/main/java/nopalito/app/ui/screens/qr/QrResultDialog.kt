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

// WifiConfiguration is deliberately kept for the pre-Q (API 26-28) branch of
// connectToWifi(): minSdk is 26 and on those devices it is the only way to
// join a network (WifiNetworkSuggestion requires API 29+). API 29+ uses
// WifiNetworkSuggestion. File-scoped because lint flags the import itself.
@file:Suppress("DEPRECATION")

package nopalito.app.ui.screens.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import nopalito.app.R

@Composable
fun QrResultDialog(
    detected: QrDetected,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onOpenMap: () -> Unit,
    onConnect: (QrDetected.Type.Wifi) -> Unit,
    onClose: () -> Unit,
    onSaveImage: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // Gradient header band.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                )
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = typeIcon(detected.type),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(typeTitle(detected.type)),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            detected.format?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.85f),
                                )
                            }
                        }
                    }
                }

                Image(
                    bitmap = detected.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .height(340.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )

                // Parsed info in a subtle container.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (val type = detected.type) {
                            is QrDetected.Type.Wifi -> {
                                type.ssid?.let { InfoRow(stringResource(R.string.qr_wifi_ssid), it) }
                                type.password?.let { InfoRow(stringResource(R.string.qr_wifi_password), it) }
                                InfoRow(
                                    stringResource(R.string.qr_wifi_security),
                                    stringResource(wifiSecurityLabel(type.security)),
                                )
                            }

                            is QrDetected.Type.Url ->
                                InfoRow(stringResource(R.string.qr_format), type.url)

                            is QrDetected.Type.Email -> {
                                InfoRow(stringResource(R.string.qr_email_address), type.address)
                                type.subject?.let { InfoRow(stringResource(R.string.qr_email_subject), it) }
                                type.body?.let { InfoRow(stringResource(R.string.qr_email_body), it) }
                            }

                            is QrDetected.Type.Phone ->
                                InfoRow(stringResource(R.string.qr_phone_number), type.number)

                            is QrDetected.Type.Sms -> {
                                InfoRow(stringResource(R.string.qr_sms_number), type.number)
                                type.message?.let { InfoRow(stringResource(R.string.qr_sms_message), it) }
                            }

                            is QrDetected.Type.Geo -> {
                                InfoRow(stringResource(R.string.qr_geo_lat), type.lat.toString())
                                InfoRow(stringResource(R.string.qr_geo_lng), type.lng.toString())
                            }

                            is QrDetected.Type.Text ->
                                InfoRow(stringResource(R.string.qr_content), detected.content)
                        }
                    }
                }

                // Primary actions.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Button(
                        onClick = onCopy,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 50.dp),
                    ) {
                        Text(stringResource(R.string.qr_copy))
                    }
                    val wifi = detected.type as? QrDetected.Type.Wifi
                    when {
                        wifi != null -> Button(
                            onClick = { onConnect(wifi) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 50.dp),
                        ) {
                            Text(stringResource(R.string.qr_connect))
                        }

                        detected.type is QrDetected.Type.Url -> OutlinedButton(
                            onClick = onOpen,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 50.dp),
                        ) {
                            Text(stringResource(R.string.qr_open))
                        }

                        detected.type is QrDetected.Type.Geo -> OutlinedButton(
                            onClick = onOpenMap,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 50.dp),
                        ) {
                            Text(stringResource(R.string.qr_open_map))
                        }
                    }
                }

                Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    if (onSaveImage != null) {
                        TextButton(onClick = onSaveImage, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.qr_save))
                        }
                    }
                    TextButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.qr_share))
                    }
                    TextButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.qr_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun typeIcon(type: QrDetected.Type): ImageVector = when (type) {
    is QrDetected.Type.Wifi -> Icons.Default.Wifi
    is QrDetected.Type.Url -> Icons.Default.Link
    is QrDetected.Type.Email -> Icons.Default.Email
    is QrDetected.Type.Phone -> Icons.Default.Phone
    is QrDetected.Type.Sms -> Icons.AutoMirrored.Filled.Message
    is QrDetected.Type.Geo -> Icons.Default.LocationOn
    is QrDetected.Type.Text -> Icons.Default.QrCode
}

private fun typeTitle(type: QrDetected.Type): Int = when (type) {
    is QrDetected.Type.Wifi -> R.string.qr_type_wifi
    is QrDetected.Type.Url -> R.string.qr_type_url
    is QrDetected.Type.Email -> R.string.qr_type_email
    is QrDetected.Type.Phone -> R.string.qr_type_phone
    is QrDetected.Type.Sms -> R.string.qr_type_sms
    is QrDetected.Type.Geo -> R.string.qr_type_geo
    is QrDetected.Type.Text -> R.string.qr_type_text
}

/** Returns a connect handler that requests the nearby/location permission on first use. */
@Composable
fun rememberWifiConnect(): (QrDetected.Type.Wifi) -> Unit {
    val context = LocalContext.current
    var pendingWifi by remember { mutableStateOf<QrDetected.Type.Wifi?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingWifi?.let { wifi ->
                val ok = connectToWifi(context, wifi)
                Toast.makeText(
                    context,
                    if (ok) R.string.qr_wifi_prompt else R.string.qr_wifi_error,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    return remember(context, launcher) {
        { wifi ->
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
            pendingWifi = wifi
            if (ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                val ok = connectToWifi(context, wifi)
                Toast.makeText(
                    context,
                    if (ok) R.string.qr_wifi_prompt else R.string.qr_wifi_error,
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                launcher.launch(permission)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun connectToWifi(context: Context, wifi: QrDetected.Type.Wifi): Boolean {
    val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val ssid = wifi.ssid ?: return false
        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        wifi.password?.takeIf { it.isNotBlank() }?.let { builder.setWpa2Passphrase(it) }
        val suggestion = builder.setIsAppInteractionRequired(false).build()
        when (wifiManager.addNetworkSuggestions(listOf(suggestion))) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE,
                -> true

            else -> false
        }
    } else {
        val ssid = wifi.ssid ?: return false
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            when (wifi.security) {
                "WEP" -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                    wepKeys[0] = "\"${wifi.password ?: ""}\""
                    wepTxKeyIndex = 0
                }

                "WPA" -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    preSharedKey = "\"${wifi.password ?: ""}\""
                }

                else -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
            }
        }
        val netId = wifiManager.addNetwork(config)
        netId != -1 && wifiManager.enableNetwork(netId, true)
    }
}

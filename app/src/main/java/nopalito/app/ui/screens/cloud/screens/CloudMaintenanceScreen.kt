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

package nopalito.app.ui.screens.cloud.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nopalito.app.R
import nopalito.app.push.TranslationHelper
import nopalito.app.push.renderMaintenanceText
import nopalito.app.ui.screens.cloud.data.MaintenanceLocalizer
import nopalito.app.ui.screens.cloud.data.MaintenanceLocalizer.MaintenanceField
import nopalito.app.ui.screens.cloud.model.MaintenanceStatus
import nopalito.app.ui.screens.cloud.viewmodel.CloudMaintenanceViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.PI
import kotlin.math.sin

/**
 * Full-screen maintenance overlay.
 *
 * Shown when the cloud service is in maintenance mode. Blocks all access
 * to the cloud module and displays:
 * - Animated wrench icon
 * - Maintenance title and message
 * - Start/end times
 * - Time remaining countdown
 * - Reason
 * - Retry button (auto-refreshes every 30s)
 */
@Composable
fun CloudMaintenanceScreen(
    viewModel: CloudMaintenanceViewModel,
    modifier: Modifier = Modifier
) {
    val maintenance by viewModel.maintenanceState.collectAsState()
    val isChecking by viewModel.isChecking.collectAsState()
    val error by viewModel.error.collectAsState()
    // Adaptive cadence (server retry_after / backoff), shown in the footer.
    val refreshSeconds by viewModel.refreshIntervalSeconds.collectAsState()
    val timeRemaining = viewModel.getTimeRemaining()

    // Localized text per field. Resolution follows the maintenance contract:
    // valid *_key -> localized resource; known code -> localized resource;
    // legacy (sanitized) -> ML Kit translated; otherwise generic resource.
    var resolvedTitle by remember { mutableStateOf("") }
    var resolvedMessage by remember { mutableStateOf("") }
    var resolvedReason by remember { mutableStateOf("") }

    val context = LocalContext.current

    // Animated wave divider: solid green on top, solid purple below, with a
    // sine-wave boundary traveling right -> left (no gradient look).
    val waveTransition = rememberInfiniteTransition(label = "maintenance_wave")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )
    val topColor = Color(0xFF076632)
    val bottomColor = Color(0xFF7A68B8)

    // Resolve when maintenance data changes. Legacy free text goes through
    // raw -> sanitize -> translate -> sanitize final (see MaintenanceLocalizer),
    // so no raw backend text ever reaches the UI (even when translation fails).
    // Localized resources are read via context.getString, which already reflects
    // the app locale.
    LaunchedEffect(maintenance) {
        maintenance?.let { status ->
            withContext(Dispatchers.Default) {
                val translate: suspend (String) -> String = { TranslationHelper.translate(it) }
                resolvedTitle = MaintenanceLocalizer.resolveText(
                    status,
                    MaintenanceField.TITLE,
                    context,
                    translate
                )
                resolvedMessage = MaintenanceLocalizer.resolveText(
                    status,
                    MaintenanceField.MESSAGE,
                    context,
                    translate
                )
                resolvedReason = MaintenanceLocalizer.resolveText(
                    status,
                    MaintenanceField.REASON,
                    context,
                    translate
                )
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(bottomColor)
            val baseY = h * 0.42f
            val amplitude = h * 0.035f
            val wavelength = w * 0.6f
            // Melt band: solid green up top, blending fluidly into purple
            // right at the wave edge so both colors fuse into each other.
            val blend = h * 0.12f
            drawPath(
                waveTopPath(
                    width = w,
                    baseY = baseY + blend * 0.45f,
                    amplitude = amplitude,
                    wavelength = wavelength,
                    phase = wavePhase
                ),
                Brush.verticalGradient(
                    0f to topColor,
                    ((baseY - blend).coerceAtLeast(0f) / h) to topColor,
                    ((baseY + blend * 0.45f) / h) to bottomColor,
                    startY = 0f,
                    endY = h
                )
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.notification),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(96.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Title (translated on-device via ML Kit, sanitized)
            Text(
                text = renderMaintenanceText(resolvedTitle, maintenance?.title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Message (translated on-device via ML Kit, sanitized)
            Text(
                text = renderMaintenanceText(resolvedMessage, maintenance?.message),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Time info card
            MaintenanceTimeCard(
                maintenance = maintenance,
                timeRemaining = timeRemaining
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Reason (translated on-device via ML Kit, sanitized)
            val safeReason = renderMaintenanceText(resolvedReason, maintenance?.reason)
            if (safeReason.isNotBlank()) {
                Text(
                    text = safeReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Error message (if any)
            error?.let { errorMsg ->
                Text(
                    text = errorMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF6B6B),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Retry button
            Button(
                onClick = { viewModel.retry() },
                enabled = !isChecking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0CAD55),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text(
                        text = stringResource(R.string.cloud_maint_retry),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Auto-refresh info
            Text(
                text = stringResource(
                    R.string.cloud_maint_auto_refresh,
                    refreshSeconds
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.4f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MaintenanceTimeCard(
    maintenance: MaintenanceStatus?,
    timeRemaining: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Start time
        maintenance?.startsAt?.let { startsAt ->
            TimeInfoRow(
                label = stringResource(R.string.cloud_maint_start),
                value = formatDateTime(startsAt, maintenance.timezone)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // End time
        maintenance?.endsAt?.let { endsAt ->
            TimeInfoRow(
                label = stringResource(R.string.cloud_maint_end),
                value = formatDateTime(endsAt, maintenance.timezone)
            )
        }

        // Time remaining
        timeRemaining?.let { remaining ->
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF86E6B1).copy(alpha = 0.2f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.cloud_maint_time_remaining, remaining),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF86E6B1)
                )
            }
        }
    }
}

@Composable
private fun TimeInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}

private fun formatDateTime(isoString: String, timezone: String?): String {
    return try {
        val instant = Instant.parse(isoString)
        val zoneId = if (timezone != null) {
            try {
                ZoneId.of(timezone)
            } catch (_: Exception) {
                ZoneId.systemDefault()
            }
        } else {
            ZoneId.systemDefault()
        }
        val zonedDateTime = instant.atZone(zoneId)
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        zonedDateTime.format(formatter)
    } catch (_: Exception) {
        isoString
    }
}

/**
 * Top region path with a sine-wave bottom edge. Phase grows with time, so the
 * wave travels right -> left and loops seamlessly every 2*PI.
 */
private fun waveTopPath(
    width: Float,
    baseY: Float,
    amplitude: Float,
    wavelength: Float,
    phase: Float,
    stepPx: Float = 8f
): Path = Path().apply {
    val k = (2 * PI).toFloat() / wavelength
    moveTo(0f, 0f)
    lineTo(width, 0f)
    var x = width
    while (x >= 0f) {
        lineTo(x, baseY + amplitude * sin(k * x + phase))
        x -= stepPx
    }
    lineTo(0f, 0f)
    close()
}

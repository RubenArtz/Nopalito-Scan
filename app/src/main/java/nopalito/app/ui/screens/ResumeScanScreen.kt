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

package nopalito.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nopalito.app.R
import nopalito.app.ui.components.isLandscape
import nopalito.app.ui.components.pageCountText
import nopalito.app.ui.screens.document.DocumentUiState


@Composable
fun ResumeScanScreen(
    currentDocument: DocumentUiState,
    onResumeScan: () -> Unit,
    onStartNewScan: () -> Unit,
) {
    val pageCount = currentDocument.document.pageCount()
    val firstPageThumbnail = currentDocument.document.thumbnail(0)

    val resumeScanModifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onResumeScan)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        .padding(horizontal = 16.dp, vertical = 24.dp)

    Scaffold { innerPadding ->
        if (!isLandscape(LocalConfiguration.current)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = resumeScanModifier.weight(2f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FirstPageThumbnail(firstPageThumbnail, Modifier.weight(1f))
                    Spacer(modifier = Modifier.height(20.dp))
                    ResumeScanActions(pageCount, onResumeScan)
                }
                HorizontalDivider()
                NewScanArea(onStartNewScan, Modifier.weight(1f))
            }
        } else {
            Row {
                Row(
                    modifier = resumeScanModifier.weight(1.8f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    FirstPageThumbnail(firstPageThumbnail, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        ResumeScanActions(pageCount, onResumeScan)
                    }
                }
                VerticalDivider()
                NewScanArea(onStartNewScan, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FirstPageThumbnail(firstPageThumbnail: Bitmap?, modifier: Modifier) {
    firstPageThumbnail?.let { bmp ->
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun ResumeScanActions(pageCount: Int, onResumeScan: () -> Unit) {

    FlowRow {
        val style = MaterialTheme.typography.bodyMedium
        Text(stringResource(R.string.scan_current), style = style)
        Text(" • ", style = style)
        Text(pageCountText(pageCount), style = style)
    }
    BigButton(onClick = onResumeScan, text = stringResource(R.string.scan_resume))
}

@Composable
private fun NewScanArea(onStartNewScan: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onStartNewScan)
            .padding(horizontal = 16.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.scan_discard_current),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        BigButton(onClick = onStartNewScan, text = stringResource(R.string.scan_new))
    }
}

@Composable
fun BigButton(onClick: () -> Unit, text: String) {
    Button(onClick = onClick, modifier = Modifier.padding(vertical = 16.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}
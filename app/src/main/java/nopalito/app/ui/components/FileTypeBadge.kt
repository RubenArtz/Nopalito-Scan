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

package nopalito.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Small icon badge (bottom-right) identifying the file type by its name. */
@Composable
fun FileTypeBadge(fileName: String, modifier: Modifier = Modifier) {
    val icon: ImageVector = when {
        fileName.endsWith(".pdf", ignoreCase = true) -> Icons.Default.PictureAsPdf
        fileName.endsWith(".doc", ignoreCase = true) ||
                fileName.endsWith(".docx", ignoreCase = true) -> Icons.Default.TextFields

        fileName.endsWith(".xls", ignoreCase = true) ||
                fileName.endsWith(".xlsx", ignoreCase = true) -> Icons.Default.TableChart

        fileName.endsWith(".png", ignoreCase = true) ||
                fileName.endsWith(".jpg", ignoreCase = true) ||
                fileName.endsWith(".jpeg", ignoreCase = true) ||
                fileName.endsWith(".webp", ignoreCase = true) ||
                fileName.endsWith(".bmp", ignoreCase = true) -> Icons.Default.Image

        else -> Icons.Default.Description
    }
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(11.dp), tint = Color.White)
    }
}
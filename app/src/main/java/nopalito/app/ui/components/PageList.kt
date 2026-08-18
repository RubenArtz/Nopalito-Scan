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

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nopalito.app.THUMBNAIL_SIZE_DP
import nopalito.app.ui.screens.document.PageOverlays
import nopalito.app.ui.state.DocumentUiModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

const val PAGE_LIST_ELEMENT_SIZE_DP = THUMBNAIL_SIZE_DP

data class CommonPageListState(
    val document: DocumentUiModel,
    val onPageClick: (Int) -> Unit,
    val onPageReorder: (String, Int) -> Unit,
    val listState: LazyListState,
    val showPageNumbers: Boolean,
    val currentPageIndex: Int? = null,
    val pageOverlays: Map<String, PageOverlays> = emptyMap(),
    val onLastItemPosition: ((Offset) -> Unit)? = null,
)

@Composable
fun CommonPageList(
    state: CommonPageListState,
    modifier: Modifier = Modifier,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val reorderableLazyListState = rememberReorderableLazyListState(state.listState) { from, to ->
        val pageId = state.document.pages[from.index].key.pageId
        state.onPageReorder(pageId, to.index)
    }
    val content: LazyListScope.() -> Unit = {
        itemsIndexed(
            state.document.pages.map { it.key },
            key = { _, item -> item.pageId }) { index, item ->
            ReorderableItem(reorderableLazyListState, key = item.pageId) { isDragging ->
                val borderColor =
                    if (isDragging) MaterialTheme.colorScheme.primary else Color.Transparent
                val modifier = Modifier
                    .longPressDraggableHandle()
                    .border(
                        width = 2.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(6.dp)
                    )
                val image = state.document.thumbnail(index)
                val overlays = state.pageOverlays[item.pageId]
                if (image != null) {
                    PageThumbnail(image, index, state, modifier, overlays)
                }
            }
        }
    }
    if (isLandscape) {
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier,
            content = content,
        )
    } else {
        LazyRow(
            state = state.listState,
            contentPadding = PaddingValues(4.dp),
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
    if (state.document.isEmpty()) {
        val layoutDirection = LocalLayoutDirection.current
        Box(
            modifier = Modifier
                .height(THUMBNAIL_SIZE_DP.dp)
                .addPositionCallback(
                    state.onLastItemPosition, LocalDensity.current, 0.5f, layoutDirection
                )
        ) {}
    }
}

@Composable
private fun PageThumbnail(
    image: Bitmap,
    index: Int,
    state: CommonPageListState,
    modifier: Modifier,
    overlays: PageOverlays?,
) {
    val bitmap = image.asImageBitmap()
    val isSelected = index == state.currentPageIndex
    val borderColor =
        if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray
    val maxImageSize = PAGE_LIST_ELEMENT_SIZE_DP.dp
    var imageModifier =
        if (bitmap.height > bitmap.width)
            Modifier.height(maxImageSize)
        else
            Modifier.width(maxImageSize)
    if (index == state.document.lastIndex()) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        imageModifier = imageModifier.addPositionCallback(
            state.onLastItemPosition, density, 1.0f, layoutDirection
        )
    }
    Box(modifier = modifier.height(PAGE_LIST_ELEMENT_SIZE_DP.dp)) {
        Card(
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .padding(4.dp)
                .align(Alignment.Center)
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "${index + 1}",
                modifier = imageModifier.clickable { state.onPageClick(index) }
            )
        }
        if (state.showPageNumbers) {
            PageNumberBadge(index)
        }
        if (overlays != null) {
            OverlayBadge(overlays)
        }
    }
}

@Composable
private fun BoxScope.OverlayBadge(overlays: PageOverlays) {
    val hasSignature = overlays.signatureState != null
    val hasDate = overlays.dateText != null
    if (!hasSignature && !hasDate) return

    val label = buildString {
        if (hasSignature) append("S")
        if (hasSignature && hasDate) append("+")
        if (hasDate) append("D")
    }
    Box(
        modifier = Modifier
            .padding(4.dp)
            .align(Alignment.TopEnd)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 8.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}

@Composable
private fun BoxScope.PageNumberBadge(index: Int) {
    Box(
        modifier = Modifier
            .padding(8.dp)
            .align(Alignment.BottomCenter)
            .background(Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(4.dp))
            .padding(vertical = 0.dp, horizontal = 8.dp)
    ) {
        Text(
            text = "${index + 1}",
            color = Color.White,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
    }
}

fun Modifier.addPositionCallback(
    callback: ((Offset) -> Unit)?,
    density: Density,
    xFactor: Float,
    layoutDirection: LayoutDirection
): Modifier {
    if (callback == null) {
        return this
    }

    return this.onGloballyPositioned { coordinates ->
        with(density) {
            val width = PAGE_LIST_ELEMENT_SIZE_DP.dp.toPx()

            val x = when (layoutDirection) {
                LayoutDirection.Ltr -> width * xFactor
                LayoutDirection.Rtl -> width * (0.5f - xFactor)
            }

            callback(
                coordinates.localToWindow(Offset(x = x, y = width / 2))
            )
        }
    }
}

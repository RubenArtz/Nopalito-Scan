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

package nopalito.app.ui.screens.debug

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nopalito.app.R
import nopalito.app.data.ImageRepository
import nopalito.app.data.VariantMetadata
import nopalito.app.data.VariantSelection
import nopalito.app.domain.ScanPipelineFlags
import nopalito.app.domain.comparableWith

/** Read-only display. Only the explicitly labelled actions create references. */
@Composable
fun VariantComparisonDialog(
    pageId: String,
    repository: ImageRepository,
    flags: ScanPipelineFlags,
    onDewarpChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dewarpNotImplemented = stringResource(R.string.processing_dewarp_not_implemented)
    val referenceSaved = stringResource(R.string.processing_variant_saved)
    val cancelledMessage = stringResource(R.string.processing_variant_cancelled)
    var variants by remember(pageId) { mutableStateOf(emptyList<VariantMetadata>()) }
    var selection by remember(pageId) { mutableStateOf(VariantSelection()) }
    var message by remember(pageId) { mutableStateOf<String?>(null) }
    var work by remember(pageId) { mutableStateOf<Job?>(null) }
    var revision by remember(pageId) { mutableIntStateOf(0) }
    var zoom by remember(pageId) { mutableFloatStateOf(1f) }
    var pan by remember(pageId) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(pageId, revision) {
        try {
            variants = repository.referenceVariants(pageId)
            selection = repository.referenceSelection(pageId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            message = e.message
        }
    }
    DisposableEffect(pageId) { onDispose { work?.cancel() } }
    fun process(freeze: Boolean) {
        work = scope.launch {
            message = null
            try {
                repository.createBaselineReference(pageId, freeze, flags)
                message = if (flags.dewarp) dewarpNotImplemented else referenceSaved
            } catch (e: CancellationException) {
                message = cancelledMessage
                throw e
            } catch (e: Exception) {
                message = e.message
            } finally {
                revision++; work = null
            }
        }
    }

    val baseline = variants.firstOrNull { it.id == selection.baselineVariantId }
    val candidate = variants.firstOrNull { it.id == selection.candidateVariantId }
    val compatible = baseline != null && candidate != null && baseline.comparableWith(candidate)
    val active = variants.firstOrNull { it.id == selection.activeVariantId }
    Dialog(
        onDismissRequest = { work?.cancel(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
                .testTag("processing_variant_dialog")
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Text(
                    stringResource(R.string.processing_variant_comparison_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("variant_comparison_title")
                )
                Text(
                    stringResource(
                        R.string.processing_variant_active,
                        selection.activeVariantId
                            ?: stringResource(R.string.processing_variant_active_unregistered)
                    ), modifier = Modifier.testTag("baseline_active_label")
                )
                Row {
                    TextButton(
                        onClick = { process(true) },
                        enabled = work == null,
                        modifier = Modifier.testTag("freeze_current_button")
                    ) { Text(stringResource(R.string.processing_variant_freeze_current)) }
                    TextButton(
                        onClick = { process(false) },
                        enabled = work == null,
                        modifier = Modifier.testTag("reprocess_baseline_button")
                    ) { Text(stringResource(R.string.processing_variant_reprocess_baseline)) }
                }
                Row {
                    Switch(
                        checked = flags.dewarp,
                        onCheckedChange = onDewarpChanged,
                        enabled = work == null
                    )
                    Text(stringResource(R.string.processing_dewarp_experimental))
                }
                if (candidate == null) Text(stringResource(R.string.processing_variant_candidate_missing))
                else if (!compatible) Text(stringResource(R.string.processing_variant_not_comparable))
                if (candidate != null) TextButton(
                    enabled = work == null,
                    onClick = {
                        work = scope.launch {
                            try {
                                repository.deleteCandidateReference(pageId)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                message = e.message
                            } finally {
                                revision++; work = null
                            }
                        }
                    },
                    modifier = Modifier.testTag("delete_candidate_button")
                ) { Text(stringResource(R.string.processing_variant_delete_candidate)) }
                val visible = listOfNotNull(
                    baseline, candidate.takeIf { compatible },
                    active?.takeIf { baseline?.comparableWith(it) == true }).distinctBy { it.id }
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                        .pointerInput(pageId) {
                            detectTransformGestures { _, movement, factor, _ ->
                                zoom = (zoom * factor).coerceIn(1f, 8f)
                                pan = if (zoom == 1f) Offset.Zero else pan + movement
                            }
                        }) {
                    visible.forEach { variant ->
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            Text(
                                (if (variant.id == selection.baselineVariantId) stringResource(R.string.processing_variant_baseline) else stringResource(
                                    R.string.processing_variant_candidate
                                )) +
                                        (if (variant.id == selection.activeVariantId) " · " + stringResource(
                                            R.string.processing_variant_active_suffix
                                        ) else ""),
                                modifier = Modifier.height(48.dp)
                            )
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val w = variant.recipe.outputWidth.toFloat()
                                val h = variant.recipe.outputHeight.toFloat()
                                val fit = minOf(maxWidth.value / w, maxHeight.value / h)
                                val rotatedFit = minOf(maxWidth.value / h, maxHeight.value / w)
                                val correction =
                                    if (variant.identity.rotation % 180 != 0 && fit > 0) rotatedFit / fit else 1f
                                AsyncImage(
                                    model = repository.variants.imageFile(pageId, variant.id),
                                    contentDescription = variant.role.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = zoom; scaleY = zoom
                                            translationX = pan.x; translationY = pan.y
                                        }
                                        .graphicsLayer {
                                            scaleX = correction; scaleY = correction
                                            rotationZ = variant.identity.rotation.toFloat()
                                        },
                                )
                            }
                        }
                    }
                }
                Column(
                    Modifier
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    variants.forEach { v ->
                        Text(
                            stringResource(
                                R.string.processing_variant_metadata,
                                v.role.name + if (v.id == selection.activeVariantId) " · " + stringResource(
                                    R.string.processing_variant_active_suffix
                                ) else "",
                                v.recipe.outputWidth, v.recipe.outputHeight, v.fileSize,
                                v.identity.algorithmId, v.identity.regionId, v.identity.rotation,
                                v.identity.recipeHash, v.identity.sourceSha256, v.outputSha256,
                                v.recipe.reproducible
                            )
                        )
                    }
                }
                message?.let { Text(it) }
                Row {
                    TextButton(
                        onClick = { zoom = 1f; pan = Offset.Zero },
                        modifier = Modifier.testTag("reset_zoom_button")
                    ) { Text(stringResource(R.string.action_reset_zoom)) }
                    if (work != null) TextButton(onClick = { work?.cancel() }) {
                        Text(
                            stringResource(
                                R.string.cancel
                            )
                        )
                    }
                    TextButton(onClick = { work?.cancel(); onDismiss() }) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}
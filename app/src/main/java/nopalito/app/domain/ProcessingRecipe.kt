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

package nopalito.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import nopalito.app.data.NormalizedQuad
import java.security.MessageDigest

/** Timestamp is provenance, never an input to the deterministic parameter hash. */
@Serializable
data class ProcessingRecipe(
    val pipelineVersion: String = "1.0",
    val algorithmId: String = "baseline",
    val sourceSha256: String,
    val sourceFileSize: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val effectiveOrientation: Int,
    val quad: NormalizedQuad?,
    /** null means historical provenance was not recorded; never guess automatic. */
    val quadSource: String?,
    val outputWidth: Int,
    val outputHeight: Int,
    val colorMode: String,
    val exportQuality: String,
    val jpegQuality: Int?,
    val maxPixels: Long,
    val postProcessing: Map<String, String>,
    val openCvVersion: String,
    val appVersion: String,
    val implementationHash: String,
    val focalLength: Float? = null,
    val sensorWidth: Float? = null,
    val subjectDistance: Float? = null,
    val rotation: Int = 0,
    val sourceKind: String = "original",
    val renderPath: String = "processedImage",
    val reproducible: Boolean = true,
    val timestamp: Long,
) {
    fun parameterHash(): String {
        val tree = recipeJson.encodeToJsonElement(this).jsonObject
        return sha256(
            canonical(JsonObject(tree.filterKeys { it != "timestamp" })).toByteArray(
                Charsets.UTF_8
            )
        )
    }

    companion object {
        val recipeJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        private fun canonical(value: JsonElement): String = when (value) {
            is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") {
                JsonPrimitive(it.key).toString() + ":" + canonical(it.value)
            }

            is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
            else -> value.toString()
        }
    }
}

@Serializable
data class VariantIdentity(
    val pageId: String,
    val regionId: String = "whole-page",
    val algorithmId: String,
    val recipeHash: String,
    val sourceSha256: String,
    val outputQuality: String,
    val colorMode: String,
    val rotation: Int,
    val processingVersion: String,
) {
    fun stableId(): String = ProcessingRecipe.sha256(
        ProcessingRecipe.recipeJson.encodeToString(this).toByteArray(Charsets.UTF_8)
    )
}

/** Snapshot only: these values do not configure or replace PostProcessing. */
object BaselineParameters {
    val current = sortedMapOf(
        "order" to "perspective,resize,enhanceCapturedImage,correctBlur,rotate,jpeg",
        "perspective" to "estimateRealDimensions.snapToStandardFormat; mean-edge-area; Kotlin homography; INTER_LINEAR; BORDER_CONSTANT=0",
        "resize" to "maxPixels; INTER_AREA; clone when within limit",
        "retinex.color" to "Lab L; +1; downsample=2 AREA; log box kernels=maxDimSmall/[60,12,3,1.7] odd>=3; weights=0.25; minmax epsilon=1e-6; upscale=CUBIC; amplitude=78; alpha=0.82",
        "retinex.illumination" to "sigma=max(maxDim/6,35); floor=10; meanFloor=1; divide CV_32F",
        "retinex.whitePoint" to "percentiles=0.005,0.996; histogram=256 [0,256); mode search=190..255; accept=190..253; targetHigh=255; epsilon=1e-6",
        "grayscale" to "BGR2GRAY; CV_32F +1; kernels=maxDim/[50,6,2.4]; boxFilter; log(+1); weights=1/3; exp; percentiles=0.004,0.99; mode=175..255; fallback>=254 percentiles=0.01,0.99 epsilon=1e-6",
        "bilateralFilter" to "grayscale only; d=9;sigmaColor=20;sigmaSpace=10; default border",
        "lighten" to "Lab L; (L+18)*1.06; clamp=0..255",
        "bw" to "medianBlur=3; adaptiveThreshold GAUSSIAN_C BINARY; max=255; block=31; C=12",
        "originalColorMode" to "enhance clone; correctBlur still runs",
        "correctBlur" to "Laplacian CV_64F defaults; varianceThreshold=120; sigma=1.8; amount=0.45+0.55*clamp((120-score)/120,0,1); addWeighted; no-op>=120",
    )
}

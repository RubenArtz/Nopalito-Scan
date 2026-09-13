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

package nopalito.app.platform

import android.graphics.BitmapFactory
import nopalito.app.BuildConfig
import nopalito.app.data.PageV2
import nopalito.app.data.toMetadata
import nopalito.app.domain.BaselineParameters
import nopalito.app.domain.ExportQuality
import nopalito.app.domain.Jpeg
import nopalito.app.domain.ProcessingRecipe
import org.opencv.core.Core

/** Describes, but never configures, the existing pipeline. */
object BaselineReference {
    /** Refuse to claim reproducibility across runtime/implementation changes. */
    fun replay(recipe: ProcessingRecipe, input: ByteArray): ByteArray {
        require(recipe.reproducible && recipe.renderPath == "processedImage")
        require(recipe.algorithmId == "baseline" && recipe.pipelineVersion == "1.0")
        require(recipe.implementationHash == BuildConfig.BASELINE_IMPLEMENTATION_SHA256)
        require(recipe.openCvVersion == Core.VERSION && recipe.appVersion == BuildConfig.VERSION_NAME)
        require(recipe.postProcessing == BaselineParameters.current)
        require(
            recipe.exportQuality == ExportQuality.BALANCED.name &&
                    recipe.jpegQuality == ExportQuality.BALANCED.jpegQuality && recipe.maxPixels == ExportQuality.BALANCED.maxPixels
        )
        require(input.size.toLong() == recipe.sourceFileSize && ProcessingRecipe.sha256(input) == recipe.sourceSha256)
        val page = PageV2(
            id = "replay",
            quad = requireNotNull(recipe.quad),
            baseRotationDegrees = recipe.effectiveOrientation,
            manualRotationDegrees = recipe.rotation,
            isColored = recipe.colorMode == "COLOR",
            colorMode = nopalito.imageprocessing.ColorMode.valueOf(recipe.colorMode),
            sourceWidth = recipe.sourceWidth,
            sourceHeight = recipe.sourceHeight,
            focalLength = recipe.focalLength,
            sensorWidth = recipe.sensorWidth,
            subjectDistance = recipe.subjectDistance
        )
        val output = render(page, input)
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(output, 0, output.size, dimensions)
        check(dimensions.outWidth == recipe.outputWidth && dimensions.outHeight == recipe.outputHeight)
        return output
    }

    fun recipe(
        page: PageV2,
        source: ByteArray,
        output: ByteArray,
        kind: String,
        stored: Boolean
    ): ProcessingRecipe {
        fun dimensions(bytes: ByteArray): Pair<Int, Int> {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            require(opts.outWidth > 0 && opts.outHeight > 0) { "Invalid JPEG dimensions" }
            return opts.outWidth to opts.outHeight
        }
        val (sw, sh) = dimensions(source)
        val (ow, oh) = dimensions(output)
        return ProcessingRecipe(
            sourceSha256 = ProcessingRecipe.sha256(source),
            sourceFileSize = source.size.toLong(),
            sourceWidth = sw,
            sourceHeight = sh,
            effectiveOrientation = page.baseRotationDegrees,
            quad = page.userQuad ?: page.quad,
            quadSource = if (page.userQuad != null) "manual" else null,
            outputWidth = ow,
            outputHeight = oh,
            colorMode = page.colorMode?.name ?: "UNKNOWN",
            exportQuality = ExportQuality.BALANCED.name,
            jpegQuality = if (stored) page.jpegQuality else ExportQuality.BALANCED.jpegQuality,
            maxPixels = ExportQuality.BALANCED.maxPixels,
            postProcessing = BaselineParameters.current,
            openCvVersion = if (stored) "unrecorded; current=${Core.VERSION}" else Core.VERSION,
            appVersion = if (stored) "unrecorded; current=${BuildConfig.VERSION_NAME}" else BuildConfig.VERSION_NAME,
            implementationHash = BuildConfig.BASELINE_IMPLEMENTATION_SHA256,
            focalLength = page.focalLength,
            sensorWidth = page.sensorWidth,
            subjectDistance = page.subjectDistance,
            rotation = page.manualRotationDegrees,
            sourceKind = kind,
            renderPath = if (stored) "stored-reference" else "processedImage",
            reproducible = !stored && page.toMetadata() != null,
            timestamp = System.currentTimeMillis(),
        )
    }

    fun render(page: PageV2, input: ByteArray): ByteArray {
        val metadata =
            requireNotNull(page.toMetadata()) { "Legacy page has no processing geometry" }
        return processedImage(
            Jpeg(input), metadata, metadata.baseRotation,
            requireNotNull(page.colorMode), ExportQuality.BALANCED
        ).bytes
    }
}

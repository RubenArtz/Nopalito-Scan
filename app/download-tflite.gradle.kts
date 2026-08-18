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

import org.gradle.api.tasks.Copy
import java.net.URL

val modelVersion = "v1.2.0"
val modelFileName = "fairscan-segmentation-model.tflite"
val modelUrl =
    "https://github.com/pynicolas/fairscan-segmentation-model/releases/download/$modelVersion/$modelFileName"

val downloadedModelPath = layout.buildDirectory.file("downloads/$modelFileName")
val generatedAssetsDir = layout.buildDirectory.dir("generated/assets")

val downloadTFLiteModel = tasks.register("downloadTFLiteModel") {
    val outputFile = downloadedModelPath.get().asFile
    outputs.file(outputFile)

    doLast {
        if (!outputFile.exists()) {
            println("Downloading $modelFileName from $modelUrl")
            outputFile.parentFile.mkdirs()
            URL(modelUrl).openStream().use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            println("Model already downloaded: ${outputFile.absolutePath}")
        }
    }
}

val copyTFLiteToAssets = tasks.register<Copy>("copyTFLiteToAssets") {
    dependsOn(downloadTFLiteModel)
    from(downloadedModelPath)
    into(generatedAssetsDir)
}

tasks.named("preBuild") {
    dependsOn(copyTFLiteToAssets)
}

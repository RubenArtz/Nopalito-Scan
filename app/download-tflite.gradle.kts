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

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.net.URL

val tfliteModelVersion = "v1.2.0"
val tfliteModelFileName = "fairscan-segmentation-model.tflite"
val tfliteModelUrl =
    "https://github.com/pynicolas/fairscan-segmentation-model/releases/download/$tfliteModelVersion/$tfliteModelFileName"

val downloadedModelPath = layout.buildDirectory.file("downloads/$tfliteModelFileName")
val generatedAssetsDir = layout.buildDirectory.dir("generated/assets")

// Dedicated task type (instead of doLast on a plain DefaultTask) so the task
// is configuration-cache compatible: only its lazy inputs are serialized,
// never the surrounding script object.
abstract class DownloadTFLiteModel : DefaultTask() {
    @get:Input
    abstract val modelUrl: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val outputFile = outputFile.get().asFile
        if (!outputFile.exists()) {
            logger.lifecycle("Downloading {} from {}", outputFile.name, modelUrl.get())
            outputFile.parentFile.mkdirs()
            URL(modelUrl.get()).openStream().use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            logger.lifecycle("Model already downloaded: {}", outputFile.absolutePath)
        }
    }
}

val downloadTFLiteModel = tasks.register<DownloadTFLiteModel>("downloadTFLiteModel") {
    modelUrl.set(tfliteModelUrl)
    outputFile.set(downloadedModelPath)
}

val copyTFLiteToAssets = tasks.register<Copy>("copyTFLiteToAssets") {
    dependsOn(downloadTFLiteModel)
    from(downloadedModelPath)
    into(generatedAssetsDir)
}

tasks.named("preBuild") {
    dependsOn(copyTFLiteToAssets)
}
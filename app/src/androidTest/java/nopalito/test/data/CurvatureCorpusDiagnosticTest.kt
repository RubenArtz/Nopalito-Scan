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

package nopalito.test.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import nopalito.app.data.NormalizedQuad
import nopalito.app.data.PointD
import nopalito.app.domain.CurvatureAnalysisRequest
import nopalito.app.domain.CurvatureAnalysisResult
import nopalito.app.domain.CurvatureEvidenceType
import nopalito.app.domain.CurvatureQuadSource
import nopalito.app.domain.ProjectionCurvatureAnalyzer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Explicit diagnostic only; never connected to capture or production page processing. */
@RunWith(AndroidJUnit4::class)
class CurvatureCorpusDiagnosticTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val output = File(context.cacheDir, "curvature-diagnostics")
    private val inputRoot = File(context.cacheDir, "curvature-corpus-inputs")

    private fun sha(input: InputStream): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { b -> "%02x".format(b.toInt() and 255) }
    }

    private fun pixelSha(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(bitmap.width)
        val bytes = ByteBuffer.allocate(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            bytes.clear()
            row.forEach { bytes.putInt(it) }
            digest.update(bytes.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun protectedFiles(): Map<String, String> =
        listOf(
            "sources",
            "scanned_pages",
            "originals",
            "safe",
            "processing_variants",
            "ab-validation"
        )
            .flatMap { name ->
                File(context.filesDir, name).walkTopDown().filter { it.isFile }.map {
                    it.relativeTo(context.filesDir).path to sha(it.inputStream())
                }.toList()
            }.toMap()

    @Test
    fun analyzeRegisteredCorpus() = runBlocking {
        check(OpenCVLoader.initLocal()) { "OpenCV native library is unavailable." }
        check(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        prepareIsolatedInputs()
        val manifestPath = InstrumentationRegistry.getArguments().getString("curvatureManifest")
        val analysisDimensionOverride = InstrumentationRegistry.getArguments()
            .getString("curvatureMaxAnalysisDimension")?.toIntOrNull()
        val preservedSourceOriginals = manifestPath == "PRESERVED_SOURCEORIGINALS"
        val manifest = when {
            preservedSourceOriginals -> isolatedPreservedOriginalManifest()
            manifestPath == null -> JSONObject(
                context.assets.open("curvature-corpus.json").bufferedReader().use { it.readText() }
            )

            else -> JSONObject(File(manifestPath).readText())
        }
        val before = protectedFiles()
        output.mkdirs()
        val reports = JSONArray()
        val decisionFailures = mutableListOf<String>()
        val cases = manifest.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val entry = cases.getJSONObject(i)
            if (entry.optString("status") == "MISSING") continue
            val id = entry.getString("case_id")
            require(id.matches(Regex("[a-z0-9_-]+")))
            val asset = entry.optString("asset")
            val source =
                if (asset.isEmpty()) File(entry.getString("source_path")).canonicalFile else null
            require(source == null || source.canonicalPath.startsWith(inputRoot.canonicalPath + File.separator)) {
                "Diagnostic corpus input escaped the isolated fixture directory."
            }
            val file = source ?: File(inputRoot, "$id.jpg")
            if (source == null) {
                context.assets.open(asset)
                    .use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            fun open(): InputStream = file.inputStream()
            val sourceBefore = sha(open())
            val sourceFileLength = file?.length()
            val exif = open().use { ExifInterface(it) }
            val decodeStarted = SystemClock.elapsedRealtimeNanos()
            val bitmap =
                open().use { BitmapFactory.decodeStream(it) } ?: error("DECODE_FAILED: $id")
            val decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStarted) / 1e6
            try {
                val pixelBefore = pixelSha(bitmap)
                val q = entry.optJSONArray("quad")
                fun point(index: Int) =
                    q!!.getJSONArray(index).let { PointD(it.getDouble(0), it.getDouble(1)) }

                val quad =
                    if (q == null) null else NormalizedQuad(point(0), point(1), point(2), point(3))
                val orientation = entry.optInt("orientation_degrees", exif.rotationDegrees)
                val detectionConfidence =
                    if (entry.has("detection_confidence") && !entry.isNull("detection_confidence")) {
                        entry.getDouble("detection_confidence").toFloat()
                    } else null
                val quadSource = when (entry.optString("quad_source")) {
                    "PERSISTED_SOURCEORIGINAL_PAGE_METADATA", "PERSISTED" -> CurvatureQuadSource.PERSISTED
                    "FALLBACK" -> CurvatureQuadSource.FALLBACK
                    else -> CurvatureQuadSource.MISSING
                }
                val request = CurvatureAnalysisRequest(
                    source = bitmap,
                    quad = quad,
                    orientationDegrees = orientation,
                    outputWidth = entry.optInt("rectified_output_width", 0),
                    outputHeight = entry.optInt("rectified_output_height", 0),
                    documentType = entry.optString("document_type").takeIf { it.isNotEmpty() },
                    spineHint = if (entry.has("spine_hint") && !entry.isNull("spine_hint")) entry.getDouble(
                        "spine_hint"
                    ).toFloat() else null,
                    detectionConfidence = detectionConfidence,
                    maxAnalysisDimension = analysisDimensionOverride
                        ?: entry.optInt(
                            "max_analysis_dimension",
                            ProjectionCurvatureAnalyzer.DEFAULT_ANALYSIS_DIMENSION
                        ),
                    quadSource = quadSource,
                )
                val durations = mutableListOf<Double>()
                val heartbeat = mutableListOf<Double>()
                val memory = JSONArray()
                val analyzer = ProjectionCurvatureAnalyzer()
                var result = analyzer.analyze(request)
                repeat(21) { iteration ->
                    val heapBefore = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                    val nativeBefore = Debug.getNativeHeapAllocatedSize()
                    val started = SystemClock.elapsedRealtimeNanos()
                    val completion = async(Dispatchers.Default) { analyzer.analyze(request) }
                    val latch = CountDownLatch(1)
                    val posted = SystemClock.elapsedRealtimeNanos()
                    Handler(Looper.getMainLooper()).post {
                        heartbeat += (SystemClock.elapsedRealtimeNanos() - posted) / 1e6
                        latch.countDown()
                    }
                    result = completion.await()
                    durations += (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                    assertTrue("Main looper did not respond", latch.await(5, TimeUnit.SECONDS))
                    val pss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                    memory.put(
                        JSONObject().put("iteration", iteration)
                            .put("heap_before_bytes", heapBefore)
                            .put(
                                "heap_after_bytes",
                                Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })
                            .put("native_before_bytes", nativeBefore)
                            .put("native_after_bytes", Debug.getNativeHeapAllocatedSize())
                            .put("process_pss_kib", pss.totalPss)
                    )
                }

                val expected = entry.optJSONArray("expected_decisions") ?: JSONArray()
                val acceptedText = result.evidence.lines
                    .filter { it.evidenceType == CurvatureEvidenceType.TEXT_BASELINE && it.acceptedForDecision }
                    .map {
                        listOf(
                            it.curvatureCoefficient,
                            it.fitResidual,
                            it.coverageRatio,
                            it.supportingPointCount
                        )
                    }
                val decisionMatches =
                    expected.length() == 0 || contains(expected, result.decision.name)
                if (!decisionMatches) decisionFailures +=
                    "$id expected=$expected actual=${result.decision} " +
                            "confidence=${result.confidence} reasons=${result.rejectionReasons} acceptedText=$acceptedText"
                val sourceAfter = sha(open())
                assertEquals("Source bytes changed for $id", sourceBefore, sourceAfter)
                assertEquals("Decoded source pixels changed for $id", pixelBefore, pixelSha(bitmap))
                if (file != null) {
                    assertEquals(
                        "Source file size disagrees with metadata for $id",
                        entry.optLong("source_file_size", sourceFileLength!!),
                        sourceFileLength
                    )
                    val metadataSha = entry.optString("source_sha256").takeIf { it.isNotEmpty() }
                    if (metadataSha != null) assertEquals(
                        "Stored source hash disagrees for $id",
                        metadataSha,
                        sourceBefore
                    )
                }

                val geometry = result.geometry
                assertEquals(
                    "Source dimensions disagree for $id",
                    bitmap.width,
                    geometry.sourceImageDimensions.width
                )
                assertEquals(
                    "Source dimensions disagree for $id",
                    bitmap.height,
                    geometry.sourceImageDimensions.height
                )
                assertEquals(
                    "Effective orientation was not applied once for $id",
                    orientation,
                    geometry.orientationAppliedDegrees
                )
                assertTrue(
                    "Invalid transform mapping was recorded for $id",
                    geometry.transformMatrix == null || geometry.transformMatrix.size == 9
                )
                assertEquals(
                    "Protected document/A-B files changed for $id",
                    before,
                    protectedFiles()
                )

                val warm = durations.drop(1).sorted()
                fun percentile(p: Double) = warm[(ceil(p * warm.size).toInt() - 1).coerceAtLeast(0)]
                val evidenceJson = JSONArray()
                result.evidence.lines.forEach { line ->
                    evidenceJson.put(
                        JSONObject().put("evidence_type", line.evidenceType.name)
                            .put("supporting_point_count", line.supportingPointCount)
                            .put(
                                "support_points",
                                JSONArray().also { points ->
                                    line.supportPoints.forEach {
                                        points.put(JSONObject().put("x", it.x).put("y", it.y))
                                    }
                                })
                            .put("fit_residual", line.fitResidual ?: JSONObject.NULL)
                            .put(
                                "curvature_coefficient",
                                line.curvatureCoefficient ?: JSONObject.NULL
                            )
                            .put("coverage_ratio", line.coverageRatio)
                            .put("confidence", line.confidence)
                            .put("accepted_for_decision", line.acceptedForDecision)
                            .put("rejection_reason", line.rejectionReason ?: JSONObject.NULL)
                    )
                }
                val report = JSONObject()
                    .put("case_id", id)
                    .put("source_path", file?.canonicalPath ?: "debug_asset:$asset")
                    .put("source_sha256_before", sourceBefore)
                    .put("source_sha256_after", sourceAfter)
                    .put("source_file_size_bytes", sourceFileLength ?: JSONObject.NULL)
                    .put(
                        "source_image_dimensions",
                        dimensionsJson(
                            geometry.sourceImageDimensions.width,
                            geometry.sourceImageDimensions.height
                        )
                    )
                    .put(
                        "oriented_image_dimensions",
                        dimensionsJson(
                            geometry.orientedImageDimensions.width,
                            geometry.orientedImageDimensions.height
                        )
                    )
                    .put(
                        "analysis_image_dimensions",
                        dimensionsJson(
                            geometry.analysisImageDimensions.width,
                            geometry.analysisImageDimensions.height
                        )
                    )
                    .put(
                        "rectified_page_dimensions",
                        geometry.rectifiedPageDimensions?.let {
                            dimensionsJson(
                                it.width,
                                it.height
                            )
                        } ?: JSONObject.NULL)
                    .put("quad_source", geometry.quadSource.name)
                    .put(
                        "quad_before_orientation",
                        pointsJson(geometry.quadCoordinatesBeforeOrientation)
                    )
                    .put(
                        "quad_after_orientation",
                        pointsJson(geometry.quadCoordinatesAfterOrientation)
                    )
                    .put("source_to_analysis_scale_x", geometry.sourceToAnalysisScaleX)
                    .put("source_to_analysis_scale_y", geometry.sourceToAnalysisScaleY)
                    .put("effective_orientation_degrees", orientation)
                    .put("orientation_applied_degrees", geometry.orientationAppliedDegrees)
                    .put("exif_orientation_degrees", exif.rotationDegrees)
                    .put("exif_mirrored", exif.isFlipped)
                    .put("transform_mapping_id", geometry.transformMappingId)
                    .put(
                        "transform_matrix",
                        JSONArray(geometry.transformMatrix ?: emptyList<Double>())
                    )
                    .put("capture_tier_metadata", entry.optString("capture_tier", "NOT_RECORDED"))
                    .put(
                        "original_capture_tier_metadata",
                        entry.optString("original_capture_tier", "NOT_RECORDED")
                    )
                    .put(
                        "requested_capture_tier",
                        entry.optString("requested_capture_tier").takeIf { it.isNotEmpty() }
                            ?: JSONObject.NULL)
                    .put(
                        "high_quality_capture_metadata",
                        entry.opt("high_quality_capture") ?: JSONObject.NULL
                    )
                    .put(
                        "tier_comparison_status",
                        if (entry.has("requested_capture_tier") && !entry.isNull("requested_capture_tier")) "VERIFIED" else "UNVERIFIED"
                    )
                    .put(
                        "tier_comparison_claim_allowed",
                        entry.has("requested_capture_tier") && !entry.isNull("requested_capture_tier")
                    )
                    .put(
                        "metadata_provenance",
                        entry.optJSONObject("metadata_provenance") ?: JSONObject()
                    )
                    .put("detection_confidence_input", detectionConfidence ?: JSONObject.NULL)
                    .put("decision", result.decision.name)
                    .put("confidence", result.confidence.name)
                    .put("evidence_type_counts", evidenceTypeCounts(result))
                    .put("eligible_text_line_count", result.evidence.eligibleTextLineCount)
                    .put(
                        "median_absolute_curvature",
                        result.evidence.medianAbsoluteCurvature ?: JSONObject.NULL
                    )
                    .put(
                        "median_fit_residual",
                        result.evidence.medianFitResidual ?: JSONObject.NULL
                    )
                    .put("line_evidence", evidenceJson)
                    .put("rejection_reasons", JSONArray(result.rejectionReasons))
                    .put("decode_ms", decodeMs)
                    .put("first_call_ms", durations.first())
                    .put("warm_p50_ms", percentile(0.5))
                    .put("warm_p95_ms", percentile(0.95))
                    .put("durations_ms", JSONArray(durations))
                    .put("memory", memory)
                    .put("main_looper_max_latency_ms", heartbeat.maxOrNull() ?: JSONObject.NULL)
                    .put("ui_frame_jank_measured", false)
                    .put("candidate_created", false)
                    .put("baseline_changed", false)
                    .put("active_variant_changed", false)
                    .put("source_changed", false)
                    .put("expected_decisions", expected)
                    .put("decision_matches_expectation", decisionMatches)
                    .put(
                        "expected_ambiguity",
                        entry.optString("expected_ambiguity").takeIf { it.isNotEmpty() }
                            ?: JSONObject.NULL)
                File(output, "$id.json").writeText(report.toString(2))
                File(output, "$id-evidence.svg").writeText(evidencePlot(result))
                reports.put(report)
            } finally {
                bitmap.recycle()
            }
        }
        assertEquals("Protected stores changed after corpus run", before, protectedFiles())
        val tierComparisonControlled = (0 until reports.length()).all {
            reports.getJSONObject(it).optBoolean("tier_comparison_claim_allowed")
        }
        File(output, "summary.json").writeText(
            JSONObject().put("device", android.os.Build.MODEL)
                .put("android", android.os.Build.VERSION.RELEASE)
                .put("corpus_status", manifest.optString("status", "UNKNOWN"))
                .put(
                    "tier_comparison_status",
                    if (tierComparisonControlled) "VERIFIED" else "UNVERIFIED"
                )
                .put("tier_comparison_claim_allowed", tierComparisonControlled)
                .put("decision_failures", JSONArray(decisionFailures))
                .put("reports", reports)
                .put("protected_files_before", JSONObject(before))
                .put("protected_files_after", JSONObject(protectedFiles())).toString(2)
        )
        assertTrue(
            "Corpus decision gate failed: ${decisionFailures.joinToString(" | ")}",
            decisionFailures.isEmpty()
        )
        cleanupInputRootOnly()
    }

    @Test
    fun isolatedCleanupCannotTargetProductionRoot() {
        val production = File(context.filesDir, "scanned_pages").canonicalFile
        assertFalse(inputRoot.canonicalFile.toPath().startsWith(production.toPath()))
        assertFalse(output.canonicalFile.toPath().startsWith(production.toPath()))
    }

    @Test
    fun syntheticDiagnosticInputsCanRunTwiceWithIdenticalHashes() {
        check(OpenCVLoader.initLocal())
        prepareIsolatedInputs()
        val fixture = File(inputRoot, "repeatability.jpg")
        context.assets.open("uncropped/img01.jpg")
            .use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val first = sha(fixture.inputStream())
        val bitmap =
            fixture.inputStream().use { BitmapFactory.decodeStream(it) } ?: error("DECODE_FAILED")
        val request = CurvatureAnalysisRequest(
            source = bitmap,
            quad = null,
            orientationDegrees = 0,
            maxAnalysisDimension = 2000
        )
        val analyzer = ProjectionCurvatureAnalyzer()
        val result1 = analyzer.analyze(request)
        val second = sha(fixture.inputStream())
        val result2 = analyzer.analyze(request)
        assertEquals(first, second)
        assertEquals(result1.decision, result2.decision)
        assertEquals(
            result1.evidence.lines.map { it.curvatureCoefficient },
            result2.evidence.lines.map { it.curvatureCoefficient })
        bitmap.recycle()
        cleanupIsolatedInputs()
        assertFalse(inputRoot.exists())
    }

    private fun prepareIsolatedInputs() {
        cleanupIsolatedInputs()
        require(!inputRoot.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        inputRoot.mkdirs()
    }

    private fun cleanupIsolatedInputs() {
        val roots = listOf(inputRoot, output)
        roots.forEach { root ->
            val canonical = root.canonicalFile
            require(canonical.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
            require(!canonical.toPath().startsWith(context.filesDir.canonicalFile.toPath()))
            if (canonical.exists()) canonical.deleteRecursively()
        }
    }

    private fun cleanupInputRootOnly() {
        val canonical = inputRoot.canonicalFile
        require(canonical.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        require(!canonical.toPath().startsWith(context.filesDir.canonicalFile.toPath()))
        if (canonical.exists()) canonical.deleteRecursively()
    }

    private fun contains(array: JSONArray, value: String): Boolean =
        (0 until array.length()).any { array.optString(it) == value }

    private fun dimensionsJson(width: Int, height: Int) =
        JSONObject().put("width", width).put("height", height)

    private fun pointsJson(points: List<PointD>?): JSONArray = JSONArray().also { out ->
        points?.forEach { out.put(JSONObject().put("x", it.x).put("y", it.y)) }
    }

    private fun evidenceTypeCounts(result: CurvatureAnalysisResult): JSONObject {
        val counts = JSONObject()
        CurvatureEvidenceType.entries.forEach { type ->
            counts.put(type.name, result.evidence.lines.count { it.evidenceType == type })
        }
        return counts
    }

    private fun evidencePlot(result: CurvatureAnalysisResult): String {
        val size = result.geometry.rectifiedPageDimensions
        val width = size?.width ?: 1
        val height = size?.height ?: 1
        val colors = mapOf(
            CurvatureEvidenceType.TEXT_BASELINE to "#167d3f",
            CurvatureEvidenceType.TABLE_LINE to "#d97706",
            CurvatureEvidenceType.GRAPHIC_EDGE to "#2563eb",
            CurvatureEvidenceType.HANDWRITING to "#9333ea",
            CurvatureEvidenceType.PAGE_BOUNDARY to "#6b7280",
            CurvatureEvidenceType.UNKNOWN to "#dc2626",
        )
        val elements = buildString {
            result.evidence.lines.forEach { line ->
                val points = line.supportPoints
                if (points.size < 2) return@forEach
                val color = colors.getValue(line.evidenceType)
                val coords = points.joinToString(" ") { "${it.x * width},${it.y * height}" }
                append("<polyline points=\"$coords\" fill=\"none\" stroke=\"$color\" stroke-width=2\"/>")
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\"><title>Rectified analysis evidence; no candidate image</title>$elements</svg>"
    }

    private fun isolatedPreservedOriginalManifest(): JSONObject {
        val fixtureRoot = File(context.cacheDir, "curvature-fixtures")
        val metadataFile = File(fixtureRoot, "document.json")
        require(metadataFile.isFile) { "Isolated corpus metadata is unavailable: ${metadataFile.path}" }
        val metadata = JSONObject(metadataFile.readText())
        val pages = metadata.optJSONArray("pages") ?: error("Page metadata is unavailable.")
        require(pages.length() == 7) { "Expected seven captured page records; found ${pages.length()}." }
        val labels = listOf(
            "flat_sheet",
            "tilted_flat_sheet",
            "curved_book_page",
            "open_book_spine",
            "wrinkled_page",
            "low_text_page",
            "table_page"
        )
        // Real-corpus labels are observational; no expected decision is forced.
        val expected = List(7) { emptyList<String>() }
        val ambiguity = mapOf(
            "curved_book_page" to "No independently annotated text baselines or persisted numeric quad confidence; insufficient evidence is an acceptable conservative result.",
            "open_book_spine" to "No independently annotated per-page text baselines or persisted numeric quad confidence; insufficient evidence is an acceptable conservative result.",
            "wrinkled_page" to "Wrinkles do not guarantee measurable baseline curvature; insufficient evidence is acceptable when text support is ambiguous.",
        )
        val cases = JSONArray()
        val ordered = (0 until pages.length()).map { pages.getJSONObject(it) }
            .sortedBy { it.optString("id").toLong() }
        ordered.forEachIndexed { index, page ->
            val id = page.getString("id")
            val sourceRelative = page.getString("sourceFile")
            require(sourceRelative == "originals/$id.jpg") { "SourceOriginal mapping mismatch for $id." }
            val fixtureSource = File(fixtureRoot, sourceRelative.removePrefix("originals/"))
            require(fixtureSource.isFile && fixtureSource.length() > 0) { "Isolated SourceOriginal missing for case ${labels[index]}. No processed-image fallback is allowed." }
            val source = File(inputRoot, "$id.jpg")
            fixtureSource.inputStream()
                .use { input -> source.outputStream().use { input.copyTo(it) } }
            require(
                page.optLong(
                    "sourceFileSize",
                    -1L
                ) == source.length()
            ) { "SourceOriginal size disagrees with document metadata for $id." }
            val q = page.optJSONObject("quad")
                ?: error("Persisted source quad is missing for ${labels[index]}.")
            val quad = JSONArray()
            listOf("topLeft", "topRight", "bottomRight", "bottomLeft").forEach { corner ->
                val point = q.getJSONObject(corner)
                quad.put(JSONArray().put(point.getDouble("x")).put(point.getDouble("y")))
            }
            cases.put(
                JSONObject().put("case_id", labels[index])
                    .put("source_path", source.absolutePath)
                    .put("source_relative_path", sourceRelative)
                    .put("source_file_size", source.length())
                    .put("source_sha256", page.optString("sourceSha256"))
                    .put("source_width_metadata", page.optInt("capturedWidth", 0))
                    .put("source_height_metadata", page.optInt("capturedHeight", 0))
                    .put("quad_source", "PERSISTED_SOURCEORIGINAL_PAGE_METADATA")
                    .put("quad", quad)
                    .put("orientation_degrees", page.optInt("baseRotationDegrees", 0))
                    .put("orientation_source", "PERSISTED_EFFECTIVE_BASE_ROTATION")
                    .put("capture_tier", page.optString("captureTier", "NOT_RECORDED"))
                    .put(
                        "original_capture_tier",
                        page.optString("originalCaptureTier", "NOT_RECORDED")
                    )
                    .put("requested_capture_tier", JSONObject.NULL)
                    .put("high_quality_capture", JSONObject.NULL)
                    .put("capture_mode", page.optString("captureMode", "NOT_RECORDED"))
                    .put("camera_id", page.optString("cameraId", "NOT_RECORDED"))
                    .put("capture_id", id)
                    .put("capture_order", index + 1)
                    .put(
                        "metadata_provenance", JSONObject()
                            .put("capture_tier", "observed")
                            .put("original_capture_tier", "observed")
                            .put("requested_capture_tier", "unavailable")
                            .put("source_dimensions", "observed")
                            .put("source_sha256", "observed")
                            .put("quad_source", "observed")
                            .put("quad_confidence", "unavailable")
                            .put("effective_orientation", "observed")
                            .put("tier_comparison", "unverified")
                    )
                    .put("expected_decisions", JSONArray(expected[index]))
                    .put("expected_ambiguity", ambiguity[labels[index]] ?: JSONObject.NULL)
            )
        }
        return JSONObject().put("schema_version", 2)
            .put("status", "USER_CAPTURED_SOURCEORIGINALS_TIER_UNVERIFIED")
            .put("device", android.os.Build.MODEL)
            .put("android", android.os.Build.VERSION.RELEASE)
            .put("cases", cases)
    }
}

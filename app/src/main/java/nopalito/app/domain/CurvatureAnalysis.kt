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

import android.graphics.Bitmap
import nopalito.app.data.NormalizedQuad
import nopalito.app.data.PointD
import nopalito.imageprocessing.Point
import nopalito.imageprocessing.getPerspectiveTransform
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class CurvatureConfidence { LOW, MEDIUM, HIGH }

enum class CurvatureDecision { NO_CHANGE, CURVATURE_DETECTED, INSUFFICIENT_EVIDENCE, REJECTED_UNSAFE }

enum class CurvatureQuadSource { PERSISTED, FALLBACK, MISSING }

enum class CurvatureEvidenceType {
    TEXT_BASELINE,
    TABLE_LINE,
    GRAPHIC_EDGE,
    HANDWRITING,
    PAGE_BOUNDARY,
    UNKNOWN,
}

data class CurvatureImageDimensions(val width: Int, val height: Int)

data class CurvatureLineEvidence(
    val evidenceType: CurvatureEvidenceType,
    val supportPoints: List<PointD>,
    val supportingPointCount: Int,
    val fitResidual: Double?,
    /** Signed quadratic sag as a fraction of rectified page height. */
    val curvatureCoefficient: Double?,
    val coverageRatio: Double,
    val confidence: Float,
    val acceptedForDecision: Boolean,
    val rejectionReason: String? = null,
)

data class CurvatureGeometryMetadata(
    val sourceImageDimensions: CurvatureImageDimensions,
    val orientedImageDimensions: CurvatureImageDimensions,
    val quadCoordinatesBeforeOrientation: List<PointD>?,
    val quadCoordinatesAfterOrientation: List<PointD>?,
    val analysisImageDimensions: CurvatureImageDimensions,
    val rectifiedPageDimensions: CurvatureImageDimensions?,
    val sourceToAnalysisScaleX: Double,
    val sourceToAnalysisScaleY: Double,
    val orientationAppliedDegrees: Int,
    val transformMatrix: List<Double>?,
    val transformMappingId: String,
    val quadSource: CurvatureQuadSource,
)

data class CurvatureEvidence(
    val lines: List<CurvatureLineEvidence>,
    val eligibleTextLineCount: Int,
    val medianAbsoluteCurvature: Double?,
    val medianFitResidual: Double?,
) {
    val sampleCount: Int get() = lines.sumOf { it.supportingPointCount }
    val linearResidual: Double get() = medianFitResidual ?: 0.0
    val quadraticResidual: Double get() = medianFitResidual ?: 0.0
    val curvatureScore: Double get() = medianAbsoluteCurvature ?: 0.0
}

data class CurvatureAnalysisResult(
    val decision: CurvatureDecision,
    val confidence: CurvatureConfidence,
    val evidence: CurvatureEvidence,
    val geometry: CurvatureGeometryMetadata,
    val rejectionReasons: List<String> = emptyList(),
)

/**
 * Read-only analysis request. [orientationDegrees] is the already-resolved
 * effective orientation from the capture metadata; the analyzer applies it
 * once. [quad] remains in the source bitmap's normalized coordinate space.
 */
data class CurvatureAnalysisRequest(
    val source: Bitmap,
    val quad: NormalizedQuad?,
    val orientationDegrees: Int,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val documentType: String? = null,
    val spineHint: Float? = null,
    val detectionConfidence: Float? = null,
    val maxAnalysisDimension: Int = 1600,
    val quadSource: CurvatureQuadSource = if (quad == null) CurvatureQuadSource.MISSING else CurvatureQuadSource.PERSISTED,
)

interface CurvatureAnalyzer {
    fun analyze(request: CurvatureAnalysisRequest): CurvatureAnalysisResult
}

/**
 * A read-only classical CV diagnostic. It rectifies only temporary reduced
 * copies and returns measurements; it never modifies the source or emits an
 * image candidate. No call site in the capture or processing pipeline uses it.
 */
class ProjectionCurvatureAnalyzer : CurvatureAnalyzer {
    override fun analyze(request: CurvatureAnalysisRequest): CurvatureAnalysisResult {
        val sourceSize = CurvatureImageDimensions(request.source.width, request.source.height)
        val degrees = normalizeDegrees(request.orientationDegrees)
        val orientedSize = if (degrees % 180 == 0) sourceSize else CurvatureImageDimensions(
            sourceSize.height,
            sourceSize.width
        )
        val initialGeometry = CurvatureGeometryMetadata(
            sourceImageDimensions = sourceSize,
            orientedImageDimensions = orientedSize,
            quadCoordinatesBeforeOrientation = request.quad?.points(),
            quadCoordinatesAfterOrientation = request.quad?.points()
                ?.let { orientQuad(it, degrees) },
            analysisImageDimensions = orientedSize,
            rectifiedPageDimensions = null,
            sourceToAnalysisScaleX = 1.0,
            sourceToAnalysisScaleY = 1.0,
            orientationAppliedDegrees = degrees,
            transformMatrix = null,
            transformMappingId = "UNAVAILABLE",
            quadSource = request.quadSource,
        )
        if (request.source.width <= 0 || request.source.height <= 0 || degrees !in setOf(
                0,
                90,
                180,
                270
            )
        ) {
            return result(
                CurvatureDecision.REJECTED_UNSAFE,
                CurvatureConfidence.LOW,
                initialGeometry,
                "INVALID_SOURCE_OR_ORIENTATION"
            )
        }
        if (request.maxAnalysisDimension !in MIN_ANALYSIS_DIMENSION..MAX_ANALYSIS_DIMENSION) {
            return result(
                CurvatureDecision.REJECTED_UNSAFE,
                CurvatureConfidence.LOW,
                initialGeometry,
                "ANALYSIS_DIMENSION_OUT_OF_RANGE"
            )
        }
        if (request.detectionConfidence != null && (!request.detectionConfidence.isFinite() || request.detectionConfidence !in 0.0f..1.0f)) {
            return result(
                CurvatureDecision.REJECTED_UNSAFE,
                CurvatureConfidence.LOW,
                initialGeometry,
                "INVALID_DETECTION_CONFIDENCE"
            )
        }

        val quadPoints = request.quad?.points()
        if (quadPoints != null && !validQuad(quadPoints)) {
            return result(
                CurvatureDecision.REJECTED_UNSAFE,
                CurvatureConfidence.LOW,
                initialGeometry,
                "INVALID_PERSISTED_QUAD"
            )
        }
        if (request.quad == null && request.quadSource != CurvatureQuadSource.MISSING) {
            return result(
                CurvatureDecision.REJECTED_UNSAFE,
                CurvatureConfidence.LOW,
                initialGeometry,
                "QUAD_SOURCE_WITHOUT_QUAD"
            )
        }

        val source = Mat()
        var oriented = Mat()
        var analysis = Mat()
        var warped = Mat()
        var transform: Mat? = null
        try {
            Utils.bitmapToMat(request.source, source)
            rotateMat(source, oriented, degrees)
            val analysisScale = min(
                1.0,
                request.maxAnalysisDimension.toDouble() / max(oriented.cols(), oriented.rows())
            )
            if (analysisScale < 1.0) {
                Imgproc.resize(
                    oriented,
                    analysis,
                    Size(
                        max(1, (oriented.cols() * analysisScale).toInt()).toDouble(),
                        max(1, (oriented.rows() * analysisScale).toInt()).toDouble(),
                    ),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
            } else {
                oriented.copyTo(analysis)
            }
            val scaleX = analysis.cols().toDouble() / oriented.cols()
            val scaleY = analysis.rows().toDouble() / oriented.rows()
            val before = quadPoints
            val after = before?.let { orientQuad(it, degrees) }
            val analysisQuad = after?.map { PointD(it.x * analysis.cols(), it.y * analysis.rows()) }
            val quadSource = if (before == null) CurvatureQuadSource.MISSING else request.quadSource
            val fallback = before == null || request.quadSource == CurvatureQuadSource.FALLBACK
            val targetSize = if (analysisQuad == null) {
                CurvatureImageDimensions(analysis.cols(), analysis.rows())
            } else {
                rectifiedSize(
                    analysisQuad,
                    request.outputWidth,
                    request.outputHeight,
                    request.maxAnalysisDimension
                )
            }
            val matrixValues: List<Double>
            if (analysisQuad == null) {
                analysis.copyTo(warped)
                transform = Mat.eye(3, 3, CvType.CV_64F)
                matrixValues = matrixValues(transform)
            } else {
                val perspective = getPerspectiveTransform(
                    analysisQuad.map { Point(it.x, it.y) },
                    listOf(
                        Point(0.0, 0.0),
                        Point((targetSize.width - 1).toDouble(), 0.0),
                        Point(
                            (targetSize.width - 1).toDouble(),
                            (targetSize.height - 1).toDouble()
                        ),
                        Point(0.0, (targetSize.height - 1).toDouble()),
                    ),
                )
                transform = perspective
                if (perspective.empty() || !matrixValues(perspective).all { it.isFinite() }) {
                    return result(
                        CurvatureDecision.REJECTED_UNSAFE,
                        CurvatureConfidence.LOW,
                        initialGeometry,
                        "PERSPECTIVE_TRANSFORM_FAILED"
                    )
                }
                Imgproc.warpPerspective(
                    analysis,
                    warped,
                    perspective,
                    Size(targetSize.width.toDouble(), targetSize.height.toDouble()),
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_REPLICATE,
                    Scalar.all(255.0),
                )
                matrixValues = matrixValues(transform)
            }

            val geometry = CurvatureGeometryMetadata(
                sourceImageDimensions = sourceSize,
                orientedImageDimensions = orientedSize,
                quadCoordinatesBeforeOrientation = before,
                quadCoordinatesAfterOrientation = after,
                analysisImageDimensions = CurvatureImageDimensions(
                    analysis.cols(),
                    analysis.rows()
                ),
                rectifiedPageDimensions = targetSize,
                sourceToAnalysisScaleX = scaleX,
                sourceToAnalysisScaleY = scaleY,
                orientationAppliedDegrees = degrees,
                transformMatrix = matrixValues,
                transformMappingId = if (fallback) "IDENTITY_FULL_IMAGE_FALLBACK" else "PERSPECTIVE_ANALYSIS_TO_RECTIFIED_PAGE",
                quadSource = quadSource,
            )
            val detection = PageEvidenceDetector.detect(warped, request.documentType)
            val evidence = detection.evidence
            val decision = decide(evidence, fallback, request)
            val confidence = when {
                decision == CurvatureDecision.REJECTED_UNSAFE -> CurvatureConfidence.LOW
                fallback || request.detectionConfidence == null -> CurvatureConfidence.LOW
                evidence.eligibleTextLineCount >= 5 && request.detectionConfidence >= 0.8f -> CurvatureConfidence.HIGH
                evidence.eligibleTextLineCount >= MIN_TEXT_LINES -> CurvatureConfidence.MEDIUM
                else -> CurvatureConfidence.LOW
            }
            return CurvatureAnalysisResult(
                decision,
                confidence,
                evidence,
                geometry,
                detection.rejectionReasons
            )
        } catch (error: RuntimeException) {
            return CurvatureAnalysisResult(
                CurvatureDecision.REJECTED_UNSAFE,
                CurvatureConfidence.LOW,
                CurvatureEvidence(emptyList(), 0, null, null),
                initialGeometry,
                listOf("ANALYSIS_FAILED:${error.javaClass.simpleName}:${error.message.orEmpty()}"),
            )
        } finally {
            source.release()
            oriented.release()
            analysis.release()
            warped.release()
            transform?.release()
        }
    }

    private fun decide(
        evidence: CurvatureEvidence,
        fallback: Boolean,
        request: CurvatureAnalysisRequest,
    ): CurvatureDecision {
        if (fallback) return CurvatureDecision.INSUFFICIENT_EVIDENCE
        if (request.documentType.equals(
                "HANDWRITING",
                ignoreCase = true
            )
        ) return CurvatureDecision.INSUFFICIENT_EVIDENCE
        if (request.detectionConfidence != null && request.detectionConfidence < MIN_QUAD_CONFIDENCE) {
            return CurvatureDecision.REJECTED_UNSAFE
        }
        val lines =
            evidence.lines.filter { it.evidenceType == CurvatureEvidenceType.TEXT_BASELINE && it.acceptedForDecision }
        if (lines.size < MIN_TEXT_LINES) return CurvatureDecision.INSUFFICIENT_EVIDENCE
        if (request.documentType.equals(
                "ILLUSTRATION_HEAVY",
                ignoreCase = true
            ) && lines.size < ILLUSTRATION_MIN_TEXT_LINES
        ) {
            return CurvatureDecision.INSUFFICIENT_EVIDENCE
        }
        val coefficients = lines.mapNotNull { it.curvatureCoefficient }
        if (coefficients.size < MIN_TEXT_LINES) return CurvatureDecision.INSUFFICIENT_EVIDENCE
        val median = median(coefficients.map(::abs))
        if (median < FLAT_CURVATURE_LIMIT) return CurvatureDecision.NO_CHANGE
        if (median < CURVED_CURVATURE_LIMIT) return CurvatureDecision.INSUFFICIENT_EVIDENCE
        val signs = coefficients.map { if (it < 0.0) -1 else 1 }
        val dominantShare =
            max(signs.count { it < 0 }, signs.count { it > 0 }).toDouble() / signs.size
        if (dominantShare < CURVATURE_SIGN_CONSISTENCY) return CurvatureDecision.REJECTED_UNSAFE
        val magnitudes = coefficients.map(::abs)
        val spread = median(magnitudes.map { abs(it - median) }) / median.coerceAtLeast(1e-9)
        if (spread > MAX_CURVATURE_SPREAD) return CurvatureDecision.REJECTED_UNSAFE
        if (request.detectionConfidence == null) return CurvatureDecision.INSUFFICIENT_EVIDENCE
        return CurvatureDecision.CURVATURE_DETECTED
    }

    private fun result(
        decision: CurvatureDecision,
        confidence: CurvatureConfidence,
        geometry: CurvatureGeometryMetadata,
        reason: String,
    ) = CurvatureAnalysisResult(
        decision,
        confidence,
        CurvatureEvidence(emptyList(), 0, null, null),
        geometry,
        listOf(reason),
    )

    private fun rectifiedSize(
        points: List<PointD>,
        requestedWidth: Int,
        requestedHeight: Int,
        maxDimension: Int
    ): CurvatureImageDimensions {
        val calculatedWidth =
            (distance(points[0], points[1]) + distance(points[3], points[2])) / 2.0
        val calculatedHeight =
            (distance(points[0], points[3]) + distance(points[1], points[2])) / 2.0
        val width = requestedWidth.takeIf { it > 0 }?.toDouble() ?: calculatedWidth
        val height = requestedHeight.takeIf { it > 0 }?.toDouble() ?: calculatedHeight
        if (!width.isFinite() || !height.isFinite() || width < MIN_PAGE_EDGE || height < MIN_PAGE_EDGE) {
            throw IllegalArgumentException("Invalid rectified page dimensions")
        }
        val scale = min(1.0, maxDimension.toDouble() / max(width, height))
        return CurvatureImageDimensions(
            max(MIN_PAGE_EDGE, (width * scale).toInt()),
            max(MIN_PAGE_EDGE, (height * scale).toInt())
        )
    }

    private fun validQuad(points: List<PointD>): Boolean {
        if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) return false
        val crosses = points.indices.map { i ->
            val a = points[i]
            val b = points[(i + 1) % 4]
            val c = points[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        val area = abs(points.indices.sumOf { i ->
            val a = points[i]
            val b = points[(i + 1) % 4]
            a.x * b.y - b.x * a.y
        }) / 2.0
        return area >= MIN_QUAD_AREA && crosses.all { it > MIN_QUAD_CROSS }
    }

    private fun rotateMat(source: Mat, output: Mat, degrees: Int) {
        when (degrees) {
            0 -> source.copyTo(output)
            90 -> Core.rotate(source, output, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(source, output, Core.ROTATE_180)
            270 -> Core.rotate(source, output, Core.ROTATE_90_COUNTERCLOCKWISE)
        }
    }

    private fun matrixValues(matrix: Mat): List<Double> {
        val values = DoubleArray(9)
        matrix.get(0, 0, values)
        return values.toList()
    }

    private fun normalizeDegrees(degrees: Int) = ((degrees % 360) + 360) % 360

    private fun orientNormalized(point: PointD, degrees: Int): PointD = when (degrees) {
        90 -> PointD(1.0 - point.y, point.x)
        180 -> PointD(1.0 - point.x, 1.0 - point.y)
        270 -> PointD(point.y, 1.0 - point.x)
        else -> point
    }

    private fun orientQuad(points: List<PointD>, degrees: Int): List<PointD> {
        val cornerOrder = when (degrees) {
            90 -> listOf(3, 0, 1, 2)
            180 -> listOf(2, 3, 0, 1)
            270 -> listOf(1, 2, 3, 0)
            else -> listOf(0, 1, 2, 3)
        }
        return cornerOrder.map { orientNormalized(points[it], degrees) }
    }

    private fun distance(a: PointD, b: PointD) = hypot(a.x - b.x, a.y - b.y)

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun NormalizedQuad.points() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    companion object {
        const val MIN_ANALYSIS_DIMENSION = 480
        const val MAX_ANALYSIS_DIMENSION = 6000
        const val DEFAULT_ANALYSIS_DIMENSION = 1600
        private const val MIN_PAGE_EDGE = 64
        private const val MIN_QUAD_AREA = 0.05
        private const val MIN_QUAD_CROSS = 1e-5
        private const val MIN_QUAD_CONFIDENCE = 0.35f
        private const val MIN_TEXT_LINES = 8
        private const val FLAT_CURVATURE_LIMIT = 0.008
        private const val CURVED_CURVATURE_LIMIT = 0.015
        private const val CURVATURE_SIGN_CONSISTENCY = 0.75
        private const val MAX_CURVATURE_SPREAD = 0.75
        private const val ILLUSTRATION_MIN_TEXT_LINES = 12
    }
}

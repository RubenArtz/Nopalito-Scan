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

import nopalito.app.data.PointD
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Evidence extraction on an already oriented and perspective-rectified analysis copy. */
internal object PageEvidenceDetector {
    private data class Component(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val area: Int
    ) {
        val baseline: Double get() = (y + height).toDouble()
        val centerX: Double get() = x + width / 2.0
    }

    data class Detection(val evidence: CurvatureEvidence, val rejectionReasons: List<String>)

    fun detect(rectified: Mat, documentType: String?): Detection {
        val width = rectified.cols()
        val height = rectified.rows()
        if (width < 64 || height < 64 || rectified.empty()) {
            return Detection(
                CurvatureEvidence(emptyList(), 0, null, null),
                listOf("RECTIFIED_PAGE_TOO_SMALL")
            )
        }
        val gray = Mat()
        val binary = Mat()
        val horizontalMask = Mat()
        val textBinary = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val edges = Mat()
        val lines = Mat()
        val opened = Mat()
        try {
            if (rectified.channels() == 1) rectified.copyTo(gray)
            else Imgproc.cvtColor(rectified, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, opened, Size(3.0, 3.0), 0.0)
            Imgproc.adaptiveThreshold(
                opened,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                31,
                11.0,
            )

            val horizontalKernelWidth = max(24, (width * TABLE_LINE_MIN_LENGTH).toInt())
            val horizontalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(horizontalKernelWidth.toDouble(), 1.0),
            )
            val verticalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(1.0, max(24, (height * TABLE_LINE_MIN_LENGTH).toInt()).toDouble()),
            )
            val verticalMask = Mat()
            val tableLines = mutableListOf<CurvatureLineEvidence>()
            try {
                Imgproc.morphologyEx(binary, horizontalMask, Imgproc.MORPH_OPEN, horizontalKernel)
                Imgproc.morphologyEx(binary, verticalMask, Imgproc.MORPH_OPEN, verticalKernel)
                val rowPixels = ByteArray(width)
                for (y in 0 until height) {
                    horizontalMask.get(y, 0, rowPixels)
                    val start = rowPixels.indexOfFirst { (it.toInt() and 255) > 0 }
                    val end = rowPixels.indexOfLast { (it.toInt() and 255) > 0 }
                    val coverage = if (start >= 0) (end - start + 1).toDouble() / width else 0.0
                    if (coverage >= TABLE_LINE_MIN_LENGTH) {
                        tableLines += CurvatureLineEvidence(
                            evidenceType = CurvatureEvidenceType.TABLE_LINE,
                            supportPoints = listOf(
                                PointD(
                                    start.toDouble() / width,
                                    y.toDouble() / height
                                ), PointD(end.toDouble() / width, y.toDouble() / height)
                            ),
                            supportingPointCount = rowPixels.count { (it.toInt() and 255) > 0 },
                            fitResidual = null,
                            curvatureCoefficient = null,
                            coverageRatio = coverage,
                            confidence = (0.45 + coverage.coerceAtMost(0.5)).toFloat(),
                            acceptedForDecision = false,
                            rejectionReason = "TABLE_STRUCTURE_IS_NOT_TEXT_BASELINE_EVIDENCE",
                        )
                    }
                }
                val verticalPixels = ByteArray(height)
                for (x in 0 until width) {
                    verticalMask.get(0, x, verticalPixels)
                    val coverage =
                        verticalPixels.count { (it.toInt() and 255) > 0 }.toDouble() / height
                    if (coverage >= TABLE_LINE_MIN_LENGTH) {
                        tableLines += CurvatureLineEvidence(
                            CurvatureEvidenceType.TABLE_LINE,
                            listOf(
                                PointD(
                                    x.toDouble() / width,
                                    verticalPixels.indexOfFirst { (it.toInt() and 255) > 0 }
                                        .toDouble() / height
                                ),
                                PointD(
                                    x.toDouble() / width,
                                    verticalPixels.indexOfLast { (it.toInt() and 255) > 0 }
                                        .toDouble() / height
                                )
                            ),
                            verticalPixels.count { (it.toInt() and 255) > 0 }, null, null, coverage,
                            (0.45 + coverage.coerceAtMost(0.5)).toFloat(), false,
                            "TABLE_STRUCTURE_IS_NOT_TEXT_BASELINE_EVIDENCE",
                        )
                    }
                }
                Core.subtract(binary, horizontalMask, textBinary)
                Core.subtract(textBinary, verticalMask, opened)
            } finally {
                horizontalKernel.release()
                verticalKernel.release()
                verticalMask.release()
            }

            Imgproc.connectedComponentsWithStats(
                textBinary,
                labels,
                stats,
                centroids,
                8,
                CvType.CV_32S
            )
            val minHeight = max(3, (height * MIN_COMPONENT_HEIGHT_RATIO).toInt())
            val maxHeight = max(minHeight + 1, (height * MAX_COMPONENT_HEIGHT_RATIO).toInt())
            val components = ArrayList<Component>()
            for (label in 1 until stats.rows()) {
                val values = IntArray(5)
                stats.get(label, 0, values)
                val c = Component(values[0], values[1], values[2], values[3], values[4])
                val aspect = c.width.toDouble() / c.height.coerceAtLeast(1)
                val insidePage =
                    c.x >= width * PAGE_MARGIN && c.x + c.width <= width * (1.0 - PAGE_MARGIN)
                            && c.y >= height * PAGE_MARGIN && c.y + c.height <= height * (1.0 - PAGE_MARGIN)
                if (insidePage && c.height in minHeight..maxHeight && c.width <= width * MAX_COMPONENT_WIDTH_RATIO
                    && c.area >= MIN_COMPONENT_AREA && aspect in MIN_COMPONENT_ASPECT..MAX_COMPONENT_ASPECT
                ) components += c
            }

            val rows = clusterByBaseline(components, width, height)
            val handwritingHint = documentType.equals("HANDWRITING", ignoreCase = true)
            val textCandidates = mutableListOf<CurvatureLineEvidence>()
            for (row in rows) {
                val sorted = row.sortedBy { it.centerX }
                val xRange = sorted.last().centerX - sorted.first().centerX
                val coverage = xRange / width
                val points = sorted.map { PointD(it.centerX / width, it.baseline / height) }
                val fit = fitQuadratic(points)
                val enough = sorted.size >= MIN_COMPONENTS_PER_LINE && coverage >= MIN_TEXT_COVERAGE
                val fitOkay = fit != null && fit.residual <= MAX_LINE_RESIDUAL
                val accepted = enough && fitOkay && !handwritingHint
                val type = when {
                    handwritingHint -> CurvatureEvidenceType.HANDWRITING
                    accepted -> CurvatureEvidenceType.TEXT_BASELINE
                    else -> CurvatureEvidenceType.UNKNOWN
                }
                val confidence = textCandidateConfidence(sorted.size, coverage, fit?.residual)
                textCandidates += CurvatureLineEvidence(
                    evidenceType = type,
                    supportPoints = points,
                    supportingPointCount = sorted.size,
                    fitResidual = fit?.residual,
                    curvatureCoefficient = fit?.coefficient,
                    coverageRatio = coverage,
                    confidence = confidence,
                    acceptedForDecision = accepted,
                    rejectionReason = when {
                        handwritingHint -> "HANDWRITING_BASELINE_DETECTOR_NOT_VALIDATED"
                        !enough -> "TOO_FEW_COMPONENTS_OR_INSUFFICIENT_HORIZONTAL_COVERAGE"
                        !fitOkay -> "UNSTABLE_OR_HIGH_RESIDUAL_LINE_FIT"
                        else -> null
                    },
                )
            }
            if (handwritingHint && textCandidates.none { it.evidenceType == CurvatureEvidenceType.HANDWRITING }) {
                textCandidates += CurvatureLineEvidence(
                    evidenceType = CurvatureEvidenceType.HANDWRITING,
                    supportPoints = emptyList(),
                    supportingPointCount = 0,
                    fitResidual = null,
                    curvatureCoefficient = null,
                    coverageRatio = 0.0,
                    confidence = 0.0f,
                    acceptedForDecision = false,
                    rejectionReason = "HANDWRITING_HINT_PRESENT_BUT_NO_VALIDATED_DETECTOR",
                )
            }

            Imgproc.Canny(gray, edges, 55.0, 135.0)
            Imgproc.HoughLinesP(
                edges,
                lines,
                1.0,
                Math.PI / 180.0,
                24,
                width * GRAPHIC_LINE_MIN_LENGTH,
                height * 0.015
            )
            val graphics = mutableListOf<CurvatureLineEvidence>()
            for (i in 0 until lines.rows()) {
                val segment = IntArray(4)
                lines.get(i, 0, segment)
                val dx = segment[2] - segment[0]
                val dy = segment[3] - segment[1]
                val length = hypot(dx.toDouble(), dy.toDouble())
                if (length < width * GRAPHIC_LINE_MIN_LENGTH) continue
                val coverage = length / width
                val pointA = PointD(segment[0].toDouble() / width, segment[1].toDouble() / height)
                val pointB = PointD(segment[2].toDouble() / width, segment[3].toDouble() / height)
                graphics += CurvatureLineEvidence(
                    CurvatureEvidenceType.GRAPHIC_EDGE,
                    listOf(pointA, pointB),
                    2,
                    null,
                    null,
                    coverage,
                    min(0.6, 0.25 + coverage).toFloat(),
                    false,
                    "EDGE_SEGMENT_IS_NOT_TEXT_BASELINE_EVIDENCE",
                )
            }

            val boundaries = listOf(
                boundary(0.0, 0.0, 1.0, 0.0),
                boundary(1.0, 0.0, 1.0, 1.0),
                boundary(1.0, 1.0, 0.0, 1.0),
                boundary(0.0, 1.0, 0.0, 0.0),
            )
            val all = tableLines + textCandidates + graphics + boundaries
            val text =
                textCandidates.filter { it.evidenceType == CurvatureEvidenceType.TEXT_BASELINE && it.acceptedForDecision }
            val coefficients = text.mapNotNull { it.curvatureCoefficient?.let(::abs) }
            val residuals = text.mapNotNull { it.fitResidual }
            val reasons = buildList {
                if (text.size < MIN_COMPONENTS_PER_LINE) add("FEWER_THAN_THREE_INDEPENDENT_TEXT_LINES")
                if (tableLines.isNotEmpty()) add("TABLE_LINES_EXCLUDED_FROM_TEXT_CURVATURE")
                if (handwritingHint) add("HANDWRITING_REQUIRES_A_VALIDATED_SPECIALIST_DETECTOR")
            }
            return Detection(
                CurvatureEvidence(
                    all,
                    text.size,
                    coefficients.takeIf { it.isNotEmpty() }?.let(::median),
                    residuals.takeIf { it.isNotEmpty() }?.let(::median)
                ),
                reasons,
            )
        } finally {
            gray.release()
            binary.release()
            horizontalMask.release()
            textBinary.release()
            labels.release()
            stats.release()
            centroids.release()
            edges.release()
            lines.release()
            opened.release()
        }
    }

    private fun clusterByBaseline(
        components: List<Component>,
        pageWidth: Int,
        pageHeight: Int
    ): List<List<Component>> {
        val tolerance = max(5.0, pageHeight * BASELINE_CLUSTER_TOLERANCE)
        val maxGap = max(36.0, pageWidth * MAX_TEXT_WORD_GAP_RATIO)
        val clusters = mutableListOf<MutableList<Component>>()
        for (component in components.sortedBy { it.centerX }) {
            val candidate = clusters.mapNotNull { group ->
                val last = group.last()
                val gap = component.x - (last.x + last.width)
                val minimumGap = -max(2.0, min(last.width, component.width) * 0.15)
                val baselineDelta = abs(component.baseline - last.baseline)
                if (gap.toDouble() in minimumGap..maxGap && baselineDelta <= tolerance) {
                    group to baselineDelta + gap.coerceAtLeast(0) * 0.05
                } else null
            }.minByOrNull { it.second }?.first
            if (candidate == null) clusters += mutableListOf(component) else candidate += component
        }
        return clusters
    }

    private data class Fit(val coefficient: Double, val residual: Double)

    private fun fitQuadratic(points: List<PointD>): Fit? {
        if (points.size < 3) return null
        val matrix = Array(3) { DoubleArray(4) }
        for (p in points) {
            val x = 2.0 * p.x - 1.0
            val y = p.y
            val terms = doubleArrayOf(x * x, x, 1.0)
            for (r in 0..2) {
                for (c in 0..2) matrix[r][c] += terms[r] * terms[c]
                matrix[r][3] += terms[r] * y
            }
        }
        val solution = solve(matrix) ?: return null
        val errors = points.map { p ->
            val x = 2.0 * p.x - 1.0
            val predicted = solution[0] * x * x + solution[1] * x + solution[2]
            (predicted - p.y) * (predicted - p.y)
        }
        return Fit(solution[0], sqrt(errors.average()))
    }

    private fun solve(matrix: Array<DoubleArray>): DoubleArray? {
        for (column in 0..2) {
            val pivot = (column..2).maxByOrNull { abs(matrix[it][column]) } ?: return null
            if (abs(matrix[pivot][column]) < 1e-10) return null
            val tmp = matrix[column]
            matrix[column] = matrix[pivot]
            matrix[pivot] = tmp
            val divisor = matrix[column][column]
            for (i in column..3) matrix[column][i] /= divisor
            for (row in 0..2) {
                if (row == column) continue
                val factor = matrix[row][column]
                for (i in column..3) matrix[row][i] -= factor * matrix[column][i]
            }
        }
        return doubleArrayOf(matrix[0][3], matrix[1][3], matrix[2][3])
    }

    private fun textCandidateConfidence(count: Int, coverage: Double, residual: Double?): Float {
        val countScore = (count / 14.0).coerceAtMost(1.0)
        val coverageScore = (coverage / 0.55).coerceIn(0.0, 1.0)
        val residualScore =
            residual?.let { (1.0 - it / MAX_LINE_RESIDUAL).coerceIn(0.0, 1.0) } ?: 0.0
        return (0.35 * countScore + 0.40 * coverageScore + 0.25 * residualScore).toFloat()
    }

    private fun boundary(x1: Double, y1: Double, x2: Double, y2: Double) = CurvatureLineEvidence(
        CurvatureEvidenceType.PAGE_BOUNDARY,
        listOf(PointD(x1, y1), PointD(x2, y2)),
        2,
        null,
        null,
        1.0,
        1.0f,
        false,
        "PAGE_BOUNDARY_IS_EXCLUDED_FROM_TEXT_CURVATURE",
    )

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private const val PAGE_MARGIN = 0.035
    private const val MIN_COMPONENT_HEIGHT_RATIO = 0.003
    private const val MAX_COMPONENT_HEIGHT_RATIO = 0.045
    private const val MAX_COMPONENT_WIDTH_RATIO = 0.16
    private const val MIN_COMPONENT_AREA = 3
    private const val MIN_COMPONENT_ASPECT = 0.08
    private const val MAX_COMPONENT_ASPECT = 15.0
    private const val MIN_COMPONENTS_PER_LINE = 5
    private const val MIN_TEXT_COVERAGE = 0.40
    private const val MAX_LINE_RESIDUAL = 0.018
    private const val BASELINE_CLUSTER_TOLERANCE = 0.008
    private const val MAX_TEXT_WORD_GAP_RATIO = 0.08
    private const val TABLE_LINE_MIN_LENGTH = 0.30
    private const val GRAPHIC_LINE_MIN_LENGTH = 0.18
}

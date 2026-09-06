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

package nopalito.app.ui.screens.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nopalito.app.R
import nopalito.app.data.stats.DailyRow
import nopalito.app.data.stats.ExportCountRow
import nopalito.app.data.stats.StatsPeriod
import nopalito.app.data.stats.StatsSummary
import nopalito.app.data.stats.ToolCountRow
import nopalito.app.data.stats.formatWithGrouping
import nopalito.app.ui.components.GradientHeroHeader
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
private fun currentLocale(): Locale {
    val configuration = LocalConfiguration.current
    return if (configuration.locales.isEmpty) Locale.getDefault()
    else configuration.locales[0]
}

@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val summary by viewModel.summary.collectAsState()
    val daily by viewModel.daily.collectAsState()
    val toolBreakdown by viewModel.toolBreakdown.collectAsState()
    val exportBreakdown by viewModel.exportBreakdown.collectAsState()
    val period by viewModel.period.collectAsState()

    BackHandler { onBack() }

    val hasAnyData =
        summary.scans > 0 || summary.exportedCount > 0 || summary.shares > 0 || summary.toolsTotal > 0 || summary.opens > 0 || summary.photos > 0 ||
                daily.any { it.scans > 0 || it.exports > 0 || it.shares > 0 || it.tools > 0 || it.opens > 0 || it.photos > 0 }

    Column(modifier = modifier.fillMaxSize()) {
        GradientHeroHeader(
            title = stringResource(R.string.stats_title),
            subtitle = periodSubtitle(summary, period),
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PeriodSelector(
                selected = period,
                onSelect = viewModel::selectPeriod
            )

            if (!hasAnyData) {
                EmptyState()
            } else {
                SummaryCard(summary = summary, period = period)

                // 2x2 category grid: each category shows its own stats for the selected period
                CategoryGrid(summary = summary)

                // Adaptive chart for scans (primary) across the selected period
                ChartSection(daily = daily, period = period)

                // Separate breakdown sections per specification
                ToolBreakdownSection(rows = toolBreakdown, total = summary.toolsTotal)
                ExportBreakdownSection(rows = exportBreakdown, total = summary.exportedCount)
                ShareSection(summary = summary)
            }
        }
    }
}

@Composable
private fun periodSubtitle(summary: StatsSummary, period: StatsPeriod): String {
    val locale = currentLocale()
    return when (period) {
        StatsPeriod.DAY -> stringResource(R.string.stats_chart_today) + " • ${summary.scans} ${
            stringResource(
                R.string.stats_documents
            ).lowercase(locale)
        }"

        StatsPeriod.WEEK -> stringResource(R.string.stats_chart_last_7_days) + " • ${summary.scans}"
        StatsPeriod.MONTH -> stringResource(R.string.stats_chart_last_30_days) + " • ${summary.scans}"
        StatsPeriod.YEAR -> stringResource(R.string.stats_chart_last_12_months) + " • ${summary.scans}"
    }
}

@Composable
private fun PeriodSelector(
    selected: StatsPeriod,
    onSelect: (StatsPeriod) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatsPeriod.entries.forEach { period ->
            FilterChip(
                selected = selected == period,
                onClick = { onSelect(period) },
                label = { Text(stringResource(period.labelRes)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

@Composable
private fun SummaryCard(summary: StatsSummary, period: StatsPeriod) {
    val locale = currentLocale()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Insights,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                val periodLabel = stringResource(period.labelRes)
                Text(
                    text = "$periodLabel • ${summary.scans.formatWithGrouping()} ${stringResource(R.string.stats_documents)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (summary.pages == 1) stringResource(R.string.stats_page_single)
                    else stringResource(R.string.stats_pages_count, summary.pages),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${formatMb(summary.sizeKb)} • ${
                        stringResource(
                            R.string.stats_exported_count,
                            summary.exportedCount
                        )
                    } • ${summary.shares} ${
                        stringResource(R.string.stats_category_shares).lowercase(
                            locale
                        )
                    } • ${summary.toolsTotal} ${
                        stringResource(R.string.stats_category_tools).lowercase(
                            locale
                        )
                    }",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                if (summary.photos > 0 || summary.opens > 0) {
                    Text(
                        text = "${summary.photos} photos • ${summary.opens} opened",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryGrid(summary: StatsSummary) {
    val locale = currentLocale()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CategoryCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Description,
                value = summary.scans.formatWithGrouping(),
                label = stringResource(R.string.stats_category_scans),
                subLabel = if (summary.pages == 1) stringResource(R.string.stats_page_single) else stringResource(
                    R.string.stats_pages_count,
                    summary.pages
                ),
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
            CategoryCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.PictureAsPdf,
                value = summary.exportedCount.formatWithGrouping(),
                label = stringResource(R.string.stats_category_exports),
                subLabel = formatMb(summary.sizeKb),
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CategoryCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.IosShare,
                value = summary.shares.formatWithGrouping(),
                label = stringResource(R.string.stats_category_shares),
                subLabel = if (summary.shares == 0) stringResource(R.string.stats_no_data_shares) else "${summary.shares} ${
                    stringResource(
                        R.string.stats_category_shares
                    ).lowercase(locale)
                }",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
            CategoryCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Build,
                value = summary.toolsTotal.formatWithGrouping(),
                label = stringResource(R.string.stats_category_tools),
                subLabel = if (summary.toolsTotal == 0) stringResource(R.string.stats_no_data_tools) else stringResource(
                    R.string.stats_all_tools
                ),
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CategoryCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Image,
                value = summary.photos.formatWithGrouping(),
                label = "Photos",
                subLabel = if (summary.photos == 0) "No photos yet" else "${summary.photos} photos",
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
            CategoryCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.FolderOpen,
                value = summary.opens.formatWithGrouping(),
                label = "Opened",
                subLabel = if (summary.opens == 0) "No opens yet" else "${summary.opens} opened",
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        }
    }
}

@Composable
private fun CategoryCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    subLabel: String,
    containerColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(containerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                text = subLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    containerColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(containerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ChartSection(daily: List<DailyRow>, period: StatsPeriod) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val locale = currentLocale()

    val chartTitle = when (period) {
        StatsPeriod.DAY -> stringResource(R.string.stats_chart_today)
        StatsPeriod.WEEK -> stringResource(R.string.stats_chart_last_7_days)
        StatsPeriod.MONTH -> stringResource(R.string.stats_chart_last_30_days)
        StatsPeriod.YEAR -> stringResource(R.string.stats_chart_last_12_months)
    }

    // Build display entries depending on period - locale-aware
    val entries: List<ChartEntry> = when (period) {
        StatsPeriod.DAY -> buildDailyEntries(daily, 1, locale)
        StatsPeriod.WEEK -> buildDailyEntries(daily, 7, locale)
        StatsPeriod.MONTH -> buildDailyEntries(daily, 30, locale)
        StatsPeriod.YEAR -> buildMonthlyEntries(daily, locale)
    }
    val max = (entries.maxOfOrNull { it.value } ?: 0).coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.BarChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = chartTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(14.dp)
                )
            }

            // For month with many bars, allow horizontal scroll if needed; otherwise fit
            val chartHeight = 160.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val barCount = entries.size.coerceAtLeast(1)
                    val gap = if (barCount > 12) 4.dp.toPx() else 8.dp.toPx()
                    val barW = (w - gap * (barCount + 1)) / barCount
                    val chartH = h - 32.dp.toPx()
                    val chartTop = 16.dp.toPx()

                    // Background tracks
                    entries.forEachIndexed { i, _ ->
                        val x = gap + i * (barW + gap)
                        drawRoundRect(
                            color = surfaceVariant.copy(alpha = 0.8f),
                            topLeft = Offset(x, chartTop),
                            size = Size(barW, chartH),
                            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                        )
                    }
                    // Foreground bars (scans)
                    entries.forEachIndexed { i, entry ->
                        val ratio = entry.value.toFloat() / max.toFloat()
                        val barH =
                            (chartH * ratio).coerceAtLeast(if (entry.value > 0) 4.dp.toPx() else 0f)
                        val x = gap + i * (barW + gap)
                        val y = chartTop + chartH - barH
                        if (barH > 0) {
                            drawRoundRect(
                                brush = Brush.verticalGradient(listOf(primary, tertiary)),
                                topLeft = Offset(x, y),
                                size = Size(barW, barH),
                                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                            )
                        }
                    }
                }

                // Value labels on top of bars
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartHeight)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    entries.forEach { entry ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 1.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            if (entry.value > 0) {
                                Text(
                                    text = entry.value.formatWithGrouping(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip
                                )
                            }
                        }
                    }
                }

                // Labels at bottom
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    entries.forEach { entry ->
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = if (entries.size > 12) 7.sp else 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }
            // Legend with totals for other categories in same period
            if (entries.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    entries.takeIf { it.size <= 7 }?.let {
                        // show summed info only for small periods
                    }
                }
            }
        }
    }
}

private data class ChartEntry(val label: String, val value: Int)

private fun buildDailyEntries(daily: List<DailyRow>, days: Int, locale: Locale): List<ChartEntry> {
    val map = daily.associateBy { it.date }
    val today = LocalDate.now()
    return (days - 1 downTo 0).map { offset ->
        val date = today.minusDays(offset.toLong())
        val key = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val row = map[key]
        val label = if (days == 1) {
            date.format(DateTimeFormatter.ofPattern("MMM dd", locale))
        } else if (days <= 7) {
            shortDay(key, locale)
        } else {
            // For 30 days, show day number only to save space
            date.format(DateTimeFormatter.ofPattern("dd", locale))
        }
        ChartEntry(label = label, value = row?.scans ?: 0)
    }
}

private fun buildMonthlyEntries(daily: List<DailyRow>, locale: Locale): List<ChartEntry> {
    val today = LocalDate.now()
    // Aggregate daily rows by YearMonth
    val byMonth = daily.groupBy {
        try {
            val d = LocalDate.parse(it.date, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            YearMonth.from(d)
        } catch (_: Exception) {
            null
        }
    }.filterKeys { it != null }

    return (11 downTo 0).map { offset ->
        val ym = YearMonth.from(today).minusMonths(offset.toLong())
        val rows = byMonth[ym] ?: emptyList()
        val sum = rows.sumOf { it.scans }
        val label = ym.format(DateTimeFormatter.ofPattern("MMM", locale)).take(3)
        ChartEntry(label = label, value = sum)
    }
}

@Composable
private fun ToolBreakdownSection(rows: List<ToolCountRow>, total: Int) {
    val locale = currentLocale()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.stats_tool_breakdown),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${total.formatWithGrouping()} ${
                        stringResource(R.string.stats_category_tools).lowercase(
                            locale
                        )
                    }",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.stats_no_data_tools),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val max = rows.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                    rows.forEach { row ->
                        ToolRowItem(row = row, max = max, locale = locale)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRowItem(row: ToolCountRow, max: Int, locale: Locale) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatToolName(row.tool, locale),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = row.count.formatWithGrouping(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        LinearProgressIndicator(
            progress = { row.count.toFloat() / max.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun ExportBreakdownSection(rows: List<ExportCountRow>, total: Int) {
    val locale = currentLocale()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.stats_export_breakdown),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${total.formatWithGrouping()} ${
                        stringResource(R.string.stats_category_exports).lowercase(
                            locale
                        )
                    }",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.stats_no_data_exports),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val max = rows.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.format.uppercase(locale),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                LinearProgressIndicator(
                                    progress = { row.count.toFloat() / max.toFloat() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = MaterialTheme.colorScheme.secondary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = row.count.formatWithGrouping(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareSection(summary: StatsSummary) {
    val locale = currentLocale()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.IosShare,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.stats_category_shares),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (summary.shares == 0) stringResource(R.string.stats_no_data_shares)
                    else "${summary.shares.formatWithGrouping()} ${
                        stringResource(R.string.stats_category_shares).lowercase(
                            locale
                        )
                    } • ${summary.deletes.formatWithGrouping()} ${
                        stringResource(R.string.stats_deletes).lowercase(
                            locale
                        )
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = summary.shares.formatWithGrouping(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.BarChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                text = stringResource(R.string.stats_empty),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatMb(sizeKb: Long): String {
    val mb = sizeKb / 1024.0
    return if (mb < 1) "${sizeKb} KB" else String.format(Locale.US, "%.1f MB", mb)
}

private fun formatToolName(raw: String, locale: Locale): String {
    if (raw.isBlank()) return raw
    // Convert snake_case like "compress_pdf" or "password_protect" to "Compress Pdf"
    return raw.split('_', '-', ' ').joinToString(" ") { part ->
        part.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(locale) else c.toString() }
    }
}

private fun shortDay(isoDate: String, locale: Locale): String {
    return try {
        val d = LocalDate.parse(isoDate, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        d.format(DateTimeFormatter.ofPattern("EE", locale)).take(3)
    } catch (_: Exception) {
        isoDate.takeLast(2)
    }
}

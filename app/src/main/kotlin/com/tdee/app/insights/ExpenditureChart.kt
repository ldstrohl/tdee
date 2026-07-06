package com.tdee.app.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tdee.app.data.DayExpenditurePoint
import com.tdee.app.ui.theme.ChartColors
import com.tdee.app.ui.theme.LocalChartColors
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// ---------------------------------------------------------------------------
// Expenditure chart section
// ---------------------------------------------------------------------------

@Composable
internal fun ExpenditureChartSection(
    points: List<DayExpenditurePoint>,
    selectedRange: ChartRange,
    onRangeSelected: (ChartRange) -> Unit,
    onMaximize: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Expenditure", style = MaterialTheme.typography.titleMedium)
            if (points.size >= 2) {
                TextButton(onClick = onMaximize) { Text("Expand ⤢") }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChartRange.values().forEach { range ->
                Pill(
                    label = range.label,
                    active = range == selectedRange,
                    onClick = { onRangeSelected(range) },
                )
            }
        }

        when {
            points.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Log food to see expenditure",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            else -> {
                val chartColors = LocalChartColors.current
                val textMeasurer = rememberTextMeasurer()
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(880f / 340f)
                        .clickable(onClick = onMaximize),
                ) {
                    drawExpenditureChart(
                        points = points,
                        colors = chartColors,
                        textMeasurer = textMeasurer,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Canvas drawing — matches expend_chart() geometry from design/charts_gen.py
// ---------------------------------------------------------------------------

/**
 * Draws the expenditure chart on the current Canvas.
 *
 * Geometry mirrors expend_chart() in design/charts_gen.py:
 *   - Same 880×340 viewBox margins as the trend chart (ml=58, mr=18, mt=22, mb=40)
 *   - Intake bars (intakeBar color; skip days with null intakeKcal)
 *   - Deficit-only green shading between bar-top and TDEE line when intake < TDEE
 *   - TDEE polyline (tdeeLine color, stroke-width 3 scaled)
 *   - ~6 y-gridlines + ~6 date ticks
 */
internal fun DrawScope.drawExpenditureChart(
    points: List<DayExpenditurePoint>,
    colors: ChartColors,
    textMeasurer: TextMeasurer,
    xDomain: Pair<LocalDate, LocalDate>? = null,
) {
    val w = size.width
    val h = size.height

    // Same margins as trend chart (fractions of 880×340 reference)
    val ml = w * (58f / 880f)
    val mr = w * (18f / 880f)
    val mt = h * (22f / 340f)
    val mb = h * (40f / 340f)
    val pw = w - ml - mr
    val ph = h - mt - mb
    val scale = w / 880f

    // X domain. An explicit [xDomain] (pinch/pan in the full-screen view) narrows the window;
    // only the points inside it are drawn, and bar width / y-scale key off that visible subset.
    val tMin = xDomain?.first ?: points.first().date
    val tMax = xDomain?.second ?: points.last().date
    val drawPoints = if (xDomain == null) points
        else points.filter { !it.date.isBefore(tMin) && !it.date.isAfter(tMax) }
    val n = drawPoints.size.coerceAtLeast(1)
    val totalDays = ChronoUnit.DAYS.between(tMin, tMax).toFloat().coerceAtLeast(1f)

    fun xOf(date: LocalDate): Float {
        val offset = ChronoUnit.DAYS.between(tMin, date).toFloat()
        return ml + pw * (offset / totalDays)
    }

    // Y domain — include all intakeKcal (non-null) and all tdeeKcal values, ±150 padding
    val intakeValues = drawPoints.mapNotNull { it.intakeKcal }
    val tdeeValues = drawPoints.map { it.tdeeKcal }
    val allVals = intakeValues + tdeeValues
    val vMin = ((allVals.minOrNull() ?: 1500.0) - 150.0)
    val vMax = ((allVals.maxOrNull() ?: 3000.0) + 150.0)
    val vRange = (vMax - vMin).toFloat().coerceAtLeast(1f)

    fun yOf(v: Double): Float = mt + ph * (1f - ((v - vMin) / vRange).toFloat())

    val base = mt + ph  // y coordinate of the bottom of the chart area

    // Bar half-width: scaled to match design (0.7 × plot-width / n, min 1.5)
    val bw = (pw / n.toFloat() * 0.7f).coerceAtLeast(1.5f * scale)

    // ---- 6 Gridlines + y-axis labels ----
    val axisStyle = TextStyle(fontSize = 11.sp, color = colors.axisLabel)
    for (k in 0..5) {
        val v = vMin + (vMax - vMin) * k / 5.0
        val y = yOf(v)
        drawLine(color = colors.gridLine, start = Offset(ml, y), end = Offset(w - mr, y), strokeWidth = 1f)
        val label = "%.0f".format(v)
        val m = textMeasurer.measure(label, axisStyle)
        drawText(m, topLeft = Offset(ml - m.size.width - 6f, y - m.size.height / 2f))
    }

    // ---- 6 Date ticks ----
    for (k in 0..5) {
        val dayOffset = totalDays * k / 5f
        val tickDate = tMin.plusDays(dayOffset.toLong())
        val x = ml + pw * (k / 5f)
        val label = tickDate.format(DATE_FMT)
        val m = textMeasurer.measure(label, axisStyle)
        drawText(m, topLeft = Offset(x - m.size.width / 2f, h - mb + 6f))
    }

    // Clip bars + line to the plot rect so a panned window never bleeds into the gutter.
    clipRect(left = ml, top = mt, right = w - mr, bottom = mt + ph) {
        // ---- Intake bars + deficit shading ----
        for (point in drawPoints) {
            val x = xOf(point.date)
            val intakeKcal = point.intakeKcal ?: continue  // skip unlogged days

            val yIntake = yOf(intakeKcal)
            val yTdee = yOf(point.tdeeKcal)

            // Intake bar (from bottom up to intake level)
            drawRect(
                color = colors.intakeBar,
                topLeft = Offset(x - bw / 2f, yIntake),
                size = Size(bw, base - yIntake),
                alpha = 0.85f,
            )

            // Deficit-only shading: shade the gap between bar-top and TDEE line ONLY when intake < TDEE
            if (intakeKcal < point.tdeeKcal) {
                drawRect(
                    color = colors.deficitFill,
                    topLeft = Offset(x - bw / 2f, yTdee),
                    size = Size(bw, yIntake - yTdee),
                    alpha = 0.7f,
                )
            }
        }

        // ---- TDEE polyline (stroke-width 3 at reference width) ----
        if (drawPoints.size >= 2) {
            val path = Path()
            drawPoints.forEachIndexed { i, p ->
                val x = xOf(p.date)
                val y = yOf(p.tdeeKcal)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = colors.tdeeLine, style = Stroke(width = 3f * scale))
        }
    }
}

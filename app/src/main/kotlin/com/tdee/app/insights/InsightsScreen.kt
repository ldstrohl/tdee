package com.tdee.app.insights

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tdee.app.BuildConfig
import com.tdee.app.ui.theme.ChartColors
import com.tdee.app.ui.theme.LocalChartColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Insights", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }

        // Trend chart section
        TrendChartSection(
            state = state,
            onRangeSelected = { viewModel.setRange(it) },
            onPredictionToggle = { viewModel.setPrediction(!state.predictionOn) },
        )

        // Debug-only seed button
        if (BuildConfig.DEBUG) {
            Button(
                onClick = { viewModel.seedAndReload() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text("Load sample data (debug)")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Trend chart section: pills + toggle + chart
// ---------------------------------------------------------------------------

@Composable
private fun TrendChartSection(
    state: InsightsUiState,
    onRangeSelected: (WeightRange) -> Unit,
    onPredictionToggle: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Weight Trend", style = MaterialTheme.typography.titleMedium)

        // Range pills (own row)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            WeightRange.values().forEach { range ->
                Pill(
                    label = range.label,
                    active = range == state.selectedRange,
                    onClick = { onRangeSelected(range) },
                )
            }
        }
        // Prediction toggle on its own row so its label never clips
        Row(modifier = Modifier.fillMaxWidth()) {
            Pill(
                label = "🔮 Prediction",
                active = state.predictionOn,
                onClick = onPredictionToggle,
            )
        }

        // Chart or placeholder
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            state.visiblePoints.size < 2 -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Add weigh-ins to see your trend",
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
                // Goal line always shows when a goal is set; the projection LINES only when toggled on.
                val goalLbAlways = (state.projection as? ProjectionUi.Ready)?.goalLb
                val projectionArg = if (state.predictionOn) state.projection else ProjectionUi.NoGoal

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(880f / 340f),
                ) {
                    drawTrendChart(
                        points = state.visiblePoints,
                        goalLb = goalLbAlways,
                        projection = projectionArg,
                        colors = chartColors,
                        textMeasurer = textMeasurer,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pill composable
// ---------------------------------------------------------------------------

@Composable
private fun Pill(label: String, active: Boolean, onClick: () -> Unit) {
    val containerColor =
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor =
        if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Button(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.height(32.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// ---------------------------------------------------------------------------
// Canvas drawing — matches trend_chart() geometry from design/charts_gen.py
// ---------------------------------------------------------------------------

private val DATE_FMT = DateTimeFormatter.ofPattern("MMM dd")
private val DATE_FMT_LONG = DateTimeFormatter.ofPattern("MMM dd, yyyy")
private val DASH_5_4 = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))
private val DASH_7_5 = PathEffect.dashPathEffect(floatArrayOf(7f, 5f))
private val DASH_3_3 = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))

/**
 * Draws the trend chart on the current Canvas.
 *
 * Geometry mirrors trend_chart() in design/charts_gen.py:
 *   - margins (as fractions of 880×340 reference): ml=58, mr=18, mt=22, mb=40
 *   - 6 horizontal gridlines + y-axis labels
 *   - 6 date ticks along x-axis
 *   - raw scatter: r≈2.4 (scaled), EMA polyline stroke 3 (scaled)
 *   - goal dashed line + "goal NNN lb" label
 *   - prediction overlay: "now" divider, two dashed lines with dated end-markers
 *
 * All colors come from [colors] (LocalChartColors.current at the call site).
 */
private fun DrawScope.drawTrendChart(
    points: List<WeightPointLb>,
    goalLb: Double?,
    projection: ProjectionUi,
    colors: ChartColors,
    textMeasurer: TextMeasurer,
) {
    val w = size.width
    val h = size.height

    // Margins as fractions of the 880×340 reference viewBox
    val ml = w * (58f / 880f)
    val mr = w * (18f / 880f)
    val mt = h * (22f / 340f)
    val mb = h * (40f / 340f)
    val pw = w - ml - mr
    val ph = h - mt - mb

    // Scale factor for strokes / radii relative to reference width
    val scale = w / 880f

    // X domain
    val historyDates = points.map { it.date }
    val tMin = historyDates.first()

    val projReady = projection as? ProjectionUi.Ready
    val furthestPredDate: LocalDate? = projReady?.let {
        listOfNotNull(
            (it.goalPace as? PaceUi.Reachable)?.date,
            (it.currentPace as? PaceUi.Reachable)?.date,
        ).maxOrNull()
    }
    val tMax: LocalDate = furthestPredDate
        ?.takeIf { it.isAfter(historyDates.last()) }
        ?: historyDates.last()

    val totalDays = ChronoUnit.DAYS.between(tMin, tMax).toFloat().coerceAtLeast(1f)

    fun xOf(date: LocalDate): Float {
        val offset = ChronoUnit.DAYS.between(tMin, date).toFloat()
        return ml + pw * (offset / totalDays)
    }

    // Y domain — include raw, ema, and goal in the range
    val allYVals = buildList<Double> {
        addAll(points.mapNotNull { it.rawLb })
        addAll(points.map { it.emaLb })
        goalLb?.let { add(it) }
    }
    val vMin = (allYVals.minOrNull() ?: 100.0) - 2.0
    val vMax = (allYVals.maxOrNull() ?: 200.0) + 2.0
    val vRange = (vMax - vMin).toFloat().coerceAtLeast(1f)

    fun yOf(v: Double): Float = mt + ph * (1f - ((v - vMin) / vRange).toFloat())

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

    // ---- Goal dashed line + label (always shown when a goal is set) ----
    if (goalLb != null) {
        val gy = yOf(goalLb)
        drawLine(
            color = colors.goalLine,
            start = Offset(ml, gy),
            end = Offset(w - mr, gy),
            strokeWidth = 1.5f,
            pathEffect = DASH_5_4,
        )
        val glText = "goal %.0f lb".format(goalLb)
        val glM = textMeasurer.measure(glText, TextStyle(fontSize = 10.sp, color = colors.goalLine))
        // Left-anchored so it never collides with the right-side projection end-labels
        drawText(glM, topLeft = Offset(ml + 4f, gy - glM.size.height - 4f))
    }

    // ---- Raw scatter dots (r≈2.4 at reference width) ----
    val rawRadius = 2.4f * scale
    for (p in points) {
        val rawLb = p.rawLb ?: continue
        drawCircle(color = colors.rawPoint, radius = rawRadius, center = Offset(xOf(p.date), yOf(rawLb)))
    }

    // ---- EMA polyline (stroke-width 3 at reference width) ----
    if (points.size >= 2) {
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = xOf(p.date)
            val y = yOf(p.emaLb)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = colors.emaLine, style = Stroke(width = 3f * scale))
    }

    // ---- Prediction overlay ----
    if (projReady != null) {
        val last = points.last()
        val nowX = xOf(last.date)
        val nowY = yOf(last.emaLb)
        val goalY = if (goalLb != null) yOf(goalLb) else nowY

        // "now" vertical divider
        drawLine(
            color = Color(0xFFCFC8DB),
            start = Offset(nowX, mt),
            end = Offset(nowX, mt + ph),
            strokeWidth = 1f,
            pathEffect = DASH_3_3,
        )
        val nowM = textMeasurer.measure("now", TextStyle(fontSize = 9.sp, color = Color(0xFF8A7FB0)))
        drawText(nowM, topLeft = Offset(nowX - nowM.size.width / 2f, mt + 2f))

        // Goal pace line
        val gPace = projReady.goalPace
        if (gPace is PaceUi.Reachable) {
            val endX = xOf(gPace.date)
            drawLine(
                color = colors.projectionGoal,
                start = Offset(nowX, nowY),
                end = Offset(endX, goalY),
                strokeWidth = 2.4f * scale,
                pathEffect = DASH_7_5,
            )
            drawCircle(color = colors.projectionGoal, radius = 4f * scale, center = Offset(endX, goalY))
            val gLbl = "goal pace: ${gPace.date.format(DATE_FMT_LONG)}"
            val gM = textMeasurer.measure(
                gLbl,
                TextStyle(fontSize = 10.sp, color = colors.projectionGoal, fontWeight = FontWeight.SemiBold),
            )
            // Label above the end-dot, clamped to stay within the canvas width
            drawText(gM, topLeft = Offset((endX - gM.size.width / 2f).coerceIn(2f, w - gM.size.width - 2f), goalY - gM.size.height - 10f))
        }

        // Current pace line
        val cPace = projReady.currentPace
        when (cPace) {
            is PaceUi.Reachable -> {
                val endX = xOf(cPace.date)
                drawLine(
                    color = colors.projectionCurrent,
                    start = Offset(nowX, nowY),
                    end = Offset(endX, goalY),
                    strokeWidth = 2.4f * scale,
                    pathEffect = DASH_7_5,
                )
                drawCircle(color = colors.projectionCurrent, radius = 4f * scale, center = Offset(endX, goalY))
                val cLbl = "current pace: ${cPace.date.format(DATE_FMT_LONG)}"
                val cM = textMeasurer.measure(
                    cLbl,
                    TextStyle(fontSize = 10.sp, color = colors.projectionCurrent, fontWeight = FontWeight.SemiBold),
                )
                // Label below the end-dot, clamped to stay within the canvas width
                drawText(cM, topLeft = Offset((endX - cM.size.width / 2f).coerceIn(2f, w - cM.size.width - 2f), goalY + 16f))
            }

            is PaceUi.Unreachable -> {
                // Show "not on track" note near the bottom of the "now" line
                val noteM = textMeasurer.measure(
                    "current pace: not on track",
                    TextStyle(fontSize = 9.sp, color = colors.projectionCurrent),
                )
                drawText(noteM, topLeft = Offset(nowX + 4f, mt + ph - noteM.size.height - 4f))
            }
        }
    }
}

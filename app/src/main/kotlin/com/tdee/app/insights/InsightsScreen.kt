package com.tdee.app.insights

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tdee.app.BuildConfig
import com.tdee.app.data.DayExpenditurePoint
import com.tdee.app.data.MacroSummary
import com.tdee.app.ui.theme.ChartColors
import com.tdee.app.ui.theme.LocalChartColors
import com.tdee.domain.KG_TO_LB
import com.tdee.domain.PaceEstimator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong
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
    onHelp: () -> Unit = {},
    onMaximize: (ChartType) -> Unit = {},
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
            Row {
                TextButton(onClick = onHelp) { Text("?") }
                TextButton(onClick = onBack) { Text("Back") }
            }
        }

        // Trend chart section
        WeightTrendPanel(
            points = state.visiblePoints,
            selectedRange = state.selectedRange,
            onRangeSelected = { viewModel.setRange(it) },
            predictionOn = state.predictionOn,
            onPredictionToggle = { viewModel.setPrediction(!state.predictionOn) },
            projection = state.projection,
            isLoading = state.isLoading,
            onMaximize = { onMaximize(ChartType.TREND) },
        )

        // Expenditure chart section (independent range)
        ExpenditureChartSection(
            points = state.visibleExpenditurePoints,
            selectedRange = state.expenditureRange,
            onRangeSelected = { viewModel.setExpenditureRange(it) },
            onMaximize = { onMaximize(ChartType.EXPENDITURE) },
        )

        // Macro donut section (independent window)
        MacroDonutSection(
            summary = state.macroSummary,
            selectedWindow = state.macroWindow,
            onWindowSelected = { viewModel.setMacroWindow(it) },
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
// Weight trend panel: header/Expand + range pills + prediction toggle + chart.
// Shared by Insights and the Weight screen — do not fork this composable.
// ---------------------------------------------------------------------------

@Composable
internal fun WeightTrendPanel(
    points: List<WeightPointLb>,
    selectedRange: ChartRange,
    onRangeSelected: (ChartRange) -> Unit,
    predictionOn: Boolean,
    onPredictionToggle: () -> Unit,
    projection: ProjectionUi,
    isLoading: Boolean,
    onMaximize: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Weight Trend", style = MaterialTheme.typography.titleMedium)
            if (points.size >= 2) {
                TextButton(onClick = onMaximize) { Text("Expand ⤢") }
            }
        }

        // Range pills (own row)
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
        // Prediction toggle on its own row so its label never clips
        Row(modifier = Modifier.fillMaxWidth()) {
            Pill(
                label = "🔮 Prediction",
                active = predictionOn,
                onClick = onPredictionToggle,
            )
        }

        // Chart or placeholder
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            points.size < 2 -> {
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
                // Goal line always shows when a goal is set; the projection LINES only when toggled on.
                val goalLbAlways = (projection as? ProjectionUi.Ready)?.goalLb
                val projectionArg = if (predictionOn) projection else ProjectionUi.NoGoal
                WeightTrendChart(
                    points = points,
                    goalLb = goalLbAlways,
                    projection = projectionArg,
                    modifier = Modifier.clickable(onClick = onMaximize),
                )
            }
        }
    }
}

/**
 * Renders the weight trend Canvas (raw scatter + EMA + optional goal line + optional prediction
 * overlay). Shared by the Insights trend section and the Weight screen; pass
 * [ProjectionUi.NoGoal] to draw just the trend (with a goal line when [goalLb] is set).
 */
@Composable
internal fun WeightTrendChart(
    points: List<WeightPointLb>,
    goalLb: Double?,
    projection: ProjectionUi,
    modifier: Modifier = Modifier,
) {
    val chartColors = LocalChartColors.current
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(880f / 340f),
    ) {
        drawTrendChart(
            points = points,
            goalLb = goalLb,
            projection = projection,
            colors = chartColors,
            textMeasurer = textMeasurer,
        )
    }
}

// ---------------------------------------------------------------------------
// Expenditure chart section
// ---------------------------------------------------------------------------

@Composable
private fun ExpenditureChartSection(
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
// Macro donut section
// ---------------------------------------------------------------------------

@Composable
internal fun MacroDonutSection(
    summary: MacroSummary?,
    selectedWindow: MacroWindow,
    onWindowSelected: (MacroWindow) -> Unit,
    showTitle: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) {
            Text("Macros", style = MaterialTheme.typography.titleMedium)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            MacroWindow.values().forEach { window ->
                Pill(
                    label = window.label,
                    active = window == selectedWindow,
                    onClick = { onWindowSelected(window) },
                )
            }
        }

        if (summary == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Log food to see macro breakdown",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val chartColors = LocalChartColors.current
            val textMeasurer = rememberTextMeasurer()

            // Caption: "Today" for TODAY window; "avg/day · N of M days logged" otherwise
            val caption = if (selectedWindow == MacroWindow.TODAY) {
                "Today"
            } else {
                "avg/day · ${summary.completeDays} of ${summary.totalDays} days logged"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Donut canvas — square, ~200dp
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(
                        modifier = Modifier
                            .height(200.dp)
                            .aspectRatio(1f),
                    ) {
                        drawDonut(
                            summary = summary,
                            isToday = selectedWindow == MacroWindow.TODAY,
                            colors = chartColors,
                            textMeasurer = textMeasurer,
                        )
                    }
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Macro bars
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MacroBar(
                        label = "Protein",
                        consumed = summary.proteinG,
                        target = summary.targets.proteinG,
                        color = chartColors.proteinColor,
                    )
                    MacroBar(
                        label = "Fat",
                        consumed = summary.fatG,
                        target = summary.targets.fatG,
                        color = chartColors.fatColor,
                    )
                    MacroBar(
                        label = "Carbs",
                        consumed = summary.carbG,
                        target = summary.targets.carbG,
                        color = chartColors.carbColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun MacroBar(label: String, consumed: Double, target: Double, color: Color) {
    val fraction = if (target > 0) (consumed / target).coerceIn(0.0, 1.0).toFloat() else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                "${consumed.toInt()} / ${target.toInt()} g",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp),
        ) {
            // Track
            drawRoundRect(
                color = color.copy(alpha = 0.18f),
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f),
            )
            // Fill
            if (fraction > 0f) {
                drawRoundRect(
                    color = color,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pill composable
// ---------------------------------------------------------------------------

@Composable
internal fun Pill(label: String, active: Boolean, onClick: () -> Unit) {
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
 * Max days past the nominal expected goal date the P90 cone may be drawn to. When the expected
 * rate |r| is at most the P90 growth rate c the slow cone edge never crosses the goal weight (at
 * P90 the goal may never be reached), so the wedge is cut here instead — matching the end-label's
 * "90+" display cap on the ± figure.
 */
private const val CONE_GOAL_OVERSHOOT_CAP_DAYS = 90L

/**
 * The date the P90 cone is drawn out to. The cone terminates on the goal line so its intersection
 * with the goal weight reads as the P90 span of goal-arrival dates: the fast edge (slope |r|+c)
 * crosses the goal at hGoal·|r|/(|r|+c) and the slow edge (slope |r|−c) at hGoal·|r|/(|r|−c),
 * where hGoal is the nominal horizon, r the expected rate, and c = [PaceEstimator.CONE_P90_KG_PER_DAY].
 * Returns the slow-edge crossing, capped at hGoal + [CONE_GOAL_OVERSHOOT_CAP_DAYS] (also the
 * fallback when |r| ≤ c and the slow edge never crosses). Null when there is no reachable
 * expected pace.
 */
internal fun coneEndDate(projection: ProjectionUi, lastDataDate: LocalDate): LocalDate? {
    val ready = projection as? ProjectionUi.Ready ?: return null
    val ePace = ready.expectedPace as? PaceUi.Reachable ?: return null
    val hGoal = ChronoUnit.DAYS.between(lastDataDate, ePace.date)
    if (hGoal <= 0) return null
    val rate = abs(ready.expectedRateLbPerDay)
    val cone = PaceEstimator.CONE_P90_KG_PER_DAY * KG_TO_LB
    val hLate = if (rate > cone) ceil(hGoal * rate / (rate - cone)).toLong() else Long.MAX_VALUE
    return lastDataDate.plusDays(minOf(hLate, hGoal + CONE_GOAL_OVERSHOOT_CAP_DAYS))
}

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
internal fun DrawScope.drawTrendChart(
    points: List<WeightPointLb>,
    goalLb: Double?,
    projection: ProjectionUi,
    colors: ChartColors,
    textMeasurer: TextMeasurer,
    xDomain: Pair<LocalDate, LocalDate>? = null,
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

    // X domain — an explicit [xDomain] (from pinch/pan in the full-screen view) overrides the
    // data-derived span. The prediction overlay renders in both cases; the full-screen caller
    // extends its xDomain out to the projection end-dates so the lines fit within the window.
    val historyDates = points.map { it.date }
    val projReady = projection as? ProjectionUi.Ready
    val tMin: LocalDate = xDomain?.first ?: historyDates.first()
    val tMax: LocalDate = xDomain?.second
        ?: listOfNotNull(
            furthestReachableDate(projection),
            coneEndDate(projection, historyDates.last()),
        ).maxOrNull()?.takeIf { it.isAfter(historyDates.last()) }
        ?: historyDates.last()

    val totalDays = ChronoUnit.DAYS.between(tMin, tMax).toFloat().coerceAtLeast(1f)

    fun xOf(date: LocalDate): Float {
        val offset = ChronoUnit.DAYS.between(tMin, date).toFloat()
        return ml + pw * (offset / totalDays)
    }

    // Y domain — include raw, ema, and goal in the range (only points within the visible window
    // when zoomed, so the vertical scale fits what's on screen).
    val yPoints = if (xDomain == null) points
        else points.filter { !it.date.isBefore(tMin) && !it.date.isAfter(tMax) }
    val allYVals = buildList<Double> {
        addAll(yPoints.mapNotNull { it.rawLb })
        addAll(yPoints.map { it.emaLb })
        goalLb?.let { add(it) }
        // The prediction lines run from the last EMA value to the goal; include both endpoints so a
        // window zoomed into the future (with no history points) still fits the lines on-scale.
        if (projReady != null) {
            add(points.last().emaLb)
            goalLb?.let { add(it) }
        }
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

    // Clip the data series to the plot rect so a zoomed/panned window never bleeds into the gutter.
    clipRect(left = ml, top = mt, right = w - mr, bottom = mt + ph) {
        // ---- Raw scatter dots (r≈3.0 at reference width) ----
        val rawRadius = 3.0f * scale
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
    }

    // ---- Prediction overlay ----
    if (projReady != null) {
        val last = points.last()
        val nowX = xOf(last.date)
        val nowY = yOf(last.emaLb)
        val goalY = if (goalLb != null) yOf(goalLb) else nowY
        // An x within the visible plot rect. Labels/divider text anchored well outside it are
        // skipped (e.g. when zoomed into a window that excludes the "now" line or an end-dot).
        fun xVisible(x: Float) = x >= ml && x <= w - mr

        // Shapes (divider + pace lines + end-dots) are clipped to the plot rect so a zoomed/panned
        // window never bleeds into the gutters or axes; labels are drawn unclipped below.
        clipRect(left = ml, top = mt, right = w - mr, bottom = mt + ph) {
            // P90 cone (under everything else): a translucent wedge from the "now" apex opening
            // rightward. Center line = trendNowLb + expectedRateLbPerDay·h; the linearly-growing
            // half-width means both edges are straight, so the wedge is an exact triangle whose apex
            // (h=0, half-width=0) sits on the expected line's start. Drawn out to [coneEndDate] —
            // where the slow cone edge crosses the goal weight — and clipped on the past-goal side
            // at the goal line, so the wedge terminates ON the goal line and its intersection with
            // it reads as the P90 span of goal-arrival dates.
            (projReady.expectedPace as? PaceUi.Reachable)?.let { ePace ->
                val trendNowLb = last.emaLb
                val coneEnd = minOf(coneEndDate(projReady, last.date) ?: ePace.date, tMax)
                val hEnd = ChronoUnit.DAYS.between(last.date, coneEnd)
                if (hEnd > 0) {
                    val coneEndX = xOf(coneEnd)
                    val centerEndLb = trendNowLb + projReady.expectedRateLbPerDay * hEnd
                    val halfEndLb = PaceEstimator.coneHalfWidthKg(hEnd) * KG_TO_LB
                    val cone = Path().apply {
                        moveTo(nowX, nowY)
                        lineTo(coneEndX, yOf(centerEndLb + halfEndLb))
                        lineTo(coneEndX, yOf(centerEndLb - halfEndLb))
                        close()
                    }
                    val clipTop = if (goalLb != null && goalLb > trendNowLb) goalY else mt
                    val clipBottom = if (goalLb != null && goalLb < trendNowLb) goalY else mt + ph
                    clipRect(left = ml, top = clipTop, right = w - mr, bottom = clipBottom) {
                        drawPath(cone, color = colors.projectionExpected.copy(alpha = colors.projectionConeAlpha))
                    }
                }
            }

            // "now" vertical divider
            drawLine(
                color = Color(0xFFCFC8DB),
                start = Offset(nowX, mt),
                end = Offset(nowX, mt + ph),
                strokeWidth = 1f,
                pathEffect = DASH_3_3,
            )
            (projReady.goalPace as? PaceUi.Reachable)?.let { gPace ->
                val endX = xOf(gPace.date)
                drawLine(
                    color = colors.projectionGoal,
                    start = Offset(nowX, nowY),
                    end = Offset(endX, goalY),
                    strokeWidth = 2.4f * scale,
                    pathEffect = DASH_7_5,
                )
                drawCircle(color = colors.projectionGoal, radius = 4f * scale, center = Offset(endX, goalY))
            }
            (projReady.currentPace as? PaceUi.Reachable)?.let { cPace ->
                val endX = xOf(cPace.date)
                drawLine(
                    color = colors.projectionCurrent,
                    start = Offset(nowX, nowY),
                    end = Offset(endX, goalY),
                    strokeWidth = 2.4f * scale,
                    pathEffect = DASH_7_5,
                )
                drawCircle(color = colors.projectionCurrent, radius = 4f * scale, center = Offset(endX, goalY))
            }
            (projReady.expectedPace as? PaceUi.Reachable)?.let { ePace ->
                val endX = xOf(ePace.date)
                drawLine(
                    color = colors.projectionExpected,
                    start = Offset(nowX, nowY),
                    end = Offset(endX, goalY),
                    strokeWidth = 2.4f * scale,
                    pathEffect = DASH_7_5,
                )
                drawCircle(color = colors.projectionExpected, radius = 4f * scale, center = Offset(endX, goalY))
            }
        }

        // "now" label
        if (xVisible(nowX)) {
            val nowM = textMeasurer.measure("now", TextStyle(fontSize = 9.sp, color = Color(0xFF8A7FB0)))
            drawText(nowM, topLeft = Offset(nowX - nowM.size.width / 2f, mt + 2f))
        }

        // Goal pace label (above the end-dot, clamped to the canvas width)
        val gPace = projReady.goalPace
        if (gPace is PaceUi.Reachable) {
            val endX = xOf(gPace.date)
            if (xVisible(endX)) {
                val gLbl = "goal pace: ${gPace.date.format(DATE_FMT_LONG)}"
                val gM = textMeasurer.measure(
                    gLbl,
                    TextStyle(fontSize = 10.sp, color = colors.projectionGoal, fontWeight = FontWeight.SemiBold),
                )
                drawText(gM, topLeft = Offset((endX - gM.size.width / 2f).coerceIn(2f, w - gM.size.width - 2f), goalY - gM.size.height - 10f))
            }
        }

        // Current pace label (below the end-dot) or "not on track" note
        when (val cPace = projReady.currentPace) {
            is PaceUi.Reachable -> {
                val endX = xOf(cPace.date)
                if (xVisible(endX)) {
                    val cLbl = "current pace: ${cPace.date.format(DATE_FMT_LONG)}"
                    val cM = textMeasurer.measure(
                        cLbl,
                        TextStyle(fontSize = 10.sp, color = colors.projectionCurrent, fontWeight = FontWeight.SemiBold),
                    )
                    drawText(cM, topLeft = Offset((endX - cM.size.width / 2f).coerceIn(2f, w - cM.size.width - 2f), goalY + 16f))
                }
            }

            is PaceUi.Unreachable -> {
                // Show "not on track" note near the bottom of the "now" line
                if (xVisible(nowX)) {
                    val noteM = textMeasurer.measure(
                        "current pace: not on track",
                        TextStyle(fontSize = 9.sp, color = colors.projectionCurrent),
                    )
                    drawText(noteM, topLeft = Offset(nowX + 4f, mt + ph - noteM.size.height - 4f))
                }
            }
        }

        // Expected pace label (below its end-dot): "expected: <date> ± <n> d", where n is the
        // cone half-width at the goal converted to days via the expected rate.
        val ePace = projReady.expectedPace
        if (ePace is PaceUi.Reachable) {
            val endX = xOf(ePace.date)
            if (xVisible(endX)) {
                val hGoal = ChronoUnit.DAYS.between(last.date, ePace.date)
                val rateKgPerDay = ePace.rateLbPerDay / KG_TO_LB
                val nDays = if (rateKgPerDay == 0.0) 999L
                    else (PaceEstimator.coneHalfWidthKg(hGoal) / abs(rateKgPerDay)).roundToLong().coerceAtMost(999L)
                // Display cap: a slow rate divides into huge ± values that carry no information.
                val nText = if (nDays > 90L) "90+" else "$nDays"
                val eLbl = "expected: ${ePace.date.format(DATE_FMT_LONG)} ± $nText d"
                val eM = textMeasurer.measure(
                    eLbl,
                    TextStyle(fontSize = 10.sp, color = colors.projectionExpected, fontWeight = FontWeight.SemiBold),
                )
                // One full label-height below the current-pace label (which sits at goalY + 16f),
                // so the two 10.sp labels never overlap at any density.
                drawText(eM, topLeft = Offset((endX - eM.size.width / 2f).coerceIn(2f, w - eM.size.width - 2f), goalY + 16f + eM.size.height + 4f))
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

// ---------------------------------------------------------------------------
// Canvas drawing — matches donut() geometry from design/charts_gen.py
// ---------------------------------------------------------------------------

/**
 * Draws the macro donut on the current (square) Canvas.
 *
 * Geometry mirrors donut() in design/charts_gen.py:
 *   - 300×300 viewBox, cx=cy=150, r=95, stroke-width=34
 *   - Arc segments: protein (proteinColor), fat (fatColor), carbs (carbColor)
 *   - kcal total in center + "kcal so far" / "avg kcal/day" subtitle
 */
private fun DrawScope.drawDonut(
    summary: MacroSummary,
    isToday: Boolean,
    colors: ChartColors,
    textMeasurer: TextMeasurer,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    // Scale radius and stroke-width from the 300×300 reference
    val scale = size.width / 300f
    val r = 95f * scale
    val sw = 34f * scale

    val proteinKcal = summary.proteinG * 4.0
    val fatKcal = summary.fatG * 9.0
    val carbKcal = summary.carbG * 4.0
    val total = (proteinKcal + fatKcal + carbKcal).coerceAtLeast(1.0)
    val cal = (summary.kcal).toInt()

    val circumference = 2f * Math.PI.toFloat() * r

    // Background ring (light color)
    drawCircle(
        color = colors.proteinColor.copy(alpha = 0.12f),
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = sw),
    )

    // Draw three arc segments using drawArc with stroke style.
    // drawArc angles: 0 = 3 o'clock, rotate -90 to start at 12 o'clock.
    // We use useCenter=false (arc only, not a pie slice) with a Stroke style.
    var startAngle = -90f  // start at top (12 o'clock)

    for ((kcal, color) in listOf(
        proteinKcal to colors.proteinColor,
        fatKcal to colors.fatColor,
        carbKcal to colors.carbColor,
    )) {
        val sweepDeg = (kcal / total * 360.0).toFloat()
        if (sweepDeg > 0f) {
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweepDeg,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = sw, cap = StrokeCap.Butt),
            )
        }
        startAngle += sweepDeg
    }

    // Center text: calorie total
    val kcalStyle = TextStyle(
        fontSize = (26f * scale).sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.axisLabel,
        textAlign = TextAlign.Center,
    )
    val kcalM = textMeasurer.measure(cal.toString(), kcalStyle)
    drawText(kcalM, topLeft = Offset(cx - kcalM.size.width / 2f, cy - kcalM.size.height / 2f - 8f * scale))

    val subLabel = if (isToday) "kcal so far" else "avg kcal/day"
    val subStyle = TextStyle(
        fontSize = (11f * scale).sp,
        color = colors.axisLabel,
        textAlign = TextAlign.Center,
    )
    val subM = textMeasurer.measure(subLabel, subStyle)
    drawText(subM, topLeft = Offset(cx - subM.size.width / 2f, cy + kcalM.size.height / 2f - 4f * scale))
}

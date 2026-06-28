package com.tdee.app.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.tdee.app.ui.theme.LocalChartColors
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/** Which chart a [ChartDetailScreen] / "Expand" affordance refers to. */
enum class ChartType { TREND, EXPENDITURE }

/**
 * Pinch-zoom + horizontal-pan state for the full-screen chart.
 *
 * The data x-axis spans `[dataMin, dataMax]`. [scale] (≥1) shrinks the visible window to
 * `span / scale`; [panFraction] is the left edge of that window as a fraction of the full span.
 * Both are clamped so the window can never leave the data bounds. y auto-fits to the window in the
 * draw functions (they filter points to the returned [visibleWindow]).
 */
class ChartTransformState {
    var scale by mutableFloatStateOf(1f)
        private set
    var panFraction by mutableFloatStateOf(0f)
        private set

    /** Apply one transform gesture frame. [plotWidthPx] is the plot area width in pixels. */
    fun onGesture(panXpx: Float, zoom: Float, plotWidthPx: Float) {
        val oldScale = scale
        val newScale = (oldScale * zoom).coerceIn(1f, MAX_SCALE)
        // Keep the window center fixed through a pinch so zoom feels anchored.
        val center = panFraction + 0.5f / oldScale
        var newPan = center - 0.5f / newScale
        // Dragging content right (positive panX) reveals earlier dates → window moves left.
        if (plotWidthPx > 0f) newPan += -panXpx / (newScale * plotWidthPx)
        scale = newScale
        panFraction = newPan.coerceIn(0f, 1f - 1f / newScale)
    }

    fun reset() {
        scale = 1f
        panFraction = 0f
    }

    /** The visible date window for the current zoom/pan, clamped to `[dataMin, dataMax]`. */
    fun visibleWindow(dataMin: LocalDate, dataMax: LocalDate): Pair<LocalDate, LocalDate> {
        val totalDays = ChronoUnit.DAYS.between(dataMin, dataMax).coerceAtLeast(1L)
        if (scale <= 1f) return dataMin to dataMax
        val windowDays = (totalDays / scale).coerceAtLeast(1f)
        val startDays = (panFraction * totalDays).coerceIn(0f, totalDays - windowDays)
        val winMin = dataMin.plusDays(startDays.roundToLong())
        val winMaxRaw = winMin.plusDays(windowDays.roundToLong().coerceAtLeast(1L))
        val winMax = if (winMaxRaw.isAfter(dataMax)) dataMax else winMaxRaw
        return winMin to winMax
    }

    companion object {
        const val MAX_SCALE = 12f
    }
}

/**
 * Full-screen single-chart view (note 13). Fills the screen and adapts to landscape (the manifest
 * imposes no orientation lock), and supports pinch-to-zoom + drag-to-pan (note 12). Reuses the same
 * [InsightsViewModel] data and range pills; the prediction overlay is intentionally omitted here so
 * the zoom window stays bounded to real data.
 */
@Composable
fun ChartDetailScreen(
    viewModel: InsightsViewModel,
    type: ChartType,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val transform = remember(type) { ChartTransformState() }
    val chartColors = LocalChartColors.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    var plotWidthPx by remember { mutableFloatStateOf(1f) }

    val title = when (type) {
        ChartType.TREND -> "Weight Trend"
        ChartType.EXPENDITURE -> "Expenditure"
    }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(onClick = { transform.reset() }) { Text("Reset") }
                TextButton(onClick = onBack) { Text("Back") }
            }
        }

        // Range pills (reuse the same range state as Insights)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            when (type) {
                ChartType.TREND -> WeightRange.values().forEach { r ->
                    Pill(r.label, r == state.selectedRange) {
                        viewModel.setRange(r); transform.reset()
                    }
                }
                ChartType.EXPENDITURE -> ExpenditureRange.values().forEach { r ->
                    Pill(r.label, r == state.expenditureRange) {
                        viewModel.setExpenditureRange(r); transform.reset()
                    }
                }
            }
        }

        // Chart fills the remaining space (portrait or landscape).
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val hasData = when (type) {
                ChartType.TREND -> state.visiblePoints.size >= 2
                ChartType.EXPENDITURE -> state.visibleExpenditurePoints.size >= 2
            }
            if (!hasData) {
                Text(
                    "Not enough data yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { plotWidthPx = it.width.toFloat() * (804f / 880f) }
                        .pointerInput(type) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                transform.onGesture(pan.x, zoom, plotWidthPx)
                            }
                        },
                ) {
                    when (type) {
                        ChartType.TREND -> {
                            val pts = state.visiblePoints
                            val win = transform.visibleWindow(pts.first().date, pts.last().date)
                            drawTrendChart(
                                points = pts,
                                goalLb = (state.projection as? ProjectionUi.Ready)?.goalLb,
                                projection = ProjectionUi.NoGoal,
                                colors = chartColors,
                                textMeasurer = textMeasurer,
                                xDomain = win,
                            )
                        }
                        ChartType.EXPENDITURE -> {
                            val pts = state.visibleExpenditurePoints
                            val win = transform.visibleWindow(pts.first().date, pts.last().date)
                            drawExpenditureChart(
                                points = pts,
                                colors = chartColors,
                                textMeasurer = textMeasurer,
                                xDomain = win,
                            )
                        }
                    }
                }
            }
        }

        Text(
            "Pinch to zoom · drag to pan · rotate for landscape",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

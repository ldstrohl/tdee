package com.tdee.app.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import com.tdee.app.data.MacroSummary
import com.tdee.app.ui.theme.ChartColors
import com.tdee.app.ui.theme.LocalChartColors

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

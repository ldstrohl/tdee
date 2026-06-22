package com.tdee.app.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic chart color palette.  Light and dark variants are defined in
 * [Theme.kt] and provided via [LocalChartColors] by [TdeeTheme], so chart
 * composables stay theme-agnostic:
 *
 *   val c = LocalChartColors.current
 *   drawLine(color = c.emaLine, ...)
 */
data class ChartColors(
    /** Raw daily weight scatter points. */
    val rawPoint: Color,
    /** Exponential moving-average weight trend line. */
    val emaLine: Color,
    /** Goal-weight horizontal reference line. */
    val goalLine: Color,
    /** Daily calorie intake bar fill. */
    val intakeBar: Color,
    /** TDEE estimate reference line. */
    val tdeeLine: Color,
    /** Deficit / surplus shaded region fill. */
    val deficitFill: Color,
    /** Protein macro segment / bar color. */
    val proteinColor: Color,
    /** Fat macro segment / bar color. */
    val fatColor: Color,
    /** Carbohydrate macro segment / bar color. */
    val carbColor: Color,
    /** Projection line — goal trajectory. */
    val projectionGoal: Color,
    /** Projection line — current-rate trajectory. */
    val projectionCurrent: Color,
    /** Chart grid lines. */
    val gridLine: Color,
    /** Chart axis labels. */
    val axisLabel: Color,
    /** Chart background / surface. */
    val chartSurface: Color,
)

/**
 * CompositionLocal that provides the active [ChartColors].
 * Always consumed inside [TdeeTheme], which provides a non-null value.
 * Throws if accessed outside a [TdeeTheme] — this is intentional; chart
 * composables require a themed context.
 */
val LocalChartColors = compositionLocalOf<ChartColors> {
    error("LocalChartColors not provided — wrap your chart composable inside TdeeTheme.")
}

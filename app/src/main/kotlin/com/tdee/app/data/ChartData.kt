package com.tdee.app.data

import com.tdee.domain.Projection
import com.tdee.domain.Targets
import java.time.LocalDate

// ---------------------------------------------------------------------------
// Weight series
// ---------------------------------------------------------------------------

/**
 * One data point in the weight-over-time chart.
 *
 * @param rawKg that day's first-of-day measurement in kg, or null if no measurement was recorded.
 * @param emaKg EMA trend weight in kg as produced by the engine for that day. Always present for
 *   every day from the first measurement through today.
 */
data class DayWeightPoint(
    val date: LocalDate,
    val rawKg: Double?,
    val emaKg: Double,
)

// ---------------------------------------------------------------------------
// Expenditure series
// ---------------------------------------------------------------------------

/**
 * One data point in the intake-vs-expenditure chart.
 *
 * @param intakeKcal that day's logged intake in kcal, or null if the day was not fully logged
 *   (missing/incomplete). Never zero-filled.
 * @param tdeeKcal TDEE estimate in kcal as produced by the engine for that day.
 * @param calibrating true when the engine was still within its initial calibration horizon on that
 *   day (first ~2 weeks of paired data days).
 */
data class DayExpenditurePoint(
    val date: LocalDate,
    val intakeKcal: Double?,
    val tdeeKcal: Double,
    val calibrating: Boolean,
)

// ---------------------------------------------------------------------------
// Macro summary
// ---------------------------------------------------------------------------

/**
 * Time window for [TdeeRepository.macroSummary].
 *
 * TODAY = today's logged totals (not an average).
 * All other windows = per-day averages over complete logging days only within the window.
 */
enum class ChartWindow { TODAY, M1, M3, M6, Y1, ALL }

/**
 * Macro and calorie summary for a given [ChartWindow].
 *
 * For TODAY: fields represent the day's running totals; [completeDays] and [totalDays] are
 * both 1 (semantically N/A — treat them as informational only).
 *
 * For all other windows: fields are per-day averages over complete logging days in the window.
 * [completeDays] is the count of days that had at least one food entry (complete=true);
 * [totalDays] is the count of calendar days in the window.
 *
 * All macro values are in grams; [kcal] is in kcal. Units are canonical (no lb/oz).
 */
data class MacroSummary(
    val proteinG: Double,
    val fatG: Double,
    val carbG: Double,
    val kcal: Double,
    val completeDays: Int,
    val totalDays: Int,
    val targets: Targets,
)

// ---------------------------------------------------------------------------
// Weight projection
// ---------------------------------------------------------------------------

/**
 * Dual projection for the goal-weight chart.
 *
 * [goalPace] projects arrival at [goalKg] using the profile's [UserProfile.goalRateKgPerWeek].
 * [currentPace] projects arrival using the slope observed in the recent EMA window.
 * [expectedPace] projects arrival using the λ-blended expected rate (recent + long-run) from
 * `PaceEstimator`; [expectedRateKgPerDay] is that blended rate (needed for the P90 cone endpoints).
 *
 * Any projection may be [Projection.Unreachable] (e.g. a pace is flat or moving away from goal).
 *
 * All weights are in kg; UI converts to the display unit.
 */
data class WeightProjection(
    val currentTrendKg: Double,
    val goalKg: Double,
    val goalPace: Projection,
    val currentPace: Projection,
    val expectedPace: Projection,
    val expectedRateKgPerDay: Double,
)

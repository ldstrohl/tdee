package com.tdee.domain

import java.time.Instant
import java.time.LocalDate

/*
 * Canonical units for this module (dimensionally consistent; conversions live only at the UI edge):
 *   mass = kg, energy = kcal, length = cm, time = java.time (windows in days).
 * Energy is kept in kcal (not joules) because every energy source — USDA, Mifflin–St Jeor,
 * the energy-density constant, the user — speaks kcal; joules would only add boundaries.
 * Field names carry their unit (e.g. valueKcal, *Kg, *G) to keep this unambiguous in code.
 */

/** Biological sex, used by the Mifflin–St Jeor RMR formula. */
enum class Sex { MALE, FEMALE }

/**
 * Activity level with its associated TDEE multiplier applied to RMR
 * (standard Mifflin–St Jeor activity factors).
 */
enum class ActivityLevel(val multiplier: Double) {
    SEDENTARY(1.2),
    LIGHT(1.375),
    MODERATE(1.55),
    ACTIVE(1.725),
    VERY_ACTIVE(1.9),
}

/**
 * Provenance of a weight sample. Placeholder for future per-sample variance
 * weighting; for the MVP it is carried through but does not change results.
 */
enum class SampleQuality { MANUAL, DEVICE }

/**
 * User configuration driving all engine math. All weights/energy are in kg/kcal.
 *
 * @param goalRateKgPerWeek negative = loss, 0 = maintain, positive = gain.
 * @param dayStartHour custom log-day boundary hour (0..23); a day "starts" at this hour.
 * @param energyDensityKcalPerKg energy density of body mass change (default 7700 kcal/kg).
 */
data class UserProfile(
    val sex: Sex,
    val birthYear: Int,
    val heightCm: Double,
    val activityLevel: ActivityLevel,
    val goalRateKgPerWeek: Double,
    val goalWeightKg: Double? = null,
    val proteinGPerKg: Double = 2.0,
    val fatPctOfCalories: Double = 0.25,
    val dayStartHour: Int = 0,
    val smoothingWindowDays: Int = 14,
    val tdeeWindowDays: Int = 14,
    val energyDensityKcalPerKg: Double = 7700.0,
)

/** A raw, timestamped weigh-in. The raw stream is never pre-aggregated. */
data class WeightSample(val t: Instant, val kg: Double, val quality: SampleQuality)

/**
 * Intake for one log-day, already bucketed by the data layer using the same
 * dayStartHour rule the engine uses for weight.
 *
 * @param complete false means the day's intake is partial/unknown — such days
 *   are *missing data*, never treated as 0 kcal.
 */
data class DailyIntake(val date: LocalDate, val kcal: Double, val complete: Boolean)

/** Which method produced a [TdeeEstimate]. */
enum class TdeeMethod { FORMULA, BLEND, EMPIRICAL }

/**
 * A TDEE estimate.
 *
 * @param uncertaintyKcal a standard error in kcal (stable physical unit, not an
 *   abstract score). This is the slot a real posterior SE (e.g. from an EKF)
 *   replaces. Consumers should read only [calibrating] and [valueKcal].
 * @param calibrating true while the engine has fewer than a full window of
 *   paired data days (the intentionally trivial MVP rule).
 */
data class TdeeEstimate(
    val valueKcal: Double,
    val method: TdeeMethod,
    val uncertaintyKcal: Double,
    val calibrating: Boolean,
)

/** Daily macro/calorie targets derived from a TDEE estimate and the profile goal. */
data class Targets(
    val calorieTargetKcal: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbG: Double,
)

/** Result of projecting when a goal weight is reached at a given intake. */
sealed interface Projection {
    data class Reachable(val predictedDate: LocalDate, val rateKgPerDay: Double) : Projection
    data class Unreachable(val reason: String) : Projection
}

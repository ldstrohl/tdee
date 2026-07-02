package com.tdee.app.data

import com.tdee.domain.DailyIntake
import com.tdee.domain.SampleQuality
import com.tdee.domain.UserProfile
import com.tdee.domain.WeightSample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Map an instant to its log-day using the same rule as [com.tdee.domain.DefaultTdeeEngine]:
 *   shift back by dayStartHour hours, then take the local date in the given zone.
 *
 * This is intentionally byte-for-byte identical to the engine's private logDay():
 *   t.minusSeconds(dayStartHour * 3600L).atZone(zone).toLocalDate()
 */
fun logDay(t: Instant, zone: ZoneId, dayStartHour: Int): LocalDate =
    t.minusSeconds(dayStartHour * 3600L).atZone(zone).toLocalDate()

// ---------------------------------------------------------------------------
// WeightEntryEntity → WeightSample
// ---------------------------------------------------------------------------

/**
 * Map a list of [WeightEntryEntity] rows to [WeightSample] domain objects.
 * No aggregation is performed here — the engine reduces to first-of-day internally.
 */
fun List<WeightEntryEntity>.toWeightSamples(): List<WeightSample> = map { entity ->
    WeightSample(
        t = entity.timestamp,
        kg = entity.weightKg,
        quality = when (entity.source) {
            WeightSource.HEALTH_CONNECT -> SampleQuality.DEVICE
            WeightSource.MANUAL -> SampleQuality.MANUAL
        },
    )
}

// ---------------------------------------------------------------------------
// FoodEntryEntity → DailyIntake
// ---------------------------------------------------------------------------

/**
 * Map a list of [FoodEntryEntity] rows to [DailyIntake] domain objects.
 *
 * Only non-deleted entries are considered. Entries are bucketed into log-days
 * using the same [logDay] rule the engine uses. Each log-day that has at least
 * one entry emits exactly one [DailyIntake] with the summed kcal and
 * complete = true. Days with no entries are not emitted (the engine treats
 * missing days as incomplete/unknown — never 0 kcal).
 */
fun List<FoodEntryEntity>.toDailyIntake(zone: ZoneId, dayStartHour: Int): List<DailyIntake> =
    asSequence()
        .filter { it.deletedAt == null }
        .groupBy { logDay(it.timestamp, zone, dayStartHour) }
        .map { (date, entries) ->
            DailyIntake(
                date = date,
                kcal = entries.sumOf { it.kcal },
                complete = true,
            )
        }
        .sortedBy { it.date }

// ---------------------------------------------------------------------------
// UserProfileEntity → UserProfile
// ---------------------------------------------------------------------------

/**
 * Map [UserProfileEntity] to the domain [UserProfile].
 * energyDensityKcalPerKg is left at its domain default (7700) since the DB
 * carries no energy_density column per the spec.
 */
fun UserProfileEntity.toDomain(): UserProfile = UserProfile(
    sex = sex,
    birthYear = birthYear,
    heightCm = heightCm,
    activityLevel = activityLevel,
    goalRateKgPerWeek = goalRateKgPerWeek,
    goalWeightKg = goalWeightKg,
    proteinGPerKg = proteinGPerKg,
    fatPctOfCalories = fatPctOfCalories,
    dayStartHour = dayStartHour,
    smoothingWindowDays = smoothingWindowDays,
    // tdeeWindowDays intentionally omitted — the empirical window is an engine constant
    // (180 d), not user-configurable data, so use the domain default rather than the
    // legacy persisted value (the tdeeWindowDays column is now vestigial).
    // energyDensityKcalPerKg likewise omitted — uses domain default of 7700.0.
)

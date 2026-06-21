package com.tdee.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Time-queryable TDEE/weight-trend engine. This interface is the EKF seam:
 * a future Extended-Kalman-Filter engine is simply another implementation,
 * with all windowing/blend/aggregation logic hidden behind these two queries.
 */
interface TdeeEngine {
    /** EMA trend weight in kg evaluated at the log-day containing [asOf]. */
    fun weightTrendAt(asOf: Instant): Double

    /** TDEE estimate evaluated as of [asOf]. */
    fun estimateAt(asOf: Instant): TdeeEstimate
}

/**
 * Default windowed engine.
 *
 * Construction takes the raw data, the profile, and an explicit [zone] so that
 * log-day bucketing is a pure function of inputs (testable with fixed instants).
 */
class DefaultTdeeEngine(
    private val samples: List<WeightSample>,
    private val intake: List<DailyIntake>,
    private val profile: UserProfile,
    private val zone: ZoneId,
) : TdeeEngine {

    /**
     * Map an instant to its log-day: shift back by [UserProfile.dayStartHour]
     * hours before taking the local date, so a custom day boundary works.
     */
    private fun logDay(t: Instant): LocalDate =
        t.minusSeconds(profile.dayStartHour.toLong() * 3600)
            .atZone(zone)
            .toLocalDate()

    /**
     * Per-day weight aggregation: reduce each log-day's samples to the FIRST
     * (earliest timestamp) sample of that day. Returns a sorted map keyed by day.
     */
    private fun aggregatedDailyWeights(): Map<LocalDate, Double> {
        val byDay = HashMap<LocalDate, WeightSample>()
        for (s in samples) {
            val day = logDay(s.t)
            val cur = byDay[day]
            if (cur == null || s.t.isBefore(cur.t)) byDay[day] = s
        }
        return byDay.mapValues { it.value.kg }
    }

    /**
     * Build the dense daily EMA series from the first aggregated weight day
     * through [endDay] (inclusive). On days without a measurement the EMA is
     * carried forward (EMA_today = EMA_prev). Seed = first day's weight.
     *
     * Returns an empty map if there are no samples or [endDay] precedes the
     * first measured day.
     */
    private fun emaSeries(endDay: LocalDate): Map<LocalDate, Double> {
        val daily = aggregatedDailyWeights()
        if (daily.isEmpty()) return emptyMap()
        val firstDay = daily.keys.min()
        if (endDay.isBefore(firstDay)) return emptyMap()

        val alpha = 2.0 / (profile.smoothingWindowDays + 1)
        val series = LinkedHashMap<LocalDate, Double>()
        var ema = daily.getValue(firstDay)
        series[firstDay] = ema
        var day = firstDay.plusDays(1)
        while (!day.isAfter(endDay)) {
            val w = daily[day]
            ema = if (w != null) alpha * w + (1 - alpha) * ema else ema
            series[day] = ema
            day = day.plusDays(1)
        }
        return series
    }

    override fun weightTrendAt(asOf: Instant): Double {
        val endDay = logDay(asOf)
        val series = emaSeries(endDay)
        // If no EMA is defined yet (no samples on/before endDay), fall back to
        // the latest raw sample at/before asOf, else the latest raw sample, else NaN.
        return series[endDay]
            ?: samples.filter { !it.t.isAfter(asOf) }.maxByOrNull { it.t }?.kg
            ?: samples.maxByOrNull { it.t }?.kg
            ?: Double.NaN
    }

    /** Mifflin–St Jeor formula seed (RMR * activity multiplier). */
    private fun formulaTdee(asOf: Instant): Double {
        val age = asOf.atZone(zone).year - profile.birthYear
        val kg = weightTrendAt(asOf)
        val cm = profile.heightCm
        val rmr = when (profile.sex) {
            Sex.MALE -> 10 * kg + 6.25 * cm - 5 * age + 5
            Sex.FEMALE -> 10 * kg + 6.25 * cm - 5 * age - 161
        }
        return rmr * profile.activityLevel.multiplier
    }

    /**
     * Holder for empirical computation, including how many complete paired
     * data days were available within the window.
     */
    private data class Empirical(val tdee: Double?, val dataDays: Int)

    /**
     * Empirical TDEE over the trailing W-day window ending the day BEFORE
     * [asOf]'s log-day (the in-progress current day is excluded).
     *
     * Window edge choice (documented judgment call): the window covers the W
     * log-days [windowStart .. windowEnd] inclusive where windowEnd = the day
     * before logDay(asOf) and windowStart = windowEnd - (W-1). avg_intake is the
     * mean over COMPLETE intake days falling in that inclusive range. The trend
     * delta uses EMA(windowEnd) - EMA(windowStart) when both are defined.
     *
     * dataDays = count of days in the window that have BOTH a defined EMA value
     * and a complete intake entry — the paired-data measure that drives the blend.
     */
    private fun empirical(asOf: Instant): Empirical {
        val w = profile.tdeeWindowDays
        val today = logDay(asOf)
        val windowEnd = today.minusDays(1)
        val windowStart = windowEnd.minusDays((w - 1).toLong())

        val series = emaSeries(windowEnd)
        val intakeByDay = intake.associateBy { it.date }

        // Paired data days: defined EMA AND complete intake, within the window.
        var dataDays = 0
        var day = windowStart
        while (!day.isAfter(windowEnd)) {
            val hasEma = series.containsKey(day)
            val hasIntake = intakeByDay[day]?.complete == true
            if (hasEma && hasIntake) dataDays++
            day = day.plusDays(1)
        }
        if (dataDays == 0) return Empirical(null, 0)

        // avg_intake over complete days in the window.
        val completeKcals = intake.filter {
            it.complete && !it.date.isBefore(windowStart) && !it.date.isAfter(windowEnd)
        }.map { it.kcal }
        if (completeKcals.isEmpty()) return Empirical(null, dataDays)
        val avgIntake = completeKcals.average()

        val emaEnd = series[windowEnd]
        val emaStart = series[windowStart]
        if (emaEnd == null || emaStart == null) return Empirical(null, dataDays)

        val trendDeltaKg = emaEnd - emaStart
        val storedKcalPerDay = trendDeltaKg * profile.energyDensityKcalPerKg / w
        val tdee = avgIntake - storedKcalPerDay
        return Empirical(tdee, dataDays)
    }

    override fun estimateAt(asOf: Instant): TdeeEstimate {
        val w = profile.tdeeWindowDays
        val formula = formulaTdee(asOf)
        val emp = empirical(asOf)
        val dataDays = emp.dataDays
        val empiricalTdee = emp.tdee

        val (value, method) = when {
            dataDays == 0 || empiricalTdee == null -> formula to TdeeMethod.FORMULA
            dataDays >= w -> empiricalTdee to TdeeMethod.EMPIRICAL
            else -> {
                val weight = dataDays.toDouble() / w
                ((1 - weight) * formula + weight * empiricalTdee) to TdeeMethod.BLEND
            }
        }

        return TdeeEstimate(
            valueKcal = value,
            method = method,
            uncertaintyKcal = uncertainty(dataDays, w),
            calibrating = dataDays < w,
        )
    }

    /**
     * Coarse standard-error proxy (kcal). Linearly interpolates from a base SE
     * in the formula regime (dataDays = 0) down to a small floor once a full
     * window is available (dataDays >= W). This is a placeholder shape, NOT a
     * statistically derived posterior SE; it exists so consumers have a stable
     * kcal-valued uncertainty slot that a real EKF posterior SE will replace.
     */
    private fun uncertainty(dataDays: Int, w: Int): Double {
        val baseSe = 500.0
        val floorSe = 75.0
        val frac = (dataDays.toDouble() / w).coerceIn(0.0, 1.0)
        return baseSe - (baseSe - floorSe) * frac
    }
}

package com.tdee.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

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
     * Holder for the empirical computation. [tdee] is null when there is no usable
     * empirical signal (fall back to pure formula). [span] is the ACTUAL number of
     * days between the two EMA endpoints, [dataDays] the count of paired data days
     * (defined EMA AND complete intake) in the window — it drives the method label
     * and [TdeeEstimate.calibrating] — and [intakeDays] the count of complete intake
     * days averaged, which feeds the empirical standard error.
     */
    private data class Empirical(
        val tdee: Double?,
        val span: Int,
        val dataDays: Int,
        val intakeDays: Int,
    )

    /**
     * Empirical TDEE over the trailing window ending the day BEFORE [asOf]'s log-day
     * (the in-progress current day is excluded).
     *
     * Anchored-window / actual-span form (the fix that makes a long W usable from
     * day ~2): windowEnd = the day before logDay(asOf); the naive start is
     * windowEnd-(W-1), but the window START is anchored forward to the first
     * aggregated weigh-in day so an empirical signal exists as soon as there are two
     * EMA endpoints. [span] is the true day-count between those endpoints, and the
     * trend delta is divided by [span] (not a fixed W). avg_intake is the mean over
     * COMPLETE intake days in [windowStart .. windowEnd].
     *
     * dataDays = count of days in the window with BOTH a defined EMA value and a
     * complete intake entry — the paired-data measure that classifies the estimate.
     */
    private fun empirical(asOf: Instant): Empirical {
        val w = profile.tdeeWindowDays
        val today = logDay(asOf)
        val windowEnd = today.minusDays(1)

        val daily = aggregatedDailyWeights()
        if (daily.isEmpty()) return Empirical(null, 0, 0, 0)
        val firstWeightDay = daily.keys.min()

        // Anchor the start to the first weigh-in so the empirical term exists early.
        val naiveStart = windowEnd.minusDays((w - 1).toLong())
        val windowStart = if (naiveStart.isBefore(firstWeightDay)) firstWeightDay else naiveStart
        if (windowEnd.isBefore(windowStart)) return Empirical(null, 0, 0, 0)
        val span = ChronoUnit.DAYS.between(windowStart, windowEnd).toInt()
        if (span < 1) return Empirical(null, 0, 0, 0)

        val series = emaSeries(windowEnd)
        val emaEnd = series[windowEnd]
        val emaStart = series[windowStart]
        if (emaEnd == null || emaStart == null) return Empirical(null, span, 0, 0)

        val intakeByDay = intake.associateBy { it.date }
        var dataDays = 0
        val completeKcals = ArrayList<Double>()
        var day = windowStart
        while (!day.isAfter(windowEnd)) {
            val di = intakeByDay[day]
            if (di != null && di.complete) {
                completeKcals.add(di.kcal)
                if (series.containsKey(day)) dataDays++
            }
            day = day.plusDays(1)
        }
        if (completeKcals.isEmpty()) return Empirical(null, span, dataDays, 0)

        val avgIntake = completeKcals.average()
        val storedKcalPerDay = (emaEnd - emaStart) * profile.energyDensityKcalPerKg / span
        return Empirical(avgIntake - storedKcalPerDay, span, dataDays, completeKcals.size)
    }

    override fun estimateAt(asOf: Instant): TdeeEstimate {
        val w = profile.tdeeWindowDays
        val formula = formulaTdee(asOf)
        val emp = empirical(asOf)
        val empiricalTdee = emp.tdee

        // Precision-weighted (inverse-variance) shrinkage of the Mifflin prior toward
        // the empirical estimate: est = (w_f·formula + w_e·emp)/(w_f+w_e), w = 1/SE².
        // The posterior SE is sqrt(1/(w_f+w_e)). With no empirical signal this reduces
        // to the pure formula prior (posterior SE = SE_FORMULA).
        val wF = 1.0 / (SE_FORMULA * SE_FORMULA)
        if (empiricalTdee == null) {
            return TdeeEstimate(
                valueKcal = formula,
                method = TdeeMethod.FORMULA,
                uncertaintyKcal = sqrt(1.0 / wF),
                calibrating = true,
            )
        }

        val seEmp = seEmpirical(emp.span, emp.intakeDays)
        val wE = 1.0 / (seEmp * seEmp)
        val value = (wF * formula + wE * empiricalTdee) / (wF + wE)

        return TdeeEstimate(
            valueKcal = value,
            method = if (emp.dataDays >= w) TdeeMethod.EMPIRICAL else TdeeMethod.BLEND,
            uncertaintyKcal = sqrt(1.0 / (wF + wE)),
            calibrating = emp.dataDays < CALIBRATION_DAYS,
        )
    }

    /**
     * Analytic standard error of the empirical estimator (kcal):
     *   Var = σ_I²/n  +  (ρ/span)² · 2 · Var(EMA),
     *   Var(EMA) = σ_W² · α/(2−α),  α = 2/(N+1)   (steady-state EMA variance)
     * The two EMA endpoints ~span days apart are treated as independent (factor 2),
     * so doubling the span quarters the trend-delta noise — the term that lets the
     * empirical estimate earn trust quickly as the window fills.
     */
    private fun seEmpirical(span: Int, intakeDays: Int): Double {
        val alpha = 2.0 / (profile.smoothingWindowDays + 1)
        val varEma = sigmaW * sigmaW * alpha / (2 - alpha)
        val rho = profile.energyDensityKcalPerKg
        val variance = sigmaI * sigmaI / maxOf(intakeDays, 1) +
            (rho / span) * (rho / span) * 2 * varEma
        return sqrt(variance)
    }

    // ---- Measured-noise inputs for the empirical SE (data-driven, no magic numbers) ----
    // σ_W = scatter of raw daily weigh-ins about their EMA (kg); σ_I = scatter of complete
    // daily intake (kcal). Both are measured from this user's own data; below MIN_SIGMA_SAMPLES
    // observations — or when the data is degenerate (flat → zero scatter) — safe defaults apply.
    private val sigmaW: Double by lazy {
        val daily = aggregatedDailyWeights()
        if (daily.size < MIN_SIGMA_SAMPLES) return@lazy SIGMA_W_DEFAULT
        val series = emaSeries(daily.keys.max())
        val resid = daily.mapNotNull { (d, kg) -> series[d]?.let { kg - it } }
        if (resid.isEmpty()) return@lazy SIGMA_W_DEFAULT
        val rms = sqrt(resid.sumOf { it * it } / resid.size)
        if (rms > 0.0) rms else SIGMA_W_DEFAULT
    }

    private val sigmaI: Double by lazy {
        val kcals = intake.filter { it.complete }.map { it.kcal }
        if (kcals.size < MIN_SIGMA_SAMPLES) return@lazy SIGMA_I_DEFAULT
        val mean = kcals.average()
        val sd = sqrt(kcals.sumOf { (it - mean) * (it - mean) } / kcals.size)
        if (sd > 0.0) sd else SIGMA_I_DEFAULT
    }

    private companion object {
        const val SE_FORMULA = 500.0      // prior standard error on the Mifflin formula (kcal)
        const val SIGMA_W_DEFAULT = 1.0   // fallback weigh-in scatter about the EMA (kg)
        const val SIGMA_I_DEFAULT = 500.0 // fallback daily-intake scatter (kcal)
        const val MIN_SIGMA_SAMPLES = 8   // below this many observations, use the defaults
        // "Calibrating" UX horizon: paired-data days before the estimate is presented as settled.
        // Kept independent of the 180-day averaging window — shrinkage makes the estimate
        // trustworthy within ~2 weeks, so a full-window rule would flag "calibrating" for 6 months.
        const val CALIBRATION_DAYS = 14
    }
}

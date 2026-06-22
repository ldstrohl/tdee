package com.tdee.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

/** Pure projection of when a goal weight is reached at a hypothetical intake. */
object GoalProjector {

    /**
     * Projects when [goalKg] is reached given a known [rateKgPerDay].
     *
     * Returns [Projection.Unreachable] when:
     *   - [rateKgPerDay] is zero, or
     *   - the rate is signed in the wrong direction (e.g. positive rate but goal < trend), or
     *   - trend is already at the goal.
     *
     * @param trendNowKg current EMA trend weight in kg.
     * @param goalKg target weight in kg.
     * @param rateKgPerDay signed rate of weight change (negative = loss, positive = gain).
     * @param asOf reference instant; the predicted date is measured from this log-day.
     * @param zone zone used for the log-day of [asOf].
     */
    fun projectAtRate(
        trendNowKg: Double,
        goalKg: Double,
        rateKgPerDay: Double,
        asOf: Instant,
        zone: ZoneId,
    ): Projection {
        val needLoss = goalKg < trendNowKg
        val needGain = goalKg > trendNowKg
        val wrongSign = (needLoss && rateKgPerDay >= 0) || (needGain && rateKgPerDay <= 0)
        if (rateKgPerDay == 0.0 || wrongSign || (!needLoss && !needGain)) {
            return Projection.Unreachable("not reachable at this rate")
        }

        val days = ceil((goalKg - trendNowKg) / rateKgPerDay).toLong()
        // Log-day of asOf (no profile available here; zone-only boundary at midnight).
        val today = asOf.atZone(zone).toLocalDate()
        val predictedDate: LocalDate = today.plusDays(days)
        return Projection.Reachable(predictedDate, rateKgPerDay)
    }

    /**
     * Projects when [goalKg] is reached at a hypothetical [scenarioIntakeKcal] intake.
     *
     * Delegates to [projectAtRate] after computing the implied kg/day rate from the
     * caloric balance (intake – TDEE) divided by [UserProfile.energyDensityKcalPerKg].
     *
     * @param scenarioIntakeKcal hypothetical daily intake (kg/kcal already converted by caller).
     * @param tdee current TDEE estimate.
     * @param trendNowKg current EMA trend weight.
     * @param goalKg target weight.
     * @param asOf reference instant; the predicted date is measured from this log-day.
     * @param zone zone used for the log-day of [asOf].
     * @param profile supplies energy density and dayStartHour.
     */
    fun project(
        scenarioIntakeKcal: Double,
        tdee: TdeeEstimate,
        trendNowKg: Double,
        goalKg: Double,
        asOf: Instant,
        zone: ZoneId,
        profile: UserProfile,
    ): Projection {
        val rateKgPerDay = (scenarioIntakeKcal - tdee.valueKcal) / profile.energyDensityKcalPerKg
        // Apply dayStartHour shift so the log-day matches the engine's bucketing.
        val shiftedAsOf = asOf.minusSeconds(profile.dayStartHour.toLong() * 3600)
        return projectAtRate(trendNowKg, goalKg, rateKgPerDay, shiftedAsOf, zone)
    }
}

package com.tdee.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

/** Pure projection of when a goal weight is reached at a hypothetical intake. */
object GoalProjector {

    /**
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

        val needLoss = goalKg < trendNowKg
        val needGain = goalKg > trendNowKg
        val wrongSign = (needLoss && rateKgPerDay >= 0) || (needGain && rateKgPerDay <= 0)
        // Already at goal (goalKg == trendNowKg) also has no positive-progress rate.
        if (rateKgPerDay == 0.0 || wrongSign || (!needLoss && !needGain)) {
            return Projection.Unreachable("not reachable at this intake")
        }

        val days = ceil((goalKg - trendNowKg) / rateKgPerDay).toLong()
        val today = asOf.minusSeconds(profile.dayStartHour.toLong() * 3600)
            .atZone(zone)
            .toLocalDate()
        val predictedDate: LocalDate = today.plusDays(days)
        return Projection.Reachable(predictedDate, rateKgPerDay)
    }
}

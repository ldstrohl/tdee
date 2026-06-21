package com.tdee.domain

/** Pure derivation of daily calorie/macro targets from a TDEE estimate. */
object TargetCalculator {

    /**
     * @param tdee the current TDEE estimate.
     * @param bodyweightKg current trend weight (drives protein target).
     * @param profile supplies goal rate, macro ratios, and energy density.
     */
    fun targets(tdee: TdeeEstimate, bodyweightKg: Double, profile: UserProfile): Targets {
        val dailyAdjustment = profile.goalRateKgPerWeek * profile.energyDensityKcalPerKg / 7
        val calorieTarget = tdee.valueKcal + dailyAdjustment
        val proteinG = profile.proteinGPerKg * bodyweightKg
        val fatG = calorieTarget * profile.fatPctOfCalories / 9
        val carbG = (calorieTarget - proteinG * 4 - fatG * 9) / 4
        return Targets(
            calorieTargetKcal = calorieTarget,
            proteinG = proteinG,
            fatG = fatG,
            carbG = carbG,
        )
    }
}

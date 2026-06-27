package com.tdee.domain

/** A macronutrient, used by [MacroBalancer]. */
enum class Macro { PROTEIN, FAT, CARB }

/** Macro amounts in grams. [totalKcal] sums their energy at 4/9/4 kcal·g⁻¹. */
data class MacroGrams(val proteinG: Double, val fatG: Double, val carbG: Double) {
    fun totalKcal(): Double =
        proteinG * MacroBalancer.KCAL_PER_G_PROTEIN +
            fatG * MacroBalancer.KCAL_PER_G_FAT +
            carbG * MacroBalancer.KCAL_PER_G_CARB
}

/**
 * Holds the calorie target fixed and rebalances the *unlocked* macros to refill it.
 *
 * Drives the check-in override behaviour (outline note 6): when the user overrides one recommended
 * macro, the others adjust so the macros still sum to the calorie target.
 *
 * Rule: locked macros keep their grams; the remaining energy (target − locked kcal) is split across
 * the free macros in proportion to their current kcal (equal split when the free macros are all
 * zero). If the locked macros already meet or exceed the target, the free macros go to zero — and
 * when *all three* are locked the input is returned unchanged. In both over-constrained cases the
 * caller should compare [MacroGrams.totalKcal] against the target and surface the mismatch (e.g.
 * offer to update the calorie target) rather than silently overriding it.
 */
object MacroBalancer {
    const val KCAL_PER_G_PROTEIN = 4.0
    const val KCAL_PER_G_FAT = 9.0
    const val KCAL_PER_G_CARB = 4.0

    private fun kcalOf(m: Macro, g: MacroGrams): Double = when (m) {
        Macro.PROTEIN -> g.proteinG * KCAL_PER_G_PROTEIN
        Macro.FAT -> g.fatG * KCAL_PER_G_FAT
        Macro.CARB -> g.carbG * KCAL_PER_G_CARB
    }

    private fun gramsOf(m: Macro, kcal: Double): Double = when (m) {
        Macro.PROTEIN -> kcal / KCAL_PER_G_PROTEIN
        Macro.FAT -> kcal / KCAL_PER_G_FAT
        Macro.CARB -> kcal / KCAL_PER_G_CARB
    }

    /**
     * Returns macro grams that keep the [locked] macros fixed and refill [calorieTargetKcal] with
     * the rest. See the class doc for the over-constrained behaviour.
     */
    fun rebalance(
        calorieTargetKcal: Double,
        current: MacroGrams,
        locked: Set<Macro>,
    ): MacroGrams {
        val free = Macro.entries.filter { it !in locked }
        if (free.isEmpty()) return current // fully constrained — caller checks totalKcal vs target

        val lockedKcal = locked.sumOf { kcalOf(it, current) }
        val remainingKcal = (calorieTargetKcal - lockedKcal).coerceAtLeast(0.0)
        val freeCurrentKcal = free.sumOf { kcalOf(it, current) }

        fun newKcal(m: Macro): Double =
            if (m in locked) {
                kcalOf(m, current)
            } else {
                val share = if (freeCurrentKcal > 0.0) kcalOf(m, current) / freeCurrentKcal
                else 1.0 / free.size
                remainingKcal * share
            }

        return MacroGrams(
            proteinG = gramsOf(Macro.PROTEIN, newKcal(Macro.PROTEIN)),
            fatG = gramsOf(Macro.FAT, newKcal(Macro.FAT)),
            carbG = gramsOf(Macro.CARB, newKcal(Macro.CARB)),
        )
    }
}

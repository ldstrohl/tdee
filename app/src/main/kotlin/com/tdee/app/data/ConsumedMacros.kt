package com.tdee.app.data

/**
 * Summed macronutrient totals for a set of food entries (typically today's log).
 *
 * All fields are zero when no entries have been logged.
 */
data class ConsumedMacros(
    val kcal: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbG: Double,
)

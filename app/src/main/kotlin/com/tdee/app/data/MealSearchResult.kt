package com.tdee.app.data

import java.time.Instant

/**
 * A single food item within a [MealSearchResult], normalized across the three sources
 * (saved meal, logged group, standalone logged entry) that [TdeeRepository.searchMeals] draws from.
 */
data class MealSearchItem(
    val name: String,
    val kcal: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbG: Double,
    val grams: Double?,
)

/**
 * A single row returned by [TdeeRepository.searchMeals] — either a saved meal from the library,
 * a previously-logged meal group (deduped by name/mealId), or a previously-logged standalone entry
 * (deduped by name). See [TdeeRepository.searchMeals] for matching/ranking/dedup rules.
 */
sealed interface MealSearchResult {
    /** Stable identity for list diffing: `"saved-<id>"` / `"meal-<mealId>"` / `"entry-<id>"`. */
    val key: String
    val title: String
    val items: List<MealSearchItem>

    data class Saved(
        val savedMealId: Long,
        override val title: String,
        override val items: List<MealSearchItem>,
    ) : MealSearchResult {
        override val key get() = "saved-$savedMealId"
    }

    data class LoggedMeal(
        val mealId: String,
        override val title: String,
        override val items: List<MealSearchItem>,
        val lastLogged: Instant,
    ) : MealSearchResult {
        override val key get() = "meal-$mealId"
    }

    data class LoggedEntry(
        val entryId: Long,
        override val title: String,
        override val items: List<MealSearchItem>,
        val lastLogged: Instant,
    ) : MealSearchResult {
        override val key get() = "entry-$entryId"
    }
}

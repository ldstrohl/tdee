package com.tdee.app.addfood

/**
 * Drop-in seam for natural-language food parsing.
 *
 * This interface defines the contract that a future NL `/parse` proxy client (outline.md
 * modules 1–2) will implement. The manual add-food form does NOT route through it yet —
 * it is provided as a wiring point for the NL-input flow planned for a later task.
 *
 * To plug in the real parser: implement this interface against the `/parse` HTTP client and
 * bind it wherever [FoodParser] is injected. No screens need to change.
 */
interface FoodParser {
    /**
     * Parse [text] (a natural-language food description, e.g. "2 eggs and a slice of toast")
     * into a list of candidate food items. Returns an empty list if nothing could be parsed.
     */
    suspend fun parse(text: String): List<ParsedFoodItem>
}

/**
 * A single food item produced by [FoodParser].
 *
 * Designed for a confirmation screen: show [name], [displayQuantity]/[unit]/[grams],
 * [kcal] and macros to the user, and let them confirm or correct before inserting via
 * [TdeeRepository.addFood].
 *
 * @param name            display name for the food.
 * @param displayQuantity quantity as parsed (e.g. 2.0 for "2 eggs").
 * @param unit            unit string as parsed (e.g. "egg", "slice", "g").
 * @param grams           serving weight in grams, or null when not determinable.
 * @param kcal            energy in kcal.
 * @param proteinG        protein in grams.
 * @param fatG            fat in grams.
 * @param carbG           carbohydrate in grams.
 * @param needsConfirmation true when the parser's confidence is low enough that the user
 *   should be shown a confirmation screen before the entry is logged; false when the
 *   parser is confident and the entry could be logged directly (still shown for review
 *   in the MVP confirmation screen).
 */
data class ParsedFoodItem(
    val name: String,
    val displayQuantity: Double,
    val unit: String,
    val grams: Double?,
    val kcal: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbG: Double,
    val needsConfirmation: Boolean,
)

/**
 * Trivial stub implementation — always returns an empty list.
 *
 * Stands in until the real HTTP-backed parser is wired in. Returning empty rather than
 * throwing means any code that calls parse() today gracefully falls through to the manual
 * entry form with no user-visible error.
 */
class StubFoodParser : FoodParser {
    override suspend fun parse(text: String): List<ParsedFoodItem> = emptyList()
}

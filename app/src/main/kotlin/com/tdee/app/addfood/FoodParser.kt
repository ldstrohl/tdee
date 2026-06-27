package com.tdee.app.addfood

/**
 * Drop-in seam for natural-language food parsing.
 *
 * Implemented by [LlmFoodParser] (client-direct, bring-your-own-key) — the app calls the user's
 * chosen LLM provider with a key stored on-device. The manual add-food form does not route through
 * this seam; it backs the "Describe a meal" parse/confirm flow.
 */
interface FoodParser {
    /**
     * Parse [text] (a natural-language food description, e.g. "2 eggs and a slice of toast") into
     * structured food items. Returns a [ParseResult.Success] (possibly with an empty list when the
     * text names no food) or a [ParseResult.Failure] that the UI surfaces to the user.
     */
    suspend fun parse(text: String): ParseResult
}

/**
 * Outcome of [FoodParser.parse]: either parsed [items][ParseResult.Success.items] or a typed
 * [failure][ParseResult.Failure] carrying a short, user-facing [message][ParseResult.Failure.message].
 */
sealed interface ParseResult {
    data class Success(val items: List<ParsedFoodItem>) : ParseResult
    data class Failure(val kind: ParseErrorKind, val message: String) : ParseResult
}

/** Why a parse failed — drives messaging and (future) retry/UX decisions. */
enum class ParseErrorKind { NO_KEY, NETWORK, RATE_LIMITED, AUTH, SERVER, BAD_RESPONSE, UNKNOWN }

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
 * Trivial stub implementation — always returns an empty success.
 *
 * Stands in where a [FoodParser] is needed but no parsing should happen.
 */
class StubFoodParser : FoodParser {
    override suspend fun parse(text: String): ParseResult = ParseResult.Success(emptyList())
}

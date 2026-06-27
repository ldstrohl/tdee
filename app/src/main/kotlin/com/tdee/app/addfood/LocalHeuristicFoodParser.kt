package com.tdee.app.addfood

/**
 * Local, offline [FoodParser] that splits text into named items with zero macros.
 *
 * No longer the production path — [LlmFoodParser] (client-direct, bring-your-own-key) backs the
 * parse/confirm flow. Kept as a network-free fallback/test double: each item prefills the
 * confirmation screen so the user fills in the numbers manually.
 *
 * Heuristic: split [text] into items on commas and the word "and" (case-insensitive), trim each,
 * drop blanks, and emit one [ParsedFoodItem] per item using the item text as [ParsedFoodItem.name]
 * with zero/blank quantity, grams, kcal and macros and [ParsedFoodItem.needsConfirmation] = true.
 */
class LocalHeuristicFoodParser : FoodParser {

    override suspend fun parse(text: String): ParseResult {
        // Split on commas and the standalone word "and" (case-insensitive).
        val items = SPLIT_REGEX.split(text)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { item ->
                ParsedFoodItem(
                    name = item,
                    displayQuantity = 0.0,
                    unit = "",
                    grams = null,
                    kcal = 0.0,
                    proteinG = 0.0,
                    fatG = 0.0,
                    carbG = 0.0,
                    needsConfirmation = true,
                )
            }
        return ParseResult.Success(items)
    }

    private companion object {
        // Comma, or the standalone word "and" (word boundaries, so "sandwich" is not split).
        val SPLIT_REGEX = Regex(",|\\band\\b", RegexOption.IGNORE_CASE)
    }
}

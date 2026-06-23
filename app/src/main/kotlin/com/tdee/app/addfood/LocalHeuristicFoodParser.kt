package com.tdee.app.addfood

/**
 * Local stand-in [FoodParser] for the natural-language food-logging flow (outline modules 1–2).
 *
 * This is a PLACEHOLDER. The real natural-language parsing (Haiku + USDA lookup) lives in a
 * Cloudflare Worker that is not built yet. When it ships, a `WorkerFoodParser` (HTTP →
 * Cloudflare `/parse`, returning matched USDA macros) will replace this class behind the same
 * [FoodParser] seam — no screen changes needed. The confirmation screen prefills its editable
 * fields from each [ParsedFoodItem], so it works identically for both: the numbers are 0 now
 * (the user fills them in) and real once the Worker exists.
 *
 * Heuristic: split [text] into items on commas and the word "and" (case-insensitive), trim each,
 * drop blanks, and emit one [ParsedFoodItem] per item using the item text as [ParsedFoodItem.name]
 * with zero/blank quantity, grams, kcal and macros and [ParsedFoodItem.needsConfirmation] = true.
 */
class LocalHeuristicFoodParser : FoodParser {

    override suspend fun parse(text: String): List<ParsedFoodItem> {
        // Split on commas and the standalone word "and" (case-insensitive).
        return SPLIT_REGEX.split(text)
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
    }

    private companion object {
        // Comma, or the standalone word "and" (word boundaries, so "sandwich" is not split).
        val SPLIT_REGEX = Regex(",|\\band\\b", RegexOption.IGNORE_CASE)
    }
}

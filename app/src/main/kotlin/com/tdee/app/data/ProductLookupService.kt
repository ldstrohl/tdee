package com.tdee.app.data

import com.tdee.app.addfood.FoodParser
import com.tdee.app.addfood.ParseResult
import com.tdee.app.addfood.ParsedFoodItem

/**
 * Wraps [OpenFoodFactsClient] with LLM gap-filling: Open Food Facts is crowd-sourced, so a
 * product often has calories but is missing one or more macros ([ProductLookup.Found.gaps]).
 * When that happens, this asks [parser] to estimate just the missing macros from the OFF facts
 * it does have — OFF-supplied values always win, and any problem with the LLM call simply leaves
 * the OFF item as-is (a user with no API key still gets their scanned product).
 */
class ProductLookupService(
    private val off: OpenFoodFactsClient,
    private val parser: FoodParser,
) {
    suspend fun lookup(barcode: String): ProductLookup {
        val result = off.lookup(barcode)
        if (result !is ProductLookup.Found || result.gaps.isEmpty()) return result

        val estimate = parser.parse(describe(result.item, result.gaps))
        val filled = (estimate as? ParseResult.Success)
            ?.items
            ?.firstOrNull()
            ?.let { result.item.fillGaps(it, result.gaps) }
            ?: result.item

        return ProductLookup.Found(filled, result.gaps)
    }
}

/** A short factual prompt: name, serving basis, and every macro OFF already supplied. */
private fun describe(item: ParsedFoodItem, gaps: Set<Macro>): String {
    val parts = mutableListOf("${item.name}, ${servingBasis(item)}, ${formatG(item.kcal)} kcal")
    if (Macro.PROTEIN !in gaps) parts += "${formatG(item.proteinG)} g protein"
    if (Macro.FAT !in gaps) parts += "${formatG(item.fatG)} g fat"
    if (Macro.CARB !in gaps) parts += "${formatG(item.carbG)} g carbohydrate"
    return parts.joinToString(", ")
}

private fun servingBasis(item: ParsedFoodItem): String =
    if (item.unit == "serving" && item.grams != null) "one ${formatG(item.grams)} g serving" else "100 g"

private fun formatG(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/**
 * Fills only the fields named in [gaps] with the corresponding value from [from], rejecting
 * non-finite or non-positive estimates. Every other field — including OFF-supplied macros, kcal,
 * name, grams, unit and displayQuantity — is left untouched.
 */
internal fun ParsedFoodItem.fillGaps(from: ParsedFoodItem, gaps: Set<Macro>): ParsedFoodItem {
    fun estimate(value: Double): Double? = value.takeIf { it.isFinite() && it > 0.0 }

    var result = this
    if (Macro.PROTEIN in gaps) estimate(from.proteinG)?.let { result = result.copy(proteinG = it) }
    if (Macro.FAT in gaps) estimate(from.fatG)?.let { result = result.copy(fatG = it) }
    if (Macro.CARB in gaps) estimate(from.carbG)?.let { result = result.copy(carbG = it) }
    return result
}

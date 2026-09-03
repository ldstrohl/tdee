package com.tdee.app.data

import com.tdee.app.addfood.HttpOutcome
import com.tdee.app.addfood.ParseErrorKind
import com.tdee.app.addfood.ParseResult
import com.tdee.app.addfood.ParsedFoodItem
import com.tdee.app.addfood.executeWithRetry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/** A macro that was missing from an Open Food Facts product and so was left at 0.0. */
enum class Macro { PROTEIN, FAT, CARB }

/** Outcome of [OpenFoodFactsClient.lookup]. */
sealed interface ProductLookup {
    /** [item] was built from OFF data; [gaps] lists any macro OFF didn't report (left at 0.0). */
    data class Found(val item: ParsedFoodItem, val gaps: Set<Macro>) : ProductLookup

    /** The barcode isn't in the OFF database, or the product has no usable calorie data. */
    data object NotFound : ProductLookup

    /** A network or server problem prevented the lookup. */
    data class Failure(val kind: ParseErrorKind, val message: String) : ProductLookup
}

/**
 * Looks up a scanned barcode against the [Open Food Facts](https://openfoodfacts.org) product
 * database and maps the result to a [ParsedFoodItem], so a packaged product can be logged with
 * real per-serving numbers instead of an LLM estimate.
 *
 * OFF data is crowd-sourced, so a [ProductLookup.Found] item always carries
 * `needsConfirmation = true` and the caller should still show it on a confirmation screen.
 */
class OpenFoodFactsClient(
    private val client: OkHttpClient,
    baseUrl: String = "https://world.openfoodfacts.org",
) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun lookup(barcode: String): ProductLookup {
        val request = Request.Builder()
            .url(
                "$baseUrl/api/v2/product/$barcode.json?fields=" +
                    "code,product_name,brands,quantity,serving_size,serving_quantity," +
                    "serving_quantity_unit,nutriments",
            )
            .header("User-Agent", "TDEE-App/1.0 (github.com/ldstrohl/tdee)")
            .build()

        return when (val outcome = executeWithRetry(client, request, "Open Food Facts")) {
            is HttpOutcome.Body -> parseProduct(outcome.text, barcode)
            // A 404 means OFF has no such barcode. Every other error is a real failure and must
            // stay one — reporting an outage as "not in the database" would send the user off to
            // re-enter a product that is actually there.
            is HttpOutcome.Error ->
                if (outcome.code == 404) {
                    ProductLookup.NotFound
                } else {
                    ProductLookup.Failure(outcome.failure.kind, lookupMessage(outcome.failure))
                }
        }
    }
}

// ---------------------------------------------------------------------------
// Response parsing / mapping
// ---------------------------------------------------------------------------

/** [mapHttpError]'s fallback text talks about parsing a meal; say what actually failed here. */
private fun lookupMessage(failure: ParseResult.Failure): String =
    if (failure.kind == ParseErrorKind.UNKNOWN) "Couldn't look up that barcode — try again."
    else failure.message

private fun parseProduct(body: String, barcode: String): ProductLookup = try {
    val json = JSONObject(body)
    val status = json.optInt("status", 0)
    val product = json.optJSONObject("product")
    if (status != 1 || product == null) {
        ProductLookup.NotFound
    } else {
        mapProduct(product, barcode)
    }
} catch (_: JSONException) {
    ProductLookup.Failure(ParseErrorKind.BAD_RESPONSE, "Couldn't read product data — try again.")
}

private fun mapProduct(product: JSONObject, barcode: String): ProductLookup {
    val nutriments = product.optJSONObject("nutriments") ?: return ProductLookup.NotFound

    val servingQuantity = product.doubleOrNull("serving_quantity")
    val usesServing = servingQuantity != null && hasEnergy(nutriments, "_serving")

    val suffix = if (usesServing) "_serving" else "_100g"
    val kcal = kcalFor(nutriments, suffix) ?: return ProductLookup.NotFound

    val gaps = mutableSetOf<Macro>()
    val protein = nutriments.doubleOrNull("proteins$suffix").orGap(Macro.PROTEIN, gaps)
    val fat = nutriments.doubleOrNull("fat$suffix").orGap(Macro.FAT, gaps)
    val carb = nutriments.doubleOrNull("carbohydrates$suffix").orGap(Macro.CARB, gaps)

    val grams: Double?
    val displayQuantity: Double
    val unit: String
    if (usesServing) {
        val servingUnit = product.optString("serving_quantity_unit", "g").trim().lowercase()
        grams = when (servingUnit) {
            "g", "" -> servingQuantity
            "mg" -> servingQuantity / 1000.0
            else -> null // e.g. "ml" — a volume, not a mass
        }
        displayQuantity = 1.0
        unit = "serving"
    } else {
        grams = 100.0
        displayQuantity = 100.0
        unit = "g"
    }

    val item = ParsedFoodItem(
        name = resolveName(product, barcode),
        displayQuantity = displayQuantity,
        unit = unit,
        grams = grams,
        kcal = kcal,
        proteinG = protein,
        fatG = fat,
        carbG = carb,
        needsConfirmation = true,
    )
    return ProductLookup.Found(item, gaps)
}

/** Value if present, else records [macro] as a gap and returns 0.0. */
private fun Double?.orGap(macro: Macro, gaps: MutableSet<Macro>): Double {
    if (this == null) {
        gaps.add(macro)
        return 0.0
    }
    return this
}

/** True when [kcalFor] can derive a calorie value for [suffix] (direct kcal, or kJ to convert). */
private fun hasEnergy(nutriments: JSONObject, suffix: String): Boolean =
    kcalFor(nutriments, suffix) != null

/**
 * kcal for [suffix] ("_serving" or "_100g"): direct `energy-kcal*` if present, else `energy-kj*`
 * (preferred) or the generic `energy*` (EU products often list kJ only) converted to kcal.
 */
private fun kcalFor(nutriments: JSONObject, suffix: String): Double? {
    nutriments.doubleOrNull("energy-kcal$suffix")?.let { return it }
    val kj = nutriments.doubleOrNull("energy-kj$suffix") ?: nutriments.doubleOrNull("energy$suffix")
    return kj?.let { it / 4.184 }
}

/** `brands` (first, trimmed) + `product_name`, avoiding duplication; falls back to the barcode. */
private fun resolveName(product: JSONObject, barcode: String): String {
    val firstBrand = product.optString("brands").substringBefore(',').trim()
    val productName = product.optString("product_name").trim()
    return when {
        productName.isNotEmpty() && firstBrand.isNotEmpty() ->
            if (productName.equals(firstBrand, ignoreCase = true) ||
                productName.startsWith(firstBrand, ignoreCase = true)
            ) {
                productName
            } else {
                "$firstBrand $productName"
            }
        productName.isNotEmpty() -> productName
        firstBrand.isNotEmpty() -> firstBrand
        else -> barcode
    }
}

private fun JSONObject.doubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key)
    return if (value.isNaN()) null else value
}

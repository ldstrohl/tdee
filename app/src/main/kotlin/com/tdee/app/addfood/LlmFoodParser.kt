package com.tdee.app.addfood

import com.tdee.app.data.LlmProvider
import com.tdee.app.data.LlmSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Client-direct, bring-your-own-key [FoodParser]. Reads the active provider/model/key from
 * [LlmSettings] and calls that provider's API directly (no proxy), asking for structured JSON food
 * items. Replaces the retired Cloudflare Worker; the system prompt and item schema are ported from it.
 *
 * With no key for the active provider, [parse] returns [ParseResult.Failure] with
 * [ParseErrorKind.NO_KEY] so the UI can prompt the user to add one in Settings (manual entry still
 * works). Transient failures (429/500/503, network) are retried; terminal ones map to a typed
 * [ParseErrorKind] with a short user-facing message.
 *
 * Adapter base URLs are injectable so the per-provider HTTP shapes can be exercised against a
 * MockWebServer in tests.
 */
class LlmFoodParser(
    private val settings: LlmSettings,
    private val client: OkHttpClient = OkHttpClient(),
    geminiBaseUrl: String = "https://generativelanguage.googleapis.com",
    openAiUrl: String = "https://api.openai.com/v1/chat/completions",
    anthropicUrl: String = "https://api.anthropic.com/v1/messages",
) : FoodParser {

    private val adapters: Map<LlmProvider, LlmAdapter> = mapOf(
        LlmProvider.GEMINI to GeminiAdapter(client, geminiBaseUrl),
        LlmProvider.OPENAI to OpenAiAdapter(client, openAiUrl),
        LlmProvider.ANTHROPIC to AnthropicAdapter(client, anthropicUrl),
    )

    override suspend fun parse(text: String): ParseResult {
        val provider = settings.provider
        val key = settings.keyFor(provider)
        if (key.isNullOrBlank()) {
            return ParseResult.Failure(
                ParseErrorKind.NO_KEY,
                "Add an API key in Settings to use meal parsing.",
            )
        }
        val model = settings.modelFor(provider)
        return adapters.getValue(provider).parse(text, model, key)
    }
}

// ---------------------------------------------------------------------------
// Adapters
// ---------------------------------------------------------------------------

/** One provider's request shape + response extraction. Visible for MockWebServer tests. */
internal interface LlmAdapter {
    suspend fun parse(text: String, model: String, apiKey: String): ParseResult
}

internal class GeminiAdapter(
    private val client: OkHttpClient,
    private val baseUrl: String,
) : LlmAdapter {

    override suspend fun parse(text: String, model: String, apiKey: String): ParseResult {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text))),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", geminiResponseSchema()),
            )
            .toString()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        return when (val outcome = executeWithRetry(client, request)) {
            is HttpOutcome.Error -> outcome.failure
            is HttpOutcome.Body -> extractInner(outcome.text) { json ->
                json.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getString("text")
            }
        }
    }
}

internal class OpenAiAdapter(
    private val client: OkHttpClient,
    private val url: String,
) : LlmAdapter {

    override suspend fun parse(text: String, model: String, apiKey: String): ParseResult {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", text)),
            )
            .put("temperature", 0)
            .put("response_format", JSONObject().put("type", "json_object"))
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        return when (val outcome = executeWithRetry(client, request)) {
            is HttpOutcome.Error -> outcome.failure
            is HttpOutcome.Body -> extractInner(outcome.text) { json ->
                json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
            }
        }
    }
}

internal class AnthropicAdapter(
    private val client: OkHttpClient,
    private val url: String,
) : LlmAdapter {

    override suspend fun parse(text: String, model: String, apiKey: String): ParseResult {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("temperature", 0)
            .put("system", SYSTEM_PROMPT)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", text)),
            )
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        return when (val outcome = executeWithRetry(client, request)) {
            is HttpOutcome.Error -> outcome.failure
            is HttpOutcome.Body -> extractInner(outcome.text) { json ->
                json.getJSONArray("content").getJSONObject(0).getString("text")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared HTTP execution, retry, error mapping, and JSON extraction
// ---------------------------------------------------------------------------

private const val MAX_ATTEMPTS = 3
private val JSON_MEDIA = "application/json".toMediaType()

private sealed interface HttpOutcome {
    data class Body(val text: String) : HttpOutcome
    data class Error(val failure: ParseResult.Failure) : HttpOutcome
}

/**
 * Sends [request], retrying up to [MAX_ATTEMPTS] times on transient 429/500/503 and network errors
 * with backoff `400*(attempt+1)`ms. Maps terminal failures to a typed [ParseErrorKind].
 */
private suspend fun executeWithRetry(client: OkHttpClient, request: Request): HttpOutcome =
    withContext(Dispatchers.IO) {
        var lastError: HttpOutcome.Error = HttpOutcome.Error(
            ParseResult.Failure(ParseErrorKind.UNKNOWN, "Couldn't parse the meal — try again."),
        )
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext HttpOutcome.Body(response.body?.string().orEmpty())
                    }
                    val code = response.code
                    val retryable = code == 429 || code == 500 || code == 503
                    if (retryable && attempt < MAX_ATTEMPTS - 1) {
                        // fall through to backoff + retry
                    } else {
                        val errBody = response.body?.string().orEmpty()
                        return@withContext HttpOutcome.Error(mapHttpError(code, errBody))
                    }
                }
            } catch (_: IOException) {
                if (attempt >= MAX_ATTEMPTS - 1) {
                    return@withContext HttpOutcome.Error(
                        ParseResult.Failure(ParseErrorKind.NETWORK, "No internet connection."),
                    )
                }
                lastError = HttpOutcome.Error(
                    ParseResult.Failure(ParseErrorKind.NETWORK, "No internet connection."),
                )
            }
            delay(400L * (attempt + 1))
        }
        lastError
    }

private fun mapHttpError(code: Int, body: String): ParseResult.Failure = when {
    code == 401 || code == 403 ->
        ParseResult.Failure(ParseErrorKind.AUTH, "Invalid API key — check it in Settings.")
    code == 429 ->
        ParseResult.Failure(ParseErrorKind.RATE_LIMITED, "Rate limited — try again in a moment.")
    code in 500..599 ->
        ParseResult.Failure(ParseErrorKind.SERVER, "The provider had an error — try again.")
    // Other terminal errors (notably 400 — bad request, model not found, "credit balance too low",
    // etc.) carry an actionable reason from the provider. Surface it instead of a generic message.
    else ->
        ParseResult.Failure(
            ParseErrorKind.UNKNOWN,
            extractProviderError(body) ?: "Couldn't parse the meal — try again.",
        )
}

/** Provider error string from its JSON envelope. Gemini/OpenAI/Anthropic all use `error.message`. */
private fun extractProviderError(body: String): String? =
    runCatching { JSONObject(body).getJSONObject("error").getString("message") }
        .getOrNull()?.takeIf { it.isNotBlank() }

/**
 * Pulls the model's JSON text out of a provider envelope via [innerText], then parses it into
 * [ParsedFoodItem]s. Any structural problem (bad envelope, non-JSON content, missing `items`) maps
 * to [ParseErrorKind.BAD_RESPONSE].
 */
private inline fun extractInner(envelope: String, innerText: (JSONObject) -> String): ParseResult =
    try {
        val inner = innerText(JSONObject(envelope))
        parseItems(inner)
    } catch (_: JSONException) {
        badResponse()
    }

private fun parseItems(jsonText: String): ParseResult {
    val obj = JSONObject(extractJsonObject(jsonText))
    val items = obj.optJSONArray("items") ?: return badResponse()
    val parsed = (0 until items.length()).map { i ->
        val o = items.getJSONObject(i)
        ParsedFoodItem(
            name = o.getString("name"),
            displayQuantity = o.optDouble("displayQuantity", 0.0),
            unit = o.optString("unit"),
            grams = if (o.has("grams") && !o.isNull("grams")) o.getDouble("grams") else null,
            kcal = o.optDouble("kcal", 0.0),
            proteinG = o.optDouble("proteinG", 0.0),
            fatG = o.optDouble("fatG", 0.0),
            carbG = o.optDouble("carbG", 0.0),
            needsConfirmation = true,
        )
    }
    val mealName = obj.optString("mealName").takeIf { it.isNotBlank() }
    return ParseResult.Success(parsed, mealName)
}

/** Trims any prose/markdown fencing around the JSON object (first `{` … last `}`). */
private fun extractJsonObject(text: String): String {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    return if (start in 0 until end) text.substring(start, end + 1) else text
}

private fun badResponse() =
    ParseResult.Failure(ParseErrorKind.BAD_RESPONSE, "Unexpected response — try again.")

// ---------------------------------------------------------------------------
// Prompt + Gemini response schema (ported from the retired worker, minus "query")
// ---------------------------------------------------------------------------

private val SYSTEM_PROMPT = """
You convert a free-text meal description into structured food items.

Rules:
- Split the description into discrete foods. Do not split a single named dish (e.g. "chicken sandwich") into its components.
- Estimate a reasonable quantity, unit, and total grams for each item from the description and typical serving sizes.
- Estimate realistic calories and macronutrients for the WHOLE quantity stated (not per 100g). A plain black coffee is ~5 kcal, a pat of butter ~35 kcal.
- If the user names a specific brand or product you do not recognize, estimate from the closest common equivalent of that food type. Never refuse and never return zero for a named food. Keep the user's brand/product name in "name".
- If the text contains no food, return an empty items array.
- All numbers must be non-negative. Round to whole numbers.
- Also produce "mealName": a short display name for the whole meal (e.g. "Chicken sandwich & fries"), keeping the user's wording where sensible.
Return ONLY JSON of the form {"mealName":"","items":[{"name":"","displayQuantity":0,"unit":"","grams":0,"kcal":0,"proteinG":0,"fatG":0,"carbG":0}]}.
""".trim()

private val ITEM_PROPS = listOf(
    "name", "displayQuantity", "unit", "grams", "kcal", "proteinG", "fatG", "carbG",
)

/** OpenAPI-subset schema for Gemini structured output (uppercase type enums). */
private fun geminiResponseSchema(): JSONObject {
    fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
    val itemProps = JSONObject()
        .put("name", prop("STRING", "Display name, e.g. 'Scrambled eggs'"))
        .put("displayQuantity", prop("NUMBER", "Numeric quantity, e.g. 2"))
        .put("unit", prop("STRING", "Unit, e.g. 'egg', 'cup', 'g'"))
        .put("grams", prop("NUMBER", "Estimated total mass in grams"))
        .put("kcal", prop("NUMBER", "Estimated total calories"))
        .put("proteinG", prop("NUMBER", "Estimated total protein in grams"))
        .put("fatG", prop("NUMBER", "Estimated total fat in grams"))
        .put("carbG", prop("NUMBER", "Estimated total carbohydrate in grams"))
    val itemSchema = JSONObject()
        .put("type", "OBJECT")
        .put("properties", itemProps)
        .put("required", JSONArray(ITEM_PROPS))
        .put("propertyOrdering", JSONArray(ITEM_PROPS))
    val properties = JSONObject()
        .put("mealName", prop("STRING", "Short display name for the whole meal, e.g. 'Chicken sandwich & fries'"))
        .put("items", JSONObject().put("type", "ARRAY").put("items", itemSchema))
    return JSONObject()
        .put("type", "OBJECT")
        .put("properties", properties)
        .put("required", JSONArray(listOf("mealName", "items")))
        .put("propertyOrdering", JSONArray(listOf("mealName", "items")))
}

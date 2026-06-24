package com.tdee.app.addfood

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * HTTP-backed [FoodParser] that delegates to the Cloudflare `/parse` Worker (outline modules 1–2).
 *
 * Posts `{"text": <text>}` to [endpointUrl] and maps the `items` array in the response to
 * [ParsedFoodItem]. On any network or JSON error, returns an empty list so the confirmation
 * screen stays up and the user can fill in values manually — matching the graceful fallback
 * behaviour of [LocalHeuristicFoodParser].
 *
 * @param endpointUrl  Full URL of the `/parse` endpoint (e.g. `https://…/parse`).
 * @param sharedSecret Optional Bearer token; when non-null and non-blank, sent as
 *                     `Authorization: Bearer <sharedSecret>`.
 * @param client       OkHttpClient to use; can be replaced in tests (e.g. with [MockWebServer]).
 */
class WorkerFoodParser(
    private val endpointUrl: String,
    private val sharedSecret: String? = null,
    private val client: OkHttpClient = OkHttpClient(),
) : FoodParser {

    override suspend fun parse(text: String): List<ParsedFoodItem> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("text", text).toString()
                .toRequestBody("application/json".toMediaType())

            val requestBuilder = Request.Builder()
                .url(endpointUrl)
                .post(body)
            if (!sharedSecret.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $sharedSecret")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()

                val json = JSONObject(response.body?.string() ?: return@withContext emptyList())
                val items = json.optJSONArray("items") ?: return@withContext emptyList()

                (0 until items.length()).map { i ->
                    val obj = items.getJSONObject(i)
                    ParsedFoodItem(
                        name = obj.getString("name"),
                        displayQuantity = obj.optDouble("displayQuantity", 0.0),
                        unit = obj.optString("unit"),
                        grams = if (obj.has("grams") && !obj.isNull("grams")) obj.getDouble("grams") else null,
                        kcal = obj.optDouble("kcal", 0.0),
                        proteinG = obj.optDouble("proteinG", 0.0),
                        fatG = obj.optDouble("fatG", 0.0),
                        carbG = obj.optDouble("carbG", 0.0),
                        needsConfirmation = obj.optBoolean("needsConfirmation", true),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

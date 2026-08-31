package com.tdee.app.addfood

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Shared OkHttp execution, retry, error mapping, and JSON extraction helpers used by the LLM
 * adapters and other HTTP clients in the app.
 */

internal const val MAX_ATTEMPTS = 3
internal val JSON_MEDIA = "application/json".toMediaType()

internal sealed interface HttpOutcome {
    data class Body(val text: String) : HttpOutcome
    data class Error(val failure: ParseResult.Failure) : HttpOutcome
}

/**
 * Sends [request], retrying up to [MAX_ATTEMPTS] times on transient 429/500/503 and network errors
 * with backoff `400*(attempt+1)`ms. Maps terminal failures to a typed [ParseErrorKind].
 */
internal suspend fun executeWithRetry(client: OkHttpClient, request: Request): HttpOutcome =
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

internal fun mapHttpError(code: Int, body: String): ParseResult.Failure = when {
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
internal fun extractProviderError(body: String): String? =
    runCatching { JSONObject(body).getJSONObject("error").getString("message") }
        .getOrNull()?.takeIf { it.isNotBlank() }

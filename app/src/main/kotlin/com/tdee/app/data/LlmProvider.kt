package com.tdee.app.data

/**
 * LLM providers the app can call directly for natural-language meal parsing (bring-your-own-key).
 *
 * Each provider has a small preset list of selectable models and a sensible cheap default. The user
 * picks a provider + model in Settings; [LlmSettingsStore] persists the choice and the per-provider
 * API key.
 */
enum class LlmProvider(
    val displayName: String,
    val models: List<String>,
    val defaultModel: String,
) {
    // The "-latest" aliases track whatever Flash/Pro model Google currently serves. Pinned 2.5
    // names still resolve, but the free tier answers them with 503 "high demand" on every call,
    // and the alias does not have that problem.
    GEMINI(
        displayName = "Google Gemini",
        models = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-pro-latest"),
        defaultModel = "gemini-flash-latest",
    ),
    OPENAI(
        displayName = "OpenAI",
        models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini"),
        defaultModel = "gpt-4o-mini",
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        models = listOf("claude-haiku-4-5", "claude-sonnet-4-6"),
        defaultModel = "claude-haiku-4-5",
    ),
}

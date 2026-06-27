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
    GEMINI(
        displayName = "Google Gemini",
        models = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"),
        defaultModel = "gemini-2.5-flash",
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

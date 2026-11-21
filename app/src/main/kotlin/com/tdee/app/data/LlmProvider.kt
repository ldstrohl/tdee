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
    // Free-tier capacity moves between models. On 2026-11-21 "gemini-flash-latest" answered 503
    // "high demand" or hung past 60 s on every call, and "gemini-pro-latest" had a free-tier quota
    // of 0. The pinned models below answered in 1-6 s. Re-check with the models list endpoint if
    // parsing starts to fail with "high demand" again.
    GEMINI(
        displayName = "Google Gemini",
        models = listOf("gemini-2.5-flash", "gemini-3.5-flash-lite", "gemini-3.5-flash"),
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
    ;

    /**
     * The model to call, given the [stored] choice. A stored name that is no longer a preset (one we
     * removed because it stopped working) gives [defaultModel], so an old choice can't keep failing.
     */
    fun resolveModel(stored: String?): String = stored?.takeIf { it in models } ?: defaultModel
}

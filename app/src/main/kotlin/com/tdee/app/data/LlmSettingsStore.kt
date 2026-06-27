package com.tdee.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Read view of the user's LLM configuration, consumed by the food parser.
 *
 * Kept separate from the concrete [LlmSettingsStore] so the parser (and its unit tests) depend only
 * on these three reads, not on the Android Keystore-backed storage.
 */
interface LlmSettings {
    /** Currently selected provider. */
    val provider: LlmProvider

    /** Selected model for [p] (falls back to [LlmProvider.defaultModel]). */
    fun modelFor(p: LlmProvider): String

    /** API key for [p], or null/blank when none has been entered. */
    fun keyFor(p: LlmProvider): String?
}

/**
 * Persists the user's bring-your-own-key LLM configuration in [EncryptedSharedPreferences]
 * (MasterKey AES256_GCM): the selected provider, a per-provider model, and a per-provider API key.
 *
 * Synchronous accessors — values are tiny and read on demand by the parser and the Settings screen.
 */
class LlmSettingsStore(context: Context) : LlmSettings {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "llm_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override val provider: LlmProvider
        get() = prefs.getString(KEY_PROVIDER, null)
            ?.let { runCatching { LlmProvider.valueOf(it) }.getOrNull() }
            ?: LlmProvider.GEMINI

    override fun modelFor(p: LlmProvider): String =
        prefs.getString(modelKey(p), null)?.takeIf { it.isNotBlank() } ?: p.defaultModel

    override fun keyFor(p: LlmProvider): String? =
        prefs.getString(apiKeyKey(p), null)?.takeIf { it.isNotBlank() }

    /** True when a non-blank key is stored for [p]. */
    fun hasKey(p: LlmProvider): Boolean = !keyFor(p).isNullOrBlank()

    fun setProvider(p: LlmProvider) {
        prefs.edit().putString(KEY_PROVIDER, p.name).apply()
    }

    fun setModel(p: LlmProvider, model: String) {
        prefs.edit().putString(modelKey(p), model).apply()
    }

    /** Stores [key] for [p]; a blank key clears it. */
    fun setKey(p: LlmProvider, key: String) {
        prefs.edit().apply {
            if (key.isBlank()) remove(apiKeyKey(p)) else putString(apiKeyKey(p), key)
        }.apply()
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        fun modelKey(p: LlmProvider) = "model_${p.name}"
        fun apiKeyKey(p: LlmProvider) = "key_${p.name}"
    }
}

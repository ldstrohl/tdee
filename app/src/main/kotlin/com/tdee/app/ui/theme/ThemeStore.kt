package com.tdee.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive, persisted store for the user's [ThemePreference].
 *
 * Backed by [android.content.SharedPreferences] (an app/device setting, not per-user biometric
 * data), so it is available before any profile exists — the onboarding screen is themed too.
 * Exposes a [StateFlow] so the root theme recomposes live when the preference changes.
 *
 * Default is [ThemePreference.SYSTEM].
 */
class ThemeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _preference = MutableStateFlow(read())
    val preference: StateFlow<ThemePreference> = _preference.asStateFlow()

    fun set(preference: ThemePreference) {
        prefs.edit().putString(PREFS_KEY, preference.name).apply()
        _preference.value = preference
    }

    private fun read(): ThemePreference =
        prefs.getString(PREFS_KEY, null)
            ?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
            ?: ThemePreference.SYSTEM

    companion object {
        private const val PREFS_NAME = "com.tdee.app.settings"
        private const val PREFS_KEY = "theme_preference"
    }
}

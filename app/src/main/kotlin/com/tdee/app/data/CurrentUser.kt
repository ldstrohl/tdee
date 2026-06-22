package com.tdee.app.data

import android.content.Context
import java.util.UUID

/**
 * Single source of truth for "who is the current user."
 *
 * Today this is a single, stable, locally-generated UUID persisted in SharedPreferences.
 * When real authentication is added, swap this interface's binding in the DI graph to return
 * the authenticated user's id instead — all callers (TdeeRepository and the DAOs) automatically
 * become user-scoped without further changes.
 */
fun interface CurrentUser {
    fun userId(): String
}

/**
 * Default implementation backed by [SharedPreferences].
 *
 * On first access a random UUID is generated, persisted, and returned for all future calls.
 * The key [PREFS_KEY] is stable — do not rename it without a migration.
 *
 * @param context Any [Context]; the implementation uses [Context.getApplicationContext] internally.
 */
class SharedPreferencesCurrentUser(context: Context) : CurrentUser {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun userId(): String {
        val existing = prefs.getString(PREFS_KEY, null)
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(PREFS_KEY, fresh).apply()
        return fresh
    }

    companion object {
        private const val PREFS_NAME = "com.tdee.app.current_user"
        private const val PREFS_KEY = "local_user_id"
    }
}

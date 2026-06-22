package com.tdee.app.ui.theme

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `default preference is SYSTEM`() {
        assertEquals(ThemePreference.SYSTEM, ThemeStore(context).preference.value)
    }

    @Test
    fun `set updates the reactive preference`() {
        val store = ThemeStore(context)
        store.set(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, store.preference.value)
    }

    @Test
    fun `preference persists across store instances`() {
        ThemeStore(context).set(ThemePreference.LIGHT)
        assertEquals(ThemePreference.LIGHT, ThemeStore(context).preference.value)
    }

    @Test
    fun `corrupt stored value falls back to SYSTEM`() {
        context.getSharedPreferences("com.tdee.app.settings", Context.MODE_PRIVATE)
            .edit().putString("theme_preference", "NOT_A_VALUE").apply()
        assertEquals(ThemePreference.SYSTEM, ThemeStore(context).preference.value)
    }
}

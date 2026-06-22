package com.tdee.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Light scheme is the Material 3 baseline (lavender/indigo) — identical to the app's prior
// default appearance. Dark is the matching M3 baseline dark scheme. Screens reference theme
// tokens (colorScheme.*), so they adapt automatically; chart drawing (built later) should read
// its palette from a theme-resolved ChartColors rather than hard-coded hex.
private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/**
 * Root theme. Resolves the effective light/dark mode from [preference]
 * ([ThemePreference.SYSTEM] follows [isSystemInDarkTheme]) and applies the matching scheme.
 */
@Composable
fun TdeeTheme(
    preference: ThemePreference,
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}

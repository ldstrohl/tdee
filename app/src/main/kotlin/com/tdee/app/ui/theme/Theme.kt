package com.tdee.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Brand color tokens (lavender / indigo family, Material 3 baseline extended
// with the app's #6750A4 primary).
// ---------------------------------------------------------------------------

// Light scheme — brand-flavoured M3 baseline
private val LightColors = lightColorScheme(
    primary          = Color(0xFF6750A4),
    onPrimary        = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary        = Color(0xFF625B71),
    onSecondary      = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary         = Color(0xFF7D5260),
    onTertiary       = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background       = Color(0xFFFFFBFE),
    onBackground     = Color(0xFF1C1B1F),
    surface          = Color(0xFFFFFBFE),
    onSurface        = Color(0xFF1C1B1F),
    surfaceVariant   = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline          = Color(0xFF79747E),
    error            = Color(0xFFB3261E),
    onError          = Color(0xFFFFFFFF),
)

// Dark scheme — matching M3 dark baseline for the same brand hues
private val DarkColors = darkColorScheme(
    primary          = Color(0xFFD0BCFF),
    onPrimary        = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary        = Color(0xFFCCC2DC),
    onSecondary      = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary         = Color(0xFFEFB8C8),
    onTertiary       = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background       = Color(0xFF1C1B1F),
    onBackground     = Color(0xFFE6E1E5),
    surface          = Color(0xFF1C1B1F),
    onSurface        = Color(0xFFE6E1E5),
    surfaceVariant   = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline          = Color(0xFF938F99),
    error            = Color(0xFFF2B8B5),
    onError          = Color(0xFF601410),
)

// ---------------------------------------------------------------------------
// Chart palette CompositionLocal
// ---------------------------------------------------------------------------

// Light chart palette — hex values from design/charts.html
private val LightChartColors = ChartColors(
    rawPoint         = Color(0xFF8F84AC),  // deepened for contrast on the light surface
    emaLine          = Color(0xFF6750A4),
    goalLine         = Color(0xFF9A8FB8),
    intakeBar        = Color(0xFF7CC5C0),
    tdeeLine         = Color(0xFFE08A3C),
    deficitFill      = Color(0xFFCFE8D6),
    proteinColor     = Color(0xFF6750A4),
    fatColor         = Color(0xFFE0A93C),
    carbColor        = Color(0xFF3C9EA0),
    projectionGoal   = Color(0xFF3A9E6A),
    projectionCurrent = Color(0xFF4A7FE0),
    projectionExpected = Color(0xFFD9822B), // amber — distinct from goal-green / current-blue
    projectionConeAlpha = 0.10f,
    gridLine         = Color(0xFFE7E2EE),
    axisLabel        = Color(0xFF6B6478),
    chartSurface     = Color(0xFFFAF8FD),
)

// Dark chart palette — same hues, adapted for dark surface legibility:
//   • Lines brightened / lightened for contrast against a dark background
//   • Grid / axis muted toward light-on-dark tones
//   • chartSurface = dark elevated container tone (matches M3 surface at elevation)
private val DarkChartColors = ChartColors(
    rawPoint         = Color(0xFFAEA4CE),  // brightened lavender for contrast on dark
    emaLine          = Color(0xFFD0BCFF),  // bright lavender (matches dark primary)
    goalLine         = Color(0xFFBBAAD8),  // lighter goal purple
    intakeBar        = Color(0xFF5DB8B3),  // slightly muted teal, readable on dark
    tdeeLine         = Color(0xFFFFB74D),  // warm amber, brighter for contrast
    deficitFill      = Color(0xFF3D6B50),  // muted green fill, visible on dark
    proteinColor     = Color(0xFFD0BCFF),  // matches dark primary
    fatColor         = Color(0xFFFFCC80),  // bright warm yellow
    carbColor        = Color(0xFF4DD0E1),  // bright teal-cyan
    projectionGoal   = Color(0xFF66BB6A),  // bright green
    projectionCurrent = Color(0xFF64B5F6), // bright blue
    projectionExpected = Color(0xFFFFB74D), // bright amber — distinct from green/blue on dark
    projectionConeAlpha = 0.10f,
    gridLine         = Color(0xFF3A3540),  // subtle dark rule
    axisLabel        = Color(0xFFCAC4D0),  // matches onSurfaceVariant dark
    chartSurface     = Color(0xFF2B2930),  // dark elevated surface (above #1C1B1F)
)

// ---------------------------------------------------------------------------
// Root theme composable
// ---------------------------------------------------------------------------

/**
 * Root theme. Resolves the effective light/dark mode from [preference]
 * ([ThemePreference.SYSTEM] follows [isSystemInDarkTheme]) and applies the
 * matching Material 3 color scheme.  Also provides [LocalChartColors] so
 * chart composables can read `LocalChartColors.current.emaLine` etc.
 */
@Composable
fun TdeeTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        ThemePreference.LIGHT  -> false
        ThemePreference.DARK   -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    val chartColors = if (dark) DarkChartColors else LightChartColors

    CompositionLocalProvider(LocalChartColors provides chartColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            content = content,
        )
    }
}

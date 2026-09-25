// File: app/src/main/java/com/hermex/android/ui/theme/Theme.kt
package com.hermex.v3.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    tertiary = SecondaryVariant,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onPrimary = OnPrimary,
    onSecondary = OnSecondary,
    onBackground = OnBackground,
    onSurface = OnSurface,
    error = Error
)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    secondary = Secondary,
    tertiary = SecondaryVariant,
    background = Color.White,
    surface = Color.White,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.Black,
    onSurface = Color.Black
)

/**
 * Dark scheme derived from a user-picked accent color. Container tones are the
 * accent blended over the dark surfaces (alpha-over), so bubbles/buttons get a
 * coherent tint that matches the accent without extra palette work.
 *
 * Visibility notes (v0.1.48): the first version used a 22% primaryContainer and
 * left surfaceVariant untouched, so the chat (user bubbles + assistant
 * bubbles/top bars, which read surfaceVariant) barely moved while sliders and
 * the text cursor (full-strength primary) jumped — "didn't fully work".
 * primaryContainer is now 40% and surfaceVariant takes a 16% accent tint so the
 * whole chat chrome picks up the hue.
 *
 * v0.1.49: per-part overrides (UiColorOverrides) replace the accent-derived
 * tones for background / user bubbles / assistant bubbles + chrome when set.
 */
private fun accentColorScheme(accent: Color, overrides: UiColorOverrides): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = if (isDarkForeground(accent)) Color.Black else Color.White,
    primaryContainer = overrides.userBubble
        ?: accent.copy(alpha = 0.40f).compositeOver(DarkSurface),
    onPrimaryContainer = OnSurface,
    secondary = accent,
    onSecondary = if (isDarkForeground(accent)) Color.Black else Color.White,
    secondaryContainer = accent.copy(alpha = 0.25f).compositeOver(DarkSurfaceVariant),
    onSecondaryContainer = OnSurface,
    tertiary = accent.copy(alpha = 0.8f),
    onTertiary = if (isDarkForeground(accent)) Color.Black else Color.White,
    background = overrides.background ?: DarkBackground,
    surface = overrides.background ?: DarkSurface,
    surfaceVariant = overrides.assistantBubble
        ?: accent.copy(alpha = 0.16f).compositeOver(DarkSurfaceVariant),
    onBackground = overrides.text ?: OnBackground,
    onSurface = overrides.text ?: OnSurface,
    onSurfaceVariant = overrides.text?.copy(alpha = 0.72f) ?: OnSurfaceVariant,
    error = Error,
)

/**
 * Dark scheme with per-part overrides but no accent pick (accent colors fall
 * back to the app defaults). Used when the user tweaks UI parts without
 * choosing an accent — e.g. the Terminal Green background alone.
 */
private fun overriddenDarkScheme(overrides: UiColorOverrides): ColorScheme = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    tertiary = SecondaryVariant,
    primaryContainer = overrides.userBubble ?: DarkSurfaceVariant,
    onPrimaryContainer = OnSurface,
    secondaryContainer = SecondaryVariant.copy(alpha = 0.25f).compositeOver(DarkSurfaceVariant),
    onSecondaryContainer = OnSurface,
    background = overrides.background ?: DarkBackground,
    surface = overrides.background ?: DarkSurface,
    surfaceVariant = overrides.assistantBubble ?: DarkSurfaceVariant,
    onBackground = overrides.text ?: OnBackground,
    onSurface = overrides.text ?: OnSurface,
    onSurfaceVariant = overrides.text?.copy(alpha = 0.72f) ?: OnSurfaceVariant,
    error = Error,
)

/** Per-UI-part color overrides (v0.1.49+). Null = derive from accent / default. */
data class UiColorOverrides(
    val background: Color? = null,
    val userBubble: Color? = null,
    val assistantBubble: Color? = null,
    val text: Color? = null,
    // v0.1.95: extra chat surfaces (code blocks, thinking box, tool cards, gauge)
    val codeBlock: Color? = null,
    val thinkingBox: Color? = null,
    val toolCard: Color? = null,
    val gaugeTrack: Color? = null,
) {
    val isEmpty: Boolean
        get() = background == null && userBubble == null && assistantBubble == null && text == null &&
            codeBlock == null && thinkingBox == null && toolCard == null && gaugeTrack == null
}

/**
 * Resolved extra-surface colors (non-null) for the chat (v0.1.95). Defaults
 * derive from the active scheme exactly as the pre-override code did
 * (surfaceVariant alpha-over the background), so a user who never touches the
 * new Appearance rows sees zero change. Provided via [LocalUiSurfaces] by
 * [HermexTheme]; consumers read the resolved color directly — no alpha math at
 * the call site.
 */
data class UiSurfaces(
    /** Code block header + highlighted content + inline code chips. */
    val codeBlock: Color,
    /** THINKING box (finished messages) + live thinking ticker pill. */
    val thinkingBox: Color,
    /** TOOLS box, live activity panel, tool card surfaces. */
    val toolCard: Color,
    /** Context-window gauge track (bar stays accent/error — semantic). */
    val gaugeTrack: Color,
)

/** CompositionLocal for the resolved extra chat surfaces (v0.1.95). */
val LocalUiSurfaces = staticCompositionLocalOf {
    UiSurfaces(
        codeBlock = Color.Unspecified,
        thinkingBox = Color.Unspecified,
        toolCard = Color.Unspecified,
        gaugeTrack = Color.Unspecified,
    )
}

@Composable
fun HermexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    accentColor: Color? = null,
    uiOverrides: UiColorOverrides = UiColorOverrides(),
    monospace: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // User-picked accent wins over everything (including dynamic/wallpaper)
        accentColor != null -> accentColorScheme(accentColor, uiOverrides)
        // No accent, but explicit per-part overrides → dark base + overrides
        !uiOverrides.isEmpty -> overriddenDarkScheme(uiOverrides)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // v0.1.95: resolve the extra chat surfaces — per-part overrides win,
    // otherwise derive from the scheme (same alpha math the pre-override code
    // used, so defaults are byte-identical to before).
    val uiSurfaces = remember(colorScheme, uiOverrides) {
        UiSurfaces(
            codeBlock = uiOverrides.codeBlock ?: colorScheme.surfaceVariant.copy(alpha = 0.55f),
            thinkingBox = uiOverrides.thinkingBox ?: colorScheme.surfaceVariant.copy(alpha = 0.35f),
            toolCard = uiOverrides.toolCard ?: colorScheme.surfaceVariant.copy(alpha = 0.35f),
            gaugeTrack = uiOverrides.gaugeTrack ?: colorScheme.surfaceVariant,
        )
    }

    // Set status bar color
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalUiSurfaces provides uiSurfaces) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = if (monospace) MonoTypography else Typography,
            content = content
        )
    }
}
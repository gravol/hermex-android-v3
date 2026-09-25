// File: app/src/main/java/com/hermex/android/ui/theme/Color.kt
package com.hermex.v3.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// App-specific colors
val DarkBackground = Color(0xFF0B141A)
val DarkSurface = Color(0xFF1A1F25)
val DarkSurfaceVariant = Color(0xFF242A31)
val Primary = Color(0xFF00D1FF)
val PrimaryVariant = Color(0xFF00B8E6)
val Secondary = Color(0xFF00D1FF)
val SecondaryVariant = Color(0xFF00B8E6)
val OnPrimary = Color(0xFF000000)
val OnSecondary = Color(0xFF000000)
val OnBackground = Color(0xFFE6E6E6)
val OnSurface = Color(0xFFE6E6E6)
val OnSurfaceVariant = Color(0xFF9BA3A0)
val Error = Color(0xFFB00020)

// Header logo colors
val HeaderLogoColors = listOf(
    HeaderLogoPreset("Yellow", Color(0xFFFFD700)),
    HeaderLogoPreset("Blue", Color(0xFF5B7CFF)),
    HeaderLogoPreset("Purple", Color(0xFFAF52DE)),
    HeaderLogoPreset("Red", Color(0xFFFF3B30)),
    HeaderLogoPreset("Green", Color(0xFF34C759)),
    HeaderLogoPreset("White", Color(0xFFFFFFFF)),
    HeaderLogoPreset("Custom", Color(0xFFFFD700))
)

data class HeaderLogoPreset(val name: String, val color: Color)

// Utility functions for color operations
fun hexToColor(hex: String): Color {
    val cleanHex = hex.trim().removePrefix("#")
    return when {
        cleanHex.length == 6 -> Color(
            red = ((cleanHex.substring(0, 2)).toInt(16) / 255.0f),
            green = ((cleanHex.substring(2, 4)).toInt(16) / 255.0f),
            blue = ((cleanHex.substring(4, 6)).toInt(16) / 255.0f)
        )
        else -> Color(0xFFFFD700)
    }
}

fun colorToHex(color: Color): String {
    return String.format("#%02X%02X%02X",
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )
}

// Calculate luminance for dark/light foreground detection
fun isDarkForeground(color: Color): Boolean {
    val luminance = 0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue
    return luminance > 0.62
}
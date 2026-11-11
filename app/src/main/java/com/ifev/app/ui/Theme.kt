package com.ifev.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Colors from the Pleos Connect UI Design Guide (Foundations > Colors): grayscale, background,
 * surface, text and button tokens and the informative colors, with day and night values. Two brand
 * colors mark the two cars being compared: the EV (teal) and the fuel car (amber).
 */
@Immutable
data class PleosColors(
    val isDark: Boolean,
    val background: Color,       // background_primary
    val surface: Color,          // surface_basic
    val surfaceHigh: Color,      // surface_high
    val surfaceAccent: Color,    // surface_accent
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textQuaternary: Color,
    val buttonFilled: Color,     // button_filled_enabled
    val onButtonFilled: Color,
    val buttonBasic: Color,      // button_basic_enabled
    val active: Color,           // informative_active
    val positive: Color,         // informative_positive
    val negative: Color,         // informative_negative
    val ev: Color,
    val evSoft: Color,
    val fuel: Color,
    val fuelSoft: Color,
)

val PleosLight = PleosColors(
    isDark = false,
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDEEF2),
    surfaceAccent = Color(0xFFE0E1E6),
    divider = Color(0xFFEDEEF2),
    textPrimary = Color(0xFF131417),
    textSecondary = Color(0xFF44464E),
    textTertiary = Color(0xFF686A72),
    // basic_400 (A4A8AE) is 2.4:1 on white, too faint for text; basic_600 keeps 5.3:1.
    textQuaternary = Color(0xFF686A72),
    buttonFilled = Color(0xFF44464E),
    onButtonFilled = Color(0xFFFFFFFF),
    buttonBasic = Color(0xFFEDEEF2),
    active = Color(0xFF02C265),
    positive = Color(0xFF0064FF),
    negative = Color(0xFFFE3D16),
    // Text-safe on white (4.5:1 or more, as the Pleos guide asks): teal 5.3:1, amber 5.1:1.
    ev = Color(0xFF00796F),
    evSoft = Color(0xFFDDF5F2),
    fuel = Color(0xFFA85A00),
    fuelSoft = Color(0xFFFFF0DB),
)

val PleosDark = PleosColors(
    isDark = true,
    background = Color(0xFF131417),
    surface = Color(0xFF1D1E21),
    surfaceHigh = Color(0xFF313236),
    surfaceAccent = Color(0xFF44464E),
    divider = Color(0xFF313236),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFE0E1E6),
    textTertiary = Color(0xFFA4A8AE),
    textQuaternary = Color(0xFF686A72),
    buttonFilled = Color(0xFFE0E1E6),
    onButtonFilled = Color(0xFF131417),
    buttonBasic = Color(0xFF313236),
    active = Color(0xFF00E074),
    positive = Color(0xFF0082FF),
    negative = Color(0xFFFF4C28),
    ev = Color(0xFF00D1C1),
    evSoft = Color(0xFF0E3B38),
    fuel = Color(0xFFFF9F1C),
    fuelSoft = Color(0xFF3D2A0E),
)

/** Pleos type scale (Headline 48–60, Title 32–40, Body 26–30, Label 20–24). Sizes are px at 160 dpi, so sp 1:1. */
object PleosType {
    val display = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Bold, lineHeight = 104.sp)
    val headline = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, lineHeight = 58.sp)
    val titleLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 50.sp)
    val title = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
    val bodyStrong = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp)
    val body = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Normal, lineHeight = 38.sp)
    val bodySmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Normal, lineHeight = 36.sp)
    val bodySmallStrong = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp)
    val label = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal, lineHeight = 32.sp)
    val labelStrong = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
    val labelSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal, lineHeight = 28.sp)
}

val LocalPleosColors = staticCompositionLocalOf { PleosLight }

/** Follows the car's day/night mode, as the design guide asks. */
@Composable
fun IfEvTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) PleosDark else PleosLight
    val scheme = if (darkTheme) {
        darkColorScheme(background = colors.background, surface = colors.surface, primary = colors.buttonFilled, onPrimary = colors.onButtonFilled)
    } else {
        lightColorScheme(background = colors.background, surface = colors.surface, primary = colors.buttonFilled, onPrimary = colors.onButtonFilled)
    }
    CompositionLocalProvider(LocalPleosColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

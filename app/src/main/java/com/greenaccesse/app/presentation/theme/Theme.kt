package com.greenaccesse.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.greenaccesse.app.settings.ThemeMode

private val GreenPrimaryDark = Color(0xFF4CC38A)
private val GreenPrimaryLight = Color(0xFF1B8A55)

private val DarkColors = darkColorScheme(
    primary = GreenPrimaryDark,
    onPrimary = Color(0xFF00240F),
    primaryContainer = Color(0xFF14432C),
    onPrimaryContainer = Color(0xFFB7F0CF),
    secondary = Color(0xFF9CC9B0),
    onSecondary = Color(0xFF0A2618),
    secondaryContainer = Color(0xFF233A2E),
    onSecondaryContainer = Color(0xFFCDE8D8),
    background = Color(0xFF0D1311),
    onBackground = Color(0xFFE1E6E3),
    surface = Color(0xFF0D1311),
    onSurface = Color(0xFFE1E6E3),
    surfaceVariant = Color(0xFF1F2A25),
    onSurfaceVariant = Color(0xFFA9B6AF),
    surfaceContainerLowest = Color(0xFF09100D),
    surfaceContainerLow = Color(0xFF131B17),
    surfaceContainer = Color(0xFF17201C),
    surfaceContainerHigh = Color(0xFF1C2622),
    surfaceContainerHighest = Color(0xFF232E29),
    outline = Color(0xFF52615A),
    outlineVariant = Color(0xFF2E3A35),
    error = Color(0xFFF2726A),
    onError = Color(0xFF3B0907),
    errorContainer = Color(0xFF5C1814),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = GreenPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4EFD6),
    onPrimaryContainer = Color(0xFF002111),
    secondary = Color(0xFF4D6357),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E8DA),
    onSecondaryContainer = Color(0xFF0A1F14),
    background = Color(0xFFF5F8F6),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF5F8F6),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDCE5DF),
    onSurfaceVariant = Color(0xFF404943),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF3F0),
    surfaceContainer = Color(0xFFE9EEEB),
    surfaceContainerHigh = Color(0xFFE3E9E5),
    surfaceContainerHighest = Color(0xFFDDE4E0),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFC0C9C3),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** Semantic status colours used by badges and the scanner. */
@Immutable
data class StatusColors(val success: Color, val warning: Color, val error: Color, val neutral: Color)

private val DarkStatus = StatusColors(Color(0xFF4CC38A), Color(0xFFE5B454), Color(0xFFF2726A), Color(0xFF8A9690))
private val LightStatus = StatusColors(Color(0xFF1B8A55), Color(0xFFA86B00), Color(0xFFBA1A1A), Color(0xFF6B7570))

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** Monospace style for UIDs. */
val UidTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 22.sp, letterSpacing = 1.sp)

@Composable
fun GreenAccesseTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
    }
}

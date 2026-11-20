package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val TechLiveDarkScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = StudioObsidian,
    primaryContainer = Color(0xFF003B4D),
    onPrimaryContainer = Color(0xFFB3F7FF),
    secondary = LiveCrimson,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF4D0D1D),
    onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = BroadcastIndigo,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF23265A),
    onTertiaryContainer = Color(0xFFE0E2FF),
    background = StudioObsidian,
    onBackground = TextOnDark,
    surface = StudioSurface,
    onSurface = TextOnDark,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = TextMutedOnDark,
    outline = StudioBorder,
    error = CriticalRed,
    onError = Color.White
)

private val TechLiveLightScheme = lightColorScheme(
    primary = DaylightCyanPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF4FF),
    onPrimaryContainer = Color(0xFF002A38),
    secondary = DaylightCrimson,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCE3),
    onSecondaryContainer = Color(0xFF4D0717),
    tertiary = DaylightIndigo,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E7FF),
    onTertiaryContainer = Color(0xFF1E1B4B),
    background = DaylightBackground,
    onBackground = TextOnLight,
    surface = DaylightSurface,
    onSurface = TextOnLight,
    surfaceVariant = DaylightSurfaceVariant,
    onSurfaceVariant = TextMutedOnLight,
    outline = DaylightBorder,
    error = CriticalRed,
    onError = Color.White
)

val TechLiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TechLiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) TechLiveDarkScheme else TechLiveLightScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = TechLiveShapes,
        content = content
    )
}

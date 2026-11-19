package com.ulgentube.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ulgentube.app.domain.model.ThemeMode

private val UlgenDarkColorScheme = darkColorScheme(
    primary = ElectricPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B1B5A),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = CoralPink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF4A162D),
    onSecondaryContainer = Color(0xFFFFD9E4),
    tertiary = CyanAccent,
    onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF004F58),
    onTertiaryContainer = Color(0xFF97F0FF),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceContainerHigh,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = CrimsonError,
    onError = Color.White,
    errorContainer = Color(0xFF4A151B),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val UlgenLightColorScheme = lightColorScheme(
    primary = ElectricPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE7FF),
    onPrimaryContainer = Color(0xFF280A6B),
    secondary = CoralPink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE1EC),
    onSecondaryContainer = Color(0xFF5C0B2D),
    tertiary = Color(0xFF00838F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD0F6FA),
    onTertiaryContainer = Color(0xFF00363D),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceContainerHigh,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

val UlgenShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun UlgenTubeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) {
                dynamicDarkColorScheme(context).copy(
                    background = DarkBackground,
                    surface = DarkSurface,
                    surfaceContainer = DarkSurfaceContainer,
                    surfaceContainerHigh = DarkSurfaceContainerHigh
                )
            } else {
                dynamicLightColorScheme(context)
            }
        }

        isDark -> UlgenDarkColorScheme
        else -> UlgenLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = UlgenTypography,
        shapes = UlgenShapes,
        content = content
    )
}

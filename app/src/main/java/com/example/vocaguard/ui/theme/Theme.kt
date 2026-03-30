package com.example.vocaguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary              = TealPrimary,
    onPrimary            = NavyDark,
    primaryContainer     = NavyMedium,
    onPrimaryContainer   = TealLight,
    secondary            = TealLight,
    onSecondary          = NavyDark,
    secondaryContainer   = NavyLight,
    onSecondaryContainer = TealLight,
    tertiary             = SuccessGreen,
    onTertiary           = NavyDark,
    tertiaryContainer    = Color(0xFF0D2E1C),
    onTertiaryContainer  = SuccessGreen,
    background           = NavyDark,
    onBackground         = Color.White,
    surface              = NavyMedium,
    onSurface            = Color.White,
    surfaceVariant       = NavyLight,
    onSurfaceVariant     = TealLight,
    error                = AlertRed,
    onError              = Color.White,
    errorContainer       = Color(0xFF3D1212),
    onErrorContainer     = Color(0xFFFF8A80),
)

private val LightColorScheme = lightColorScheme(
    primary              = TealDark,
    onPrimary            = Color.White,
    primaryContainer     = TealLight,
    onPrimaryContainer   = NavyDark,
    secondary            = NavyMedium,
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFCAF0F8),
    onSecondaryContainer = NavyDark,
    tertiary             = Color(0xFF1B7A4E),
    onTertiary           = Color.White,
    tertiaryContainer    = Color(0xFFB8F0D4),
    onTertiaryContainer  = Color(0xFF0A2E1C),
    background           = SurfaceGray,
    onBackground         = NavyDark,
    surface              = Color.White,
    onSurface            = NavyDark,
    surfaceVariant       = Color(0xFFE8F4F8),
    onSurfaceVariant     = NavyLight,
    error                = AlertRed,
    onError              = Color.White,
    errorContainer       = Color(0xFFFFDAD6),
    onErrorContainer     = Color(0xFF410002),
)

@Composable
fun VocaGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

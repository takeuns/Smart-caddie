package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val CaddyDarkColorScheme = darkColorScheme(
    primary = GolfGreenPrimary,
    secondary = GolfGreenSecondary,
    tertiary = GolfBunkerGold,
    background = GolfSlateBg,
    surface = GolfCardBg,
    onPrimary = GolfSlateBg,
    onSecondary = GolfSoftWhite,
    onBackground = GolfSoftWhite,
    onSurface = GolfSoftWhite,
    error = GolfError,
    surfaceVariant = GolfCardBg,
    outline = GolfCardBorder
)

private val CaddyLightColorScheme = lightColorScheme(
    primary = GolfGreenSecondary,
    secondary = GolfGreenDark,
    tertiary = GolfWaterBlue,
    background = Color(0xFFFAFCFA),
    surface = Color(0xFFEEF5EF),
    onPrimary = GolfSoftWhite,
    onSecondary = GolfSoftWhite,
    onBackground = GolfSlateBg,
    onSurface = GolfSlateBg,
    error = GolfError,
    surfaceVariant = Color(0xFFEEF5EF),
    outline = Color(0xFFD4E1D6)
)

@Composable
fun CaddyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // We set dynamicColor to false by default to preserve the premium branded green-slate aesthetic
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> CaddyDarkColorScheme
        else -> CaddyLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

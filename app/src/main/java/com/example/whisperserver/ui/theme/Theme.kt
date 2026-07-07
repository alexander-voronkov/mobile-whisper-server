package com.example.whisperserver.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6F5E),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF4C6358),
    tertiary = Color(0xFF3D6373),
    background = Color(0xFFF4F6F2),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD9C1),
    onPrimary = Color(0xFF00382A),
    secondary = Color(0xFFB2CCBE),
    tertiary = Color(0xFFA4CDDF),
    background = Color(0xFF0F1411),
    surface = Color(0xFF1A201C),
    error = Color(0xFFFFB4AB),
)

/** Semantic colors for the memory guard banner (stable across light/dark). */
object GuardColors {
    val Ok = Color(0xFF2E7D32)
    val Warn = Color(0xFFF9A825)
    val Block = Color(0xFFC62828)
}

@Composable
fun WhisperServerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // The redesign commits to a fixed brand palette (primary green), so dynamic
    // Material You theming is off by default — turn it on to opt back in.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content,
        )
    }
}

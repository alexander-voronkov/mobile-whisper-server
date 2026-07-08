package com.example.whisperserver.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic palette for the redesigned "compact" UI. These are the concrete
 * surface / border / accent tokens the phone screens paint with, kept separate
 * from the Material [androidx.compose.material3.ColorScheme] (which still drives
 * built-in components). Light values come straight from the approved design;
 * dark values are the matching low-light equivalents.
 */
data class AppColors(
    val screen: Color,
    val card: Color,
    val cardBorder: Color,
    val divider: Color,
    val navBar: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val primary: Color,
    val runningChip: Color,
    val runningChipText: Color,
    val runningDot: Color,
    val success: Color,
    val error: Color,
    val warn: Color,
    val chipOutline: Color,
    val accentChip: Color,
    val accentChipText: Color,
    val waveformActive: Color,
    val waveformInactive: Color,
    val isDark: Boolean,
)

val LightAppColors = AppColors(
    screen = Color(0xFFF4F6F2),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE1E5DF),
    divider = Color(0xFFEEF1EC),
    navBar = Color(0xFFEEF2EC),
    textPrimary = Color(0xFF191C1A),
    textSecondary = Color(0xFF5B6B60),
    textMuted = Color(0xFF8B968D),
    primary = Color(0xFF2E6F5E),
    runningChip = Color(0xFFB7E9C7),
    runningChipText = Color(0xFF0B5A2B),
    runningDot = Color(0xFF1E8E5A),
    success = Color(0xFF1E8E5A),
    error = Color(0xFFBA1A1A),
    warn = Color(0xFFB7860B),
    chipOutline = Color(0xFFC0C9C1),
    accentChip = Color(0xFFE7EEE9),
    accentChipText = Color(0xFF2B5347),
    waveformActive = Color(0xFF2E6F5E),
    waveformInactive = Color(0xFFB6C6BD),
    isDark = false,
)

val DarkAppColors = AppColors(
    screen = Color(0xFF0F1411),
    card = Color(0xFF1A201C),
    cardBorder = Color(0xFF2C332E),
    divider = Color(0xFF262C28),
    navBar = Color(0xFF151A17),
    textPrimary = Color(0xFFE2E3DE),
    textSecondary = Color(0xFFA6B4AA),
    textMuted = Color(0xFF79857C),
    primary = Color(0xFF8FD9C1),
    runningChip = Color(0xFF0E3A28),
    runningChipText = Color(0xFF9BE9C0),
    runningDot = Color(0xFF4CC38A),
    success = Color(0xFF4CC38A),
    error = Color(0xFFFFB4AB),
    warn = Color(0xFFE0B44B),
    chipOutline = Color(0xFF3A423C),
    accentChip = Color(0xFF22302A),
    accentChipText = Color(0xFF9FE3C6),
    waveformActive = Color(0xFF8FD9C1),
    waveformInactive = Color(0xFF3F4A44),
    isDark = true,
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/** Shorthand accessor: `AppColors.current`. */
val appColors: AppColors
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current

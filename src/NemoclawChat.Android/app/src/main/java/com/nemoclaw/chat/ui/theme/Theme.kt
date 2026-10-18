package com.nemoclaw.chat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    background = Color(0xFF0B0D10),
    surface = Color(0xFF181C23),
    surfaceVariant = Color(0xFF20242C),
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceContainerLow = Color(0xFF12151A),
    surfaceContainer = Color(0xFF181C23),
    surfaceContainerHigh = Color(0xFF20242C),
    surfaceContainerHighest = Color(0xFF262C38),
    surfaceTint = Color.Transparent,
    primary = Color(0xFFF5A524),
    onPrimary = Color(0xFF171009),
    primaryContainer = Color(0xFF302517),
    onPrimaryContainer = Color(0xFFF5A524),
    secondary = Color(0xFFC8D2E0),
    onSecondary = Color(0xFF0B0D10),
    secondaryContainer = Color(0xFF20242C),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFF5A524),
    onTertiary = Color(0xFF171009),
    tertiaryContainer = Color(0xFF302517),
    onTertiaryContainer = Color(0xFFF5A524),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFC8D2E0),
    error = Color(0xFFFF6B82),
    onError = Color(0xFF171009),
    errorContainer = Color(0xFF3A1418),
    onErrorContainer = Color(0xFFFF6B82),
    outline = Color(0xFF292E37),
    outlineVariant = Color(0xFF23262C)
)

@Composable
fun ChatClawTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content
    )
}

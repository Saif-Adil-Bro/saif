package com.saif.mosque.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = CardWhite,
    primaryContainer = GreenLight,
    onPrimaryContainer = GreenDark,
    secondary = Gold,
    onSecondary = Ink,
    secondaryContainer = GoldLight,
    onSecondaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = CardWhite,
    onSurface = Ink,
    error = ErrorRed,
    outline = Muted
)

@Composable
fun MosqueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}

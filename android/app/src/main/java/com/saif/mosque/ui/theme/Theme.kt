package com.saif.mosque.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val ColorSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFEDF2ED)
private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = CardWhite,
    primaryContainer = GreenLight,
    onPrimaryContainer = GreenDark,
    secondary = Gold,
    onSecondary = CardWhite,
    secondaryContainer = GoldLight,
    onSecondaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = CardWhite,
    surfaceVariant = ColorSurfaceVariant,
    onSurface = Ink,
    onSurfaceVariant = Muted,
    error = ErrorRed,
    outline = Muted,
    outlineVariant = Border
)
private val baseType = Typography()
private val AppType = Typography(
    displaySmall = baseType.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineMedium = baseType.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = baseType.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = baseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = baseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = baseType.labelLarge.copy(fontWeight = FontWeight.SemiBold)
)
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MosqueTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, typography = AppType, shapes = AppShapes, content = content)
}

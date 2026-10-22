package com.ayaks.winterforest.presentation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DeepPineNight = Color(0xFF091119)
val SlateCabinSurface = Color(0xFF12212E)
val FrostedCardSurface = Color(0xFF1A2F40)
val IceCyanPrimary = Color(0xFF7CC6FE)
val HearthAmberAccent = Color(0xFFF4A261)
val SpruceEmerald = Color(0xFF52B788)
val CrimsonDanger = Color(0xFFE76F51)
val SnowCrispText = Color(0xFFEDF6FC)
val MistMutedText = Color(0xFFA8C2D4)

private val WinterColorScheme = darkColorScheme(
    primary = IceCyanPrimary,
    onPrimary = Color(0xFF06131D),
    secondary = HearthAmberAccent,
    onSecondary = Color(0xFF1F1206),
    tertiary = SpruceEmerald,
    background = DeepPineNight,
    onBackground = SnowCrispText,
    surface = SlateCabinSurface,
    onSurface = SnowCrispText,
    surfaceVariant = FrostedCardSurface,
    onSurfaceVariant = MistMutedText,
    error = CrimsonDanger
)

@Composable
fun WinterForestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WinterColorScheme,
        content = content
    )
}

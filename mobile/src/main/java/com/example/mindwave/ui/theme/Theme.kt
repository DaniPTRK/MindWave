package com.example.mindwave.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * MindWave overall theme - colors & typography
 */
private val MindWaveLightColors = lightColorScheme(
    primary = WavePrimary,
    onPrimary = SurfaceCard,
    primaryContainer = WaveLightContainer,
    onPrimaryContainer = WaveAbyss,
    secondary = WaveDeep,
    onSecondary = SurfaceCard,
    secondaryContainer = WaveSurfaceVariant,
    onSecondaryContainer = WaveAbyss,
    tertiary = NavyBrand,
    onTertiary = SurfaceCard,
    background = PageBackground,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = WaveSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DividerColor,
    outlineVariant = DividerColor,
    error = StressCritical,
    onError = SurfaceCard,
)

@Composable
fun MindWaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MindWaveLightColors,
        typography = MindWaveTypography,
        content = content,
    )
}



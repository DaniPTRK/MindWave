package com.example.mindwave.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

// pallete which mimics the ocean vibe
val WavePrimary = Color(0xFF26C6DA)
val WaveDeep = Color(0xFF00838F)
val WaveAbyss = Color(0xFF006064)

private val MindWaveColors = Colors(
    primary = WavePrimary,
    primaryVariant = WaveDeep,
    secondary = Color(0xFF4DD0E1),
    secondaryVariant = WaveAbyss,
    background = Color(0xFF001417),
    surface = Color(0xFF00282E),
    error = Color(0xFFE74C3C),
    onPrimary = Color(0xFF002023),
    onSecondary = Color(0xFF00201F),
    onBackground = Color(0xFFE0F7FA),
    onSurface = Color(0xFFE0F7FA),
    onError = Color(0xFF000000),
)

@Composable
fun MindWaveTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colors = MindWaveColors,
        content = content
    )
}

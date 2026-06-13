package com.example.mindwave.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * MindWave palette
 * Light, calm surface with a 5-step stress scale
 */

// Brand/Wave
val WavePrimary = Color(0xFF26C6DA)
val WaveDeep = Color(0xFF00838F)
val WaveAbyss = Color(0xFF006064)
val NavyBrand = Color(0xFF1B3A5C)
val WaveLightContainer = Color(0xFFB2EBF2)
val WaveSurfaceVariant = Color(0xFFE0F7FA)

// Surfaces
val PageBackground = Color(0xFFF0FDFD)
val SurfaceCard = Color(0xFFFFFFFF)
val DividerColor = Color(0xFFE0E0E0)

// Text
val TextPrimary = Color(0xFF212121)
val TextSecondary = Color(0xFF757575)
val TextHint = Color(0xFF9E9E9E)

// Sress scales
val StressRelaxed = Color(0xFF27AE60)
val StressCalm = Color(0xFF82C91E)
val StressElevated = Color(0xFFF1C40F)
val StressHigh = Color(0xFFF39C12)
val StressCritical = Color(0xFFE74C3C)

/**
 * Maps a 0–100 stress percentage to its scale colour
 */
fun stressColor(percent: Int): Color = when {
    percent <= 30 -> StressRelaxed
    percent <= 50 -> StressCalm
    percent <= 65 -> StressElevated
    percent <= 80 -> StressHigh
    else -> StressCritical
}

/**
 * Human-readable likelihood label for a 0–100 stress probability percentage.
 * Framed as likelihood, not intensity, since the model outputs P(stress class).
 */
fun stressLabel(percent: Int): String = when {
    percent <= 30 -> "Stress unlikely"
    percent <= 50 -> "Low likelihood"
    percent <= 65 -> "Elevated likelihood"
    percent <= 80 -> "High likelihood"
    else          -> "Stress likely"
}

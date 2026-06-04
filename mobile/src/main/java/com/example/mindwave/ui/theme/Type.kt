package com.example.mindwave.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * MindWave typography
 */
val OutfitFontFamily = FontFamily.Default

private fun outfit(
    weight: FontWeight,
    size: Int,
    lineHeight: Int = size + 6,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = OutfitFontFamily,
    fontWeight = weight,
    fontStyle = FontStyle.Normal,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

val MindWaveTypography = Typography(
    displayLarge = outfit(FontWeight.Bold, 57, 64, (-0.25)),
    displayMedium = outfit(FontWeight.Bold, 45, 52),
    displaySmall = outfit(FontWeight.SemiBold, 36, 44),
    headlineLarge = outfit(FontWeight.SemiBold, 32, 40),
    headlineMedium = outfit(FontWeight.SemiBold, 28, 36),
    headlineSmall = outfit(FontWeight.SemiBold, 24, 32),
    titleLarge = outfit(FontWeight.SemiBold, 22, 28),
    titleMedium = outfit(FontWeight.Medium, 16, 24, 0.15),
    titleSmall = outfit(FontWeight.Medium, 14, 20, 0.1),
    bodyLarge = outfit(FontWeight.Normal, 16, 24, 0.5),
    bodyMedium = outfit(FontWeight.Normal, 14, 20, 0.25),
    bodySmall = outfit(FontWeight.Normal, 12, 16, 0.4),
    labelLarge = outfit(FontWeight.Medium, 14, 20, 0.1),
    labelMedium = outfit(FontWeight.Medium, 12, 16, 0.5),
    labelSmall = outfit(FontWeight.Medium, 11, 16, 0.5),
)

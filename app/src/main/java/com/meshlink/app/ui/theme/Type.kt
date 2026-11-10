package com.meshlink.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight,
    fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp
)
val Typography = Typography(
    headlineLarge = text(32, 40, FontWeight.Bold),
    headlineMedium = text(28, 36, FontWeight.Bold),
    headlineSmall = text(24, 32, FontWeight.SemiBold),
    titleLarge = text(22, 28, FontWeight.SemiBold),
    titleMedium = text(16, 24, FontWeight.SemiBold),
    titleSmall = text(14, 20, FontWeight.SemiBold),
    bodyLarge = text(16, 24),
    bodyMedium = text(14, 20),
    bodySmall = text(12, 18),
    labelLarge = text(14, 20, FontWeight.Medium),
    labelMedium = text(12, 16, FontWeight.Medium),
    labelSmall = text(11, 16, FontWeight.Medium)
)

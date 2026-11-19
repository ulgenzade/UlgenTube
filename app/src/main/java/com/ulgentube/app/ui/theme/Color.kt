package com.ulgentube.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Marka & Gradient Renkleri
val ElectricPurple = Color(0xFF7C4DFF)
val CoralPink = Color(0xFFFF4D8D)
val SoftViolet = Color(0xFFB388FF)
val DeepIndigo = Color(0xFF311B92)
val CyanAccent = Color(0xFF00E5FF)
val EmeraldSuccess = Color(0xFF10B981)
val AmberWarning = Color(0xFFF59E0B)
val CrimsonError = Color(0xFFFF5252)

// Koyu Tema Yüzeyleri (Derin Gece)
val DarkBackground = Color(0xFF0B0E14)
val DarkSurface = Color(0xFF0F131B)
val DarkSurfaceContainerLow = Color(0xFF121721)
val DarkSurfaceContainer = Color(0xFF141923)
val DarkSurfaceContainerHigh = Color(0xFF1C2331)
val DarkSurfaceContainerHighest = Color(0xFF242D3F)
val DarkOnSurface = Color(0xFFF1F5F9)
val DarkOnSurfaceVariant = Color(0xFF94A3B8)
val DarkOutline = Color(0xFF334155)
val DarkOutlineVariant = Color(0xFF1E293B)

// Açık Tema Yüzeyleri (Temiz & Modern)
val LightBackground = Color(0xFFF7F8FC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF2F4FA)
val LightSurfaceContainer = Color(0xFFECEFF7)
val LightSurfaceContainerHigh = Color(0xFFE3E8F4)
val LightSurfaceContainerHighest = Color(0xFFD8DFEF)
val LightOnSurface = Color(0xFF0F172A)
val LightOnSurfaceVariant = Color(0xFF475569)
val LightOutline = Color(0xFFCBD5E1)
val LightOutlineVariant = Color(0xFFE2E8F0)

// Premium Gradient Fırçaları
val UlgenPrimaryGradient: Brush = Brush.horizontalGradient(
    colors = listOf(ElectricPurple, CoralPink)
)

val UlgenSubtleGradient: Brush = Brush.linearGradient(
    colors = listOf(
        ElectricPurple.copy(alpha = 0.22f),
        CoralPink.copy(alpha = 0.16f)
    )
)

val UlgenCardHighlightGradient: Brush = Brush.horizontalGradient(
    colors = listOf(
        ElectricPurple.copy(alpha = 0.14f),
        CoralPink.copy(alpha = 0.10f)
    )
)

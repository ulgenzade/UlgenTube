package com.ulgentube.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Marka & Rafine Vurgu Renkleri (Swiss Minimal & Yüksek Kontrast)
val ElectricPurple = Color(0xFFE11D48) // Birincil rafine video kırmızısı (Rose 600)
val CoralPink = Color(0xFFF43F5E)      // İkincil yumuşak vurgu (Rose 500)
val SoftViolet = Color(0xFFFB7185)     // Açık vurgu tonu (Rose 400)
val DeepIndigo = Color(0xFF881337)     // Koyu vurgu konteyneri (Rose 900)
val CyanAccent = Color(0xFF38BDF8)     // Teknik / bilgi vurgusu (Sky 400)
val EmeraldSuccess = Color(0xFF10B981)
val AmberWarning = Color(0xFFF59E0B)
val CrimsonError = Color(0xFFEF4444)

// Koyu Tema Yüzeyleri (Nötr Karbon)
val DarkBackground = Color(0xFF09090B)
val DarkSurface = Color(0xFF0F0F12)
val DarkSurfaceContainerLow = Color(0xFF111114)
val DarkSurfaceContainer = Color(0xFF18181C)
val DarkSurfaceContainerHigh = Color(0xFF222228)
val DarkSurfaceContainerHighest = Color(0xFF2C2C34)
val DarkOnSurface = Color(0xFFFAFAFA)
val DarkOnSurfaceVariant = Color(0xFFA1A1AA)
val DarkOutline = Color(0xFF3F3F46)
val DarkOutlineVariant = Color(0xFF27272E)

// Açık Tema Yüzeyleri (Temiz Swiss Nötr)
val LightBackground = Color(0xFFFAFAFA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF4F4F5)
val LightSurfaceContainer = Color(0xFFEEEEF0)
val LightSurfaceContainerHigh = Color(0xFFE4E4E7)
val LightSurfaceContainerHighest = Color(0xFFD4D4D8)
val LightOnSurface = Color(0xFF09090B)
val LightOnSurfaceVariant = Color(0xFF52525B)
val LightOutline = Color(0xFFD4D4D8)
val LightOutlineVariant = Color(0xFFE4E4E7)

// Rafine Gradyan Fırçaları (Abartısız)
val UlgenPrimaryGradient: Brush = Brush.horizontalGradient(
    colors = listOf(ElectricPurple, CoralPink)
)

val UlgenSubtleGradient: Brush = Brush.linearGradient(
    colors = listOf(
        ElectricPurple.copy(alpha = 0.14f),
        CoralPink.copy(alpha = 0.08f)
    )
)

val UlgenCardHighlightGradient: Brush = Brush.horizontalGradient(
    colors = listOf(
        ElectricPurple.copy(alpha = 0.08f),
        Color.Transparent
    )
)

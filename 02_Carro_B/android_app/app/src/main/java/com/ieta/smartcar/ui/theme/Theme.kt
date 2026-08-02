package com.ieta.smartcar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Paleta del mando: azul profundo de fondo, cian y azul electrico como acentos. */
object Neon {
    val Background = Color(0xFF05070F)
    val Surface = Color(0xFF0B1224)
    val SurfaceHigh = Color(0xFF121C33)
    val Outline = Color(0xFF1E2C4A)

    val Cyan = Color(0xFF00E5FF)
    val Blue = Color(0xFF2979FF)
    val DeepBlue = Color(0xFF0D47A1)

    val Danger = Color(0xFFFF3D5A)
    val Warning = Color(0xFFFFB300)
    val Ok = Color(0xFF00E676)

    val TextPrimary = Color(0xFFE6F3FF)
    val TextMuted = Color(0xFF7A8CA8)
}

private val SmartCarColors = darkColorScheme(
    primary = Neon.Cyan,
    onPrimary = Neon.Background,
    secondary = Neon.Blue,
    background = Neon.Background,
    onBackground = Neon.TextPrimary,
    surface = Neon.Surface,
    onSurface = Neon.TextPrimary,
    error = Neon.Danger
)

@Composable
fun SmartCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SmartCarColors,
        content = content
    )
}

package com.ieta.smartcar.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ieta.smartcar.ui.theme.Neon
import kotlin.math.abs

/** Rune Bluetooth oficial en coordenadas 24x24, dibujada como Path para no depender de iconos extra. */
private const val BLUETOOTH_PATH =
    "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 " +
        "-4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.12L13,18.17v-3.76l1.88,1.71z"

@Composable
fun BluetoothGlyph(
    tint: Color,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 28.dp
) {
    Canvas(modifier = modifier.size(glyphSize)) {
        val path = PathParser().parsePathString(BLUETOOTH_PATH).toPath()
        val scale = size.minDimension / 24f
        scale(scale, scale, pivot = Offset.Zero) {
            drawPath(path, color = tint)
        }
    }
}

/** Chip de estado con punto pulsante: se lee de un vistazo sin dejar de mirar el carro. */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Neon.Surface)
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = Neon.TextPrimary,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Barra bipolar de potencia por rueda. El cero queda al centro, de modo que retroceso y
 * avance se distinguen por el lado hacia el que crece la barra, no por un signo diminuto.
 */
@Composable
fun WheelMeter(
    label: String,
    power: Int,
    modifier: Modifier = Modifier
) {
    val level by animateFloatAsState(
        targetValue = (power / 255f).coerceIn(-1f, 1f),
        label = "level"
    )
    val accent = if (power >= 0) Neon.Cyan else Neon.Warning

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Neon.TextMuted, fontSize = 9.sp, letterSpacing = 1.sp)
            Text(
                text = "$power",
                color = if (abs(power) > 0) accent else Neon.TextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            val trackHeight = size.height
            val half = size.width / 2f

            drawRoundRect(
                color = Neon.Surface,
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f)
            )
            drawLine(
                color = Neon.Outline,
                start = Offset(half, 0f),
                end = Offset(half, trackHeight),
                strokeWidth = 1.dp.toPx()
            )

            if (abs(level) > 0.001f) {
                val width = abs(level) * half
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.55f), accent)
                    ),
                    topLeft = Offset(if (level >= 0f) half else half - width, 0f),
                    size = Size(width, trackHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f)
                )
            }
        }
    }
}

/** Boton circular del panel inferior, al estilo de los mandos de la referencia. */
@Composable
fun NeonRoundButton(
    caption: String,
    value: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 62.dp,
    onClick: () -> Unit
) {
    val ring by animateColorAsState(
        targetValue = if (active) accent else Neon.Outline,
        label = "ring"
    )
    val view = LocalView.current

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(if (active) accent.copy(alpha = 0.14f) else Neon.Surface)
                .border(1.5.dp, ring, CircleShape)
                .clickable {
                    // Confirmacion tactil: manejando no se mira la pantalla, y un boton
                    // que no responde invita a tocarlo dos veces.
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (!active) return@Canvas
                drawCircle(
                    color = accent.copy(alpha = 0.18f),
                    radius = size.minDimension / 2f + 4.dp.toPx(),
                    style = Stroke(width = 4.dp.toPx())
                )
            }
            Text(
                text = value,
                color = if (active) accent else Neon.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = caption,
            color = Neon.TextMuted,
            fontSize = 9.sp,
            letterSpacing = 1.sp
        )
    }
}

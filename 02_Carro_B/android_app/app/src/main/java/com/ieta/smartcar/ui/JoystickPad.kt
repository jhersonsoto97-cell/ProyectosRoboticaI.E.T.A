package com.ieta.smartcar.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ieta.smartcar.ui.theme.Neon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Ejes que el stick deja mover. En modo arcade cada stick controla uno solo. */
enum class StickAxis { BOTH, VERTICAL, HORIZONTAL }

/**
 * Joystick analogico dibujado en Canvas.
 *
 * Entrega coordenadas normalizadas -1..1 con Y positivo hacia arriba, que es la
 * convencion del mundo fisico, no la de pantalla. La conversion se hace una sola vez
 * aqui para que el resto de la app no tenga que pensar en el signo invertido.
 */
@Composable
fun JoystickPad(
    label: String,
    axis: StickAxis,
    modifier: Modifier = Modifier,
    diameter: Dp = 200.dp,
    accent: Color = Neon.Cyan,
    onChange: (x: Float, y: Float) -> Unit
) {
    val callback by rememberUpdatedState(onChange)

    var knob by remember { mutableStateOf(Offset.Zero) }   // Desplazamiento en pixeles.
    var pressed by remember { mutableStateOf(false) }

    // Al soltar, la perilla vuelve al centro con un rebote elastico. Al presionar responde
    // sin animacion: cualquier suavizado en esa direccion se percibe como lag del mando.
    val settle by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) snap() else spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "settle"
    )

    Box(modifier = modifier.size(diameter)) {
        Canvas(
            modifier = Modifier
                .size(diameter)
                .pointerInput(axis) {
                    val radius = minOf(size.width, size.height) / 2f
                    val knobRadius = radius * 0.30f
                    val travel = radius - knobRadius - 8.dp.toPx()
                    val center = Offset(size.width / 2f, size.height / 2f)

                    fun publish(position: Offset) {
                        var delta = position - center
                        if (axis == StickAxis.VERTICAL) delta = Offset(0f, delta.y)
                        if (axis == StickAxis.HORIZONTAL) delta = Offset(delta.x, 0f)

                        val distance = hypot(delta.x, delta.y)
                        if (distance > travel) {
                            delta *= (travel / distance)
                        }
                        knob = delta
                        callback(delta.x / travel, -delta.y / travel)
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        publish(down.position)
                        down.consume()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            publish(change.position)
                            change.consume()
                        }

                        // El comando de parada sale ya; el knob conserva su valor solo
                        // para que la animacion de retorno tenga desde donde partir.
                        pressed = false
                        callback(0f, 0f)
                    }
                }
        ) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val knobRadius = radius * 0.30f
            val travel = radius - knobRadius - 8.dp.toPx()
            val position = center + knob * settle
            val magnitude = (hypot(knob.x, knob.y) / travel).coerceIn(0f, 1f)

            drawBase(center, radius, accent)
            drawTicks(center, radius, accent, knob, magnitude)
            drawAxisHint(center, radius, axis, accent)
            if (magnitude > 0.02f) {
                drawVector(center, position, accent, magnitude)
            }
            drawKnob(position, knobRadius, accent, magnitude)
        }

        Text(
            text = label,
            color = Neon.TextMuted,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = diameter * 0.06f)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBase(
    center: Offset,
    radius: Float,
    accent: Color
) {
    // Pozo del stick: degradado radial para dar sensacion de concavidad.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Neon.SurfaceHigh, Neon.Surface, Color(0xFF050A16)),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )

    // Halo exterior: tres trazos de alfa decreciente imitan el brillo de un LED difuso.
    for (step in 1..3) {
        drawCircle(
            color = accent.copy(alpha = 0.16f / step),
            radius = radius + step * 3.dp.toPx(),
            center = center,
            style = Stroke(width = 3.dp.toPx())
        )
    }

    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(accent, Neon.Blue, accent.copy(alpha = 0.35f)),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius)
        ),
        radius = radius,
        center = center,
        style = Stroke(width = 2.dp.toPx())
    )

    drawCircle(
        color = Neon.Outline,
        radius = radius * 0.62f,
        center = center,
        style = Stroke(width = 1.dp.toPx())
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTicks(
    center: Offset,
    radius: Float,
    accent: Color,
    knob: Offset,
    magnitude: Float
) {
    val tickCount = 48
    val inner = radius - 12.dp.toPx()
    val outer = radius - 5.dp.toPx()
    val knobAngle = Math.toDegrees(kotlin.math.atan2(knob.y, knob.x).toDouble()).toFloat()

    for (index in 0 until tickCount) {
        val angle = index * 360f / tickCount
        val radians = Math.toRadians(angle.toDouble())

        // Las marcas cercanas a la direccion empujada se encienden: realimentacion
        // visual de hacia donde va el carro sin necesidad de leer un numero.
        var separation = abs(angle - knobAngle) % 360f
        if (separation > 180f) separation = 360f - separation
        val lit = magnitude > 0.05f && separation < 32f
        val alpha = if (lit) 0.35f + 0.65f * magnitude else 0.20f

        drawLine(
            color = if (lit) accent.copy(alpha = alpha) else Neon.Outline.copy(alpha = alpha),
            start = center + Offset(
                (cos(radians) * inner).toFloat(),
                (sin(radians) * inner).toFloat()
            ),
            end = center + Offset(
                (cos(radians) * outer).toFloat(),
                (sin(radians) * outer).toFloat()
            ),
            strokeWidth = if (lit) 2.5f.dp.toPx() else 1.5f.dp.toPx()
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxisHint(
    center: Offset,
    radius: Float,
    axis: StickAxis,
    accent: Color
) {
    if (axis == StickAxis.BOTH) return

    val reach = radius * 0.48f
    val hint = accent.copy(alpha = 0.22f)
    val ends = if (axis == StickAxis.VERTICAL) {
        listOf(Offset(0f, -reach), Offset(0f, reach))
    } else {
        listOf(Offset(-reach, 0f), Offset(reach, 0f))
    }

    ends.forEach { end ->
        drawLine(
            color = hint,
            start = center + end * 0.45f,
            end = center + end,
            strokeWidth = 2.dp.toPx()
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVector(
    center: Offset,
    position: Offset,
    accent: Color,
    magnitude: Float
) {
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(accent.copy(alpha = 0.15f), accent.copy(alpha = 0.85f)),
            start = center,
            end = position
        ),
        start = center,
        end = position,
        strokeWidth = (3f + 4f * magnitude).dp.toPx()
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawKnob(
    position: Offset,
    knobRadius: Float,
    accent: Color,
    magnitude: Float
) {
    // Resplandor bajo el pulgar, mas intenso cuanto mas se empuja el stick.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.30f + 0.25f * magnitude), Color.Transparent),
            center = position,
            radius = knobRadius * 2.1f
        ),
        radius = knobRadius * 2.1f,
        center = position
    )

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF6FE9FF), accent, Neon.DeepBlue, Color(0xFF061024)),
            center = position - Offset(knobRadius * 0.35f, knobRadius * 0.45f),
            radius = knobRadius * 1.9f
        ),
        radius = knobRadius,
        center = position
    )

    drawCircle(
        color = accent.copy(alpha = 0.9f),
        radius = knobRadius,
        center = position,
        style = Stroke(width = 2.dp.toPx())
    )

    // Reflejo especular: vende la idea de una perilla fisica de plastico.
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.45f), Color.Transparent),
            center = position - Offset(knobRadius * 0.25f, knobRadius * 0.45f),
            radius = knobRadius * 0.7f
        ),
        topLeft = position - Offset(knobRadius * 0.7f, knobRadius * 0.8f),
        size = Size(knobRadius * 1.0f, knobRadius * 0.6f)
    )
}

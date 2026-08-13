package com.ieta.smartcar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ieta.smartcar.carro.TipoCarro
import com.ieta.smartcar.ui.theme.Neon
import kotlin.math.min

/**
 * Cada carro visto desde arriba.
 *
 * Dibujado y no fotografiado: una foto de cada chasis habria que sacarla, recortarla
 * y mantenerla, y a este tamano se veria como una mancha oscura sobre fondo oscuro.
 * El trazo deja leer de un vistazo cual es cual, que es lo unico que la tarjeta
 * necesita resolver.
 *
 * La silueta es la misma en los dos y lo que cambia es lo que llevan encima: asi se
 * lee que son dos versiones del mismo carro, que es lo que son.
 */
@Composable
fun IlustracionCarro(
    tipo: TipoCarro,
    acento: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val u = min(size.width, size.height)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val trazo = (u * 0.022f).coerceAtLeast(1.5f)

        dibujarChasis(cx, cy, u, trazo, acento)

        when (tipo) {
            TipoCarro.CARRO_B -> dibujarRadioBluetooth(cx, cy, u, trazo, acento)
            TipoCarro.EXPLORADOR -> dibujarCabezaSonar(cx, cy, u, trazo, acento)
        }
    }
}

private fun DrawScope.dibujarChasis(
    cx: Float, cy: Float, u: Float, trazo: Float, acento: Color,
) {
    /* Las ruedas van primero para que el borde del chasis quede encima y la union
     * se lea como una pieza sola y no como cuatro rectangulos sueltos. */
    val ruedaAncho = u * 0.11f
    val ruedaAlto = u * 0.21f

    for (lado in listOf(-1f, 1f)) {
        for (eje in listOf(-1f, 1f)) {
            drawRoundRect(
                color = acento.copy(alpha = 0.55f),
                topLeft = Offset(
                    cx + lado * u * 0.30f - ruedaAncho / 2f,
                    cy + eje * u * 0.21f - ruedaAlto / 2f
                ),
                size = Size(ruedaAncho, ruedaAlto),
                cornerRadius = CornerRadius(ruedaAncho * 0.42f)
            )
        }
    }

    val ancho = u * 0.46f
    val alto = u * 0.66f

    drawRoundRect(
        color = acento.copy(alpha = 0.12f),
        topLeft = Offset(cx - ancho / 2f, cy - alto / 2f),
        size = Size(ancho, alto),
        cornerRadius = CornerRadius(u * 0.07f)
    )
    drawRoundRect(
        color = acento,
        topLeft = Offset(cx - ancho / 2f, cy - alto / 2f),
        size = Size(ancho, alto),
        cornerRadius = CornerRadius(u * 0.07f),
        style = Stroke(width = trazo)
    )

    /* La placa de control, para que el chasis no quede vacio por dentro. */
    drawRoundRect(
        color = acento.copy(alpha = 0.35f),
        topLeft = Offset(cx - u * 0.13f, cy - u * 0.09f),
        size = Size(u * 0.26f, u * 0.24f),
        cornerRadius = CornerRadius(u * 0.02f),
        style = Stroke(width = trazo * 0.7f)
    )
}

/** Carro B: las ondas del modulo Bluetooth saliendo del frente. */
private fun DrawScope.dibujarRadioBluetooth(
    cx: Float, cy: Float, u: Float, trazo: Float, acento: Color,
) {
    val base = cy - u * 0.33f

    drawCircle(color = acento, radius = u * 0.035f, center = Offset(cx, base))

    /* Tres arcos que se abren hacia adelante y se apagan con la distancia, como se
     * dibuja siempre una emision. */
    for (i in 1..3) {
        val radio = u * (0.09f + 0.075f * i)
        drawArc(
            color = acento.copy(alpha = 0.55f - 0.13f * i),
            startAngle = 215f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(cx - radio, base - radio),
            size = Size(radio * 2f, radio * 2f),
            style = Stroke(width = trazo * 0.85f)
        )
    }
}

/** Explorador: el sonar sobre el brazo del servo, barriendo. */
private fun DrawScope.dibujarCabezaSonar(
    cx: Float, cy: Float, u: Float, trazo: Float, acento: Color,
) {
    val base = cy - u * 0.33f

    /* El eje del servo. */
    drawCircle(
        color = acento.copy(alpha = 0.5f),
        radius = u * 0.055f,
        center = Offset(cx, base),
        style = Stroke(width = trazo * 0.8f)
    )

    /* Los dos transductores del HC-SR04, que es lo que lo vuelve reconocible. */
    for (lado in listOf(-1f, 1f)) {
        drawCircle(
            color = acento,
            radius = u * 0.038f,
            center = Offset(cx + lado * u * 0.055f, base - u * 0.045f),
            style = Stroke(width = trazo * 0.8f)
        )
    }

    /* El abanico del barrido. Se abre mas que las ondas del Carro B para que las
     * dos tarjetas no se confundan de reojo. */
    for (i in 1..3) {
        val radio = u * (0.13f + 0.085f * i)
        drawArc(
            color = Neon.Ok.copy(alpha = 0.40f - 0.10f * i),
            startAngle = 195f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(cx - radio, base - radio),
            size = Size(radio * 2f, radio * 2f),
            style = Stroke(width = trazo * 0.75f)
        )
    }
}

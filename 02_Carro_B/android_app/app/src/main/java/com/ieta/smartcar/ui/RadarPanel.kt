package com.ieta.smartcar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ieta.smartcar.ui.theme.Neon
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lo que el sonar ve, en el hueco que queda entre los dos sticks.
 *
 * Va en el centro y no en una pantalla aparte porque su valor es mirarlo mientras se
 * maneja: un obstaculo que aparece a la derecha solo sirve si se ve antes de doblar.
 *
 * Los angulos llegan en el marco del carro, con el cero al frente, asi que se dibujan
 * sin convertir nada: arriba es hacia donde apunta el carro.
 */
@Composable
fun RadarPanel(
    ecos: Map<Int, Float>,
    anguloActual: Int?,
    conectado: Boolean,
    progresoEscaneo: Int,
    alcanceCm: Float = ALCANCE_CM,
    modifier: Modifier = Modifier,
    ancho: Dp = 190.dp,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(ancho)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ancho * 0.62f)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Neon.Surface.copy(alpha = 0.85f), Color(0xFF04070E))
                    )
                )
                .border(1.dp, Neon.Outline, RoundedCornerShape(14.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(ancho * 0.62f)) {
                /* El carro se dibuja abajo al centro y el barrido se abre hacia arriba,
                 * de modo que la pantalla queda orientada como el carro: lo que aparece
                 * a la derecha del radar esta a la derecha del carro. */
                val origen = Offset(size.width / 2f, size.height * 0.92f)
                val radio = size.height * 0.80f

                dibujarRejilla(origen, radio)
                dibujarEcos(ecos, origen, radio, alcanceCm)
                anguloActual?.let { dibujarAguja(it, origen, radio) }

                /* El carro, para que el centro no quede vacio y se lea de donde salen
                 * las medidas. */
                drawCircle(color = Neon.Cyan, radius = 3.dp.toPx(), center = origen)
            }

            if (!conectado) {
                Text(
                    text = "SIN ENLACE",
                    color = Neon.TextMuted,
                    fontSize = 9.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "SONAR",
                color = Neon.TextMuted,
                fontSize = 8.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold
            )

            /* Durante un escaneo el carro se calla varios segundos mientras gira. Sin
             * este numero el radar parece congelado y la reaccion natural es tocar
             * cosas hasta romperlo. */
            Text(
                text = when {
                    progresoEscaneo < 100 -> "ESCANEANDO $progresoEscaneo%"
                    ecos.isEmpty() -> "SIN ECOS"
                    else -> "${masCercano(ecos)} CM"
                },
                color = if (progresoEscaneo < 100) Neon.Warning else Neon.Cyan,
                fontSize = 8.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun masCercano(ecos: Map<Int, Float>): Int =
    ecos.values.minOrNull()?.toInt() ?: 0

private fun androidx.compose.ui.graphics.drawscope.DrawScope.dibujarRejilla(
    origen: Offset,
    radio: Float,
) {
    val guion = PathEffect.dashPathEffect(floatArrayOf(3f, 7f))

    /* Tres anillos alcanzan para dar escala sin llenar de lineas un recuadro chico. */
    for (i in 1..3) {
        val r = radio * i / 3f
        drawArc(
            color = Neon.Outline.copy(alpha = 0.7f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(origen.x - r, origen.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = 1f, pathEffect = guion)
        )
    }

    /* Radios cada 45 grados: dan referencia de direccion sin competir con los ecos. */
    for (grados in listOf(-90, -45, 0, 45, 90)) {
        val rad = Math.toRadians((grados - 90).toDouble())
        drawLine(
            color = Neon.Outline.copy(alpha = 0.5f),
            start = origen,
            end = Offset(
                origen.x + (radio * cos(rad)).toFloat(),
                origen.y + (radio * sin(rad)).toFloat()
            ),
            strokeWidth = 1f,
            pathEffect = guion
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.dibujarEcos(
    ecos: Map<Int, Float>,
    origen: Offset,
    radio: Float,
    alcanceCm: Float,
) {
    for ((grados, distancia) in ecos) {
        val fraccion = (distancia / alcanceCm).coerceIn(0f, 1f)
        val rad = Math.toRadians((grados - 90).toDouble())
        val punto = Offset(
            origen.x + (radio * fraccion * cos(rad)).toFloat(),
            origen.y + (radio * fraccion * sin(rad)).toFloat()
        )

        /* Lo cercano se pinta en alerta y lo lejano en cian. Manejando no hay tiempo de
         * leer numeros: el color es lo que avisa. */
        val color = when {
            distancia < 25f -> Neon.Danger
            distancia < 60f -> Neon.Warning
            else -> Neon.Cyan
        }

        drawCircle(color = color.copy(alpha = 0.25f), radius = 5f, center = punto)
        drawCircle(color = color, radius = 2.5f, center = punto)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.dibujarAguja(
    grados: Int,
    origen: Offset,
    radio: Float,
) {
    val rad = Math.toRadians((grados - 90).toDouble())
    val punta = Offset(
        origen.x + (radio * cos(rad)).toFloat(),
        origen.y + (radio * sin(rad)).toFloat()
    )

    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Neon.Ok.copy(alpha = 0.7f), Color.Transparent),
            start = origen,
            end = punta
        ),
        start = origen,
        end = punta,
        strokeWidth = 2.5f,
        cap = StrokeCap.Round
    )
}

/** Alcance util del HC-SR04, el mismo que declara el firmware. */
private const val ALCANCE_CM = 250f

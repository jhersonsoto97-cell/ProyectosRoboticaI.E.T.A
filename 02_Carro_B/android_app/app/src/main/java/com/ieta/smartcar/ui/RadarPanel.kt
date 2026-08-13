package com.ieta.smartcar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ieta.smartcar.ui.theme.Neon
import kotlin.math.cos
import kotlin.math.roundToInt
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
    alcanceCm: Int,
    onAlcance: (Int) -> Unit,
    modifier: Modifier = Modifier,
    ancho: Dp = 190.dp,
) {
    val medidor = rememberTextMeasurer()
    val anchoLamina = ancho - ANCHO_SLIDER - 8.dp
    val paso = pasoDeAnillo(alcanceCm)

    Row(modifier = modifier.width(ancho)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .width(anchoLamina)
                    .height(anchoLamina * 0.62f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Neon.Surface.copy(alpha = 0.85f), Color(0xFF04070E))
                        )
                    )
                    .border(1.dp, Neon.Outline, RoundedCornerShape(14.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    /* El carro se dibuja abajo al centro y el barrido se abre hacia
                     * arriba, de modo que la pantalla queda orientada como el carro: lo
                     * que aparece a la derecha del radar esta a la derecha del carro. */
                    val origen = Offset(size.width / 2f, size.height * 0.92f)
                    val radio = size.height * 0.80f

                    dibujarRejilla(origen, radio, alcanceCm, paso, medidor)
                    dibujarEcos(ecos, origen, radio, alcanceCm.toFloat())
                    anguloActual?.let { dibujarAguja(it, origen, radio) }

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
                modifier = Modifier.width(anchoLamina),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ANILLOS DE ${paso} CM",
                    color = Neon.TextMuted,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold
                )

                /* Durante un escaneo el carro se calla varios segundos mientras gira.
                 * Sin este numero el radar parece congelado y la reaccion natural es
                 * tocar cosas hasta romperlo. */
                Text(
                    text = when {
                        progresoEscaneo < 100 -> "ESCANEANDO $progresoEscaneo%"
                        ecos.isEmpty() -> "SIN ECOS"
                        else -> "${ecos.values.minOrNull()?.toInt() ?: 0} CM"
                    },
                    color = if (progresoEscaneo < 100) Neon.Warning else Neon.Cyan,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        SliderAlcance(
            alcanceCm = alcanceCm,
            onAlcance = onAlcance,
            alto = anchoLamina * 0.62f
        )
    }
}

/**
 * Alcance del radar, en vertical al lado de la lamina.
 *
 * Vertical y no horizontal porque asi comparte altura con el radar y no le roba banda a
 * un centro que ya esta ajustado. Arriba se ve mas lejos, abajo se ve mas fino: el
 * mismo sentido que tiene alejar o acercar la vista.
 */
@Composable
private fun SliderAlcance(
    alcanceCm: Int,
    onAlcance: (Int) -> Unit,
    alto: Dp,
) {
    /* Los valores se redondean a decenas. Un alcance de 137 cm no significa nada para
     * quien mira, y con el dedo es imposible repetirlo. */
    fun desdeY(y: Float, altoPx: Float) {
        val fraccion = (1f - (y / altoPx)).coerceIn(0f, 1f)
        val crudo = ALCANCE_MIN + fraccion * (ALCANCE_MAX - ALCANCE_MIN)
        onAlcance((crudo / 10f).roundToInt() * 10)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (alcanceCm >= 100) "${alcanceCm / 100f}m" else "${alcanceCm}cm",
            color = Neon.Cyan,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(4.dp))

        Canvas(
            modifier = Modifier
                .width(ANCHO_SLIDER)
                .height(alto - 26.dp)
                .pointerInput(Unit) {
                    detectTapGestures { desdeY(it.y, size.height.toFloat()) }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { cambio, _ ->
                        desdeY(cambio.position.y, size.height.toFloat())
                    }
                }
        ) {
            val fraccion =
                ((alcanceCm - ALCANCE_MIN) / (ALCANCE_MAX - ALCANCE_MIN)).coerceIn(0f, 1f)
            val carril = 4.dp.toPx()
            val x = size.width / 2f
            val yPulgar = size.height * (1f - fraccion)

            drawRoundRect(
                color = Neon.Outline,
                topLeft = Offset(x - carril / 2f, 0f),
                size = Size(carril, size.height),
                cornerRadius = CornerRadius(carril / 2f)
            )

            /* Se pinta desde abajo: la barra llena crece con el alcance, igual que el
             * numero de arriba. */
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Neon.Cyan, Neon.Cyan.copy(alpha = 0.35f)),
                    startY = yPulgar,
                    endY = size.height
                ),
                topLeft = Offset(x - carril / 2f, yPulgar),
                size = Size(carril, size.height - yPulgar),
                cornerRadius = CornerRadius(carril / 2f)
            )

            drawCircle(color = Neon.Cyan.copy(alpha = 0.25f), radius = 9.dp.toPx(),
                center = Offset(x, yPulgar))
            drawCircle(color = Neon.Cyan, radius = 5.dp.toPx(), center = Offset(x, yPulgar))
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = "ZOOM",
            color = Neon.TextMuted,
            fontSize = 7.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Separacion entre anillos, elegida para que siempre queden entre tres y cinco.
 *
 * Los pasos posibles son valores que una persona puede sumar de cabeza. Repartir el
 * alcance en cuatro partes iguales daria anillos de 37 o 62 cm, que obligan a hacer
 * cuentas justo cuando no hay tiempo de hacerlas.
 */
private fun pasoDeAnillo(alcanceCm: Int): Int =
    PASOS.firstOrNull { alcanceCm / it <= 5 } ?: PASOS.last()

private fun DrawScope.dibujarRejilla(
    origen: Offset,
    radio: Float,
    alcanceCm: Int,
    paso: Int,
    medidor: TextMeasurer,
) {
    /* Los anillos van en cian con muy poca opacidad y no en el color de borde del tema:
     * ese color es un azul oscuro casi igual al fondo del recuadro, asi que la rejilla
     * se perdia. El cian se separa del fondo por tono en vez de por brillo, que es lo
     * que la deja legible sin volverla protagonista frente a los ecos. */
    val guion = PathEffect.dashPathEffect(floatArrayOf(3f, 6f))

    var distancia = paso
    while (distancia <= alcanceCm) {
        val r = radio * distancia / alcanceCm
        drawArc(
            color = Neon.Cyan.copy(alpha = 0.20f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(origen.x - r, origen.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = 1.2f, pathEffect = guion)
        )

        /* El valor del anillo, sobre el eje vertical. Sin el, cambiar el alcance mueve
         * los puntos sin que se sepa a que distancia quedaron. */
        val rotulo = medidor.measure(
            // La division entera dejaba 100 y 150 rotulados los dos como "1m", que
            // convierte dos anillos distintos en el mismo dato.
            text = when {
                distancia < 100 -> "$distancia"
                distancia % 100 == 0 -> "${distancia / 100}m"
                else -> "${distancia / 100}.${(distancia % 100) / 10}m"
            },
            style = TextStyle(
                color = Neon.TextMuted.copy(alpha = 0.75f),
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold
            )
        )
        drawText(
            textLayoutResult = rotulo,
            topLeft = Offset(origen.x + 3.dp.toPx(), origen.y - r - rotulo.size.height / 2f)
        )

        distancia += paso
    }

    /* Radios cada 45 grados: dan referencia de direccion sin competir con los ecos. Van
     * a la mitad de opacidad que los anillos, que son los que llevan la escala. */
    for (grados in listOf(-90, -45, 0, 45, 90)) {
        val rad = Math.toRadians((grados - 90).toDouble())
        drawLine(
            color = Neon.Cyan.copy(alpha = 0.10f),
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

private fun DrawScope.dibujarEcos(
    ecos: Map<Int, Float>,
    origen: Offset,
    radio: Float,
    alcanceCm: Float,
) {
    for ((grados, distancia) in ecos) {
        /* Lo que queda mas alla del alcance elegido no se dibuja pegado al borde: eso
         * inventaria una pared donde solo hay algo lejos. Simplemente no se muestra. */
        if (distancia > alcanceCm) continue

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

private fun DrawScope.dibujarAguja(grados: Int, origen: Offset, radio: Float) {
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

private val ANCHO_SLIDER = 26.dp

/** Alcance util del HC-SR04, el mismo que declara el firmware. */
private const val ALCANCE_MAX = 250f

/** Por debajo de esto el sensor no mide: su propio transductor sigue vibrando. */
private const val ALCANCE_MIN = 30f

/** Separaciones que una persona puede sumar de cabeza. */
private val PASOS = listOf(10, 20, 25, 50, 100)

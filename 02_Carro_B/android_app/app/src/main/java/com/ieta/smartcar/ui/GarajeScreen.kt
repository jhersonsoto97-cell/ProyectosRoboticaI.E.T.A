package com.ieta.smartcar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ieta.smartcar.R
import com.ieta.smartcar.carro.Capacidades
import com.ieta.smartcar.carro.Carro
import com.ieta.smartcar.carro.Garaje
import com.ieta.smartcar.carro.TipoCarro
import com.ieta.smartcar.ui.theme.Neon

/**
 * Eleccion de carro, justo despues de la presentacion.
 *
 * Es una pantalla y no un menu escondido porque es la primera decision de cada
 * sesion y porque, mostrandola, se ve de entrada que la app maneja dos carros. Un
 * selector dentro de los ajustes lo dejaria como un detalle de configuracion.
 */
@Composable
fun GarajeScreen(
    ultimoUsado: Carro?,
    onElegir: (Carro) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A1428), Neon.Background, Color(0xFF04060D))
                )
            )
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val altoTarjeta = (maxHeight * 0.70f).coerceIn(190.dp, 340.dp)
            val anchoTarjeta = altoTarjeta * 0.78f

            // Un solo tamano para las dos tarjetas, calculado con el nombre mas largo.
            // Midiendo cada una por separado, "Carro B" quedaba enorme al lado de
            // "Explorador" y el par se leia como dos disenos distintos.
            //
            // El 0.66 es lo que ocupa de ancho una mayuscula de este peso por cada punto
            // de tamano. Medido sobre el render y no calculado: la primera estimacion
            // fue 0.56 y a "Explorador" le quedaba la ultima letra afuera.
            //
            // Va con margen a proposito. Que el nombre roce el borde se lee como que la
            // tarjeta le queda chica, no como una decision.
            val letrasDelMasLargo = Garaje.todos.maxOf { it.nombre.length }
            val tamanoNombre =
                ((anchoTarjeta.value - 26f) / (letrasDelMasLargo * 0.66f)).coerceIn(16f, 44f)

            Image(
                painter = painterResource(R.drawable.ic_escudo),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 18.dp, top = 14.dp)
                    .height((maxHeight * 0.15f).coerceIn(40.dp, 64.dp))
                    .alpha(0.9f)
            )

            Image(
                painter = painterResource(R.drawable.ic_brand),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 18.dp, top = 22.dp)
                    .height((maxHeight * 0.09f).coerceIn(24.dp, 40.dp))
            )

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "ELEGI UN CARRO",
                    color = Neon.Cyan.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (carro in Garaje.todos) {
                        TarjetaCarro(
                            carro = carro,
                            esUltimo = carro.nombre == ultimoUsado?.nombre,
                            alto = altoTarjeta,
                            ancho = anchoTarjeta,
                            tamanoNombre = tamanoNombre,
                            onClick = { onElegir(carro) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * La tarjeta de un carro.
 *
 * El nombre va enorme y cortado por el borde de abajo: a tamano prudente compite
 * con el resto y hay que buscarlo, y desbordando se lee de reojo desde el otro
 * lado de la mesa, que es como se usa esta pantalla.
 *
 * Los arcos punteados del fondo no son adorno. Son la forma de un barrido de
 * sonar, que es lo que estos carros hacen, y ademas amarran la tipografia a la
 * ilustracion en vez de dejarlas como dos bloques sueltos.
 */
@Composable
private fun TarjetaCarro(
    carro: Carro,
    esUltimo: Boolean,
    alto: Dp,
    ancho: Dp,
    tamanoNombre: Float,
    onClick: () -> Unit,
) {
    val acento = when (carro.tipo) {
        TipoCarro.CARRO_B -> Neon.Cyan
        TipoCarro.EXPLORADOR -> Neon.Blue
    }

    Box(
        modifier = Modifier
            .width(ancho)
            .height(alto)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        acento.copy(alpha = 0.34f),
                        acento.copy(alpha = 0.10f),
                        Color(0xFF060A16),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(0f, Float.POSITIVE_INFINITY)
                )
            )
            .border(
                width = if (esUltimo) 2.dp else 1.dp,
                color = acento.copy(alpha = if (esUltimo) 0.9f else 0.35f),
                shape = RoundedCornerShape(26.dp)
            )
            .clickable(onClick = onClick)
    ) {
        ArcosDeBarrido(acento = acento, modifier = Modifier.fillMaxSize())

        // Brillo de arriba, que es lo que le da volumen al degradado plano.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(alto * 0.42f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(acento.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(ancho.value * 1.2f, 0f),
                        radius = ancho.value * 2.4f
                    )
                )
        )

        Chip(texto = carro.cerebro, acento = acento,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp))

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(if (esUltimo) acento else acento.copy(alpha = 0.28f))
        )

        IlustracionCarro(
            tipo = carro.tipo,
            acento = acento,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = -alto * 0.09f)
                .fillMaxWidth()
                .height(alto * 0.46f)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(start = 14.dp, bottom = 4.dp)
            ) {
                for (etiqueta in etiquetasDe(carro.capacidades)) {
                    Etiqueta(etiqueta, acento)
                }
            }

            Text(
                text = carro.medio.uppercase(),
                color = Neon.TextMuted,
                fontSize = 8.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 15.dp)
            )

            // El desborde es a proposito: el bloque se empuja hacia abajo y el recorte
            // de la tarjeta se come el pie de las letras.
            Text(
                text = carro.nombre.uppercase(),
                color = Neon.TextPrimary,
                fontSize = tamanoNombre.sp,
                lineHeight = (tamanoNombre * 1.05f).sp,
                letterSpacing = (-tamanoNombre * 0.03f).sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp)
                    .offset(y = tamanoNombre.dp * 0.30f)
            )
        }
    }
}

/** Arcos concentricos punteados, como el barrido de un sonar visto desde arriba. */
@Composable
private fun ArcosDeBarrido(acento: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val centro = Offset(size.width * 0.5f, size.height * 0.80f)
        val guion = PathEffect.dashPathEffect(floatArrayOf(2.5f, 9f))

        for (i in 1..5) {
            val radio = size.width * (0.13f * i)
            drawArc(
                color = acento.copy(alpha = 0.30f - 0.045f * i),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(centro.x - radio, centro.y - radio),
                size = Size(radio * 2f, radio * 2f),
                style = Stroke(width = 1.2f, pathEffect = guion)
            )
        }

        /* Unos pocos ecos sueltos sobre los arcos, como los puntos de la referencia.
         * Fijos y no aleatorios: la tarjeta se redibuja al tocarla y unos puntos que
         * saltan de lugar delatarian que no significan nada. */
        val ecos = listOf(0.22f to 0.34f, 0.55f to 0.52f, 0.78f to 0.30f, 0.40f to 0.66f)
        for ((fx, fr) in ecos) {
            val angulo = Math.toRadians((200f + 140f * fx).toDouble())
            val radio = size.width * 0.65f * fr
            drawCircle(
                color = acento.copy(alpha = 0.55f),
                radius = 2.2f,
                center = Offset(
                    centro.x + (radio * kotlin.math.cos(angulo)).toFloat(),
                    centro.y + (radio * kotlin.math.sin(angulo)).toFloat()
                )
            )
        }
    }
}

@Composable
private fun Chip(texto: String, acento: Color, modifier: Modifier = Modifier) {
    Text(
        text = texto,
        color = Neon.TextPrimary.copy(alpha = 0.92f),
        fontSize = 9.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(99.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, acento.copy(alpha = 0.35f), RoundedCornerShape(99.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

@Composable
private fun Etiqueta(texto: String, acento: Color) {
    Text(
        text = texto,
        color = acento,
        fontSize = 8.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(acento.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

private fun etiquetasDe(capacidades: Capacidades): List<String> = buildList {
    add("MANDO")
    if (capacidades.radar) add("RADAR")
    if (capacidades.escaneo) add("MAPA")
    if (capacidades.trimsReversa) add("TRIMS")
}

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
            val altoTarjeta = (maxHeight * 0.70f).coerceIn(190.dp, 460.dp)
            val anchoTarjeta = altoTarjeta * 0.78f

            // Un solo tamano de nombre para las dos tarjetas, calculado con el mas largo.
            // Midiendo cada una por separado, "Carro B" quedaba enorme al lado de
            // "Explorador" y el par se leia como dos disenos distintos.
            //
            // El 0.66 es lo que ocupa de ancho una mayuscula de este peso por cada punto
            // de tamano. Medido sobre el render y no calculado: la primera estimacion
            // fue 0.56 y a "Explorador" le quedaba la ultima letra afuera.
            val letrasDelMasLargo = Garaje.todos.maxOf { it.nombre.length }
            val tamanoNombre =
                ((anchoTarjeta.value - 26f) / (letrasDelMasLargo * 0.66f)).coerceIn(16f, 44f)

            Image(
                painter = painterResource(R.drawable.ic_escudo),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 18.dp, top = 14.dp)
                    // El tope es lo que manda en una tablet, no la fraccion: a 800 dp de
                    // alto la cuenta da de sobra y lo que recortaba era el limite, pensado
                    // cuando la app solo corria en telefonos. El escudo lleva leyendas
                    // finas y a 90 dp se volvia una mancha de color sin forma.
                    .height((maxHeight * 0.17f).coerceIn(44.dp, 150.dp))
            )

            Image(
                painter = painterResource(R.drawable.ic_brand),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 18.dp, top = 22.dp)
                    .height((maxHeight * 0.10f).coerceIn(26.dp, 96.dp))
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
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
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

/** Curvatura de las esquinas. Compartida por la sombra, el recorte y el borde. */
private val FORMA = RoundedCornerShape(26.dp)

/**
 * La tarjeta de un carro.
 *
 * El nombre va enorme y cortado por el borde de abajo: a tamano prudente compite
 * con el resto y hay que buscarlo, y desbordando se lee de reojo desde el otro
 * lado de la mesa, que es como se usa esta pantalla.
 *
 * El volumen de capsula no sale de redondear mas las esquinas sino de como cae la
 * luz: sombra propia, borde iluminado arriba y apagado abajo, un lustre en la mitad
 * de arriba y penumbra en la de abajo. Agrandar el radio la habria hecho mas
 * redonda pero igual de plana.
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
            // La sombra va tenida del acento y no en negro: sobre un fondo casi negro
            // una sombra negra no se ve, y el halo de color es lo que despega la
            // tarjeta del fondo y la deja flotando.
            .shadow(
                elevation = if (esUltimo) 26.dp else 16.dp,
                shape = FORMA,
                ambientColor = acento,
                spotColor = acento
            )
            .clip(FORMA)
            // Base opaca antes del tinte. El degradado del acento es semitransparente
            // arriba, y sin algo solido debajo la sombra de color que va detras se
            // transparentaba a traves de la tarjeta y dibujaba su propio rectangulo.
            .background(Color(0xFF05080F))
            .background(
                Brush.verticalGradient(
                    listOf(
                        acento.copy(alpha = 0.30f),
                        acento.copy(alpha = 0.11f),
                        Color.Transparent,
                    )
                )
            )
            .clickable(onClick = onClick)
    ) {
        ArcosDeBarrido(acento = acento, modifier = Modifier.fillMaxSize())
        LuzDeCapsula(modifier = Modifier.fillMaxSize())

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

            // Apoyado sobre el borde pero sin tocarlo. El desborde de la primera version
            // dejaba el canto de la tarjeta cortando el pie de las letras, y un canto
            // iluminado atravesando una palabra se lee como un defecto y no como estilo.
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
                    .padding(start = 12.dp, bottom = 12.dp)
            )
        }

        // El canto, encima de todo. Va con degradado y no con un color plano: claro
        // arriba, del acento en el medio y oscuro abajo, que es como se comporta el
        // filo de una pieza redondeada bajo una luz que viene de arriba. Un borde de
        // un solo color deja la tarjeta pegada al fondo, como recortada en papel.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = if (esUltimo) 2.dp else 1.4.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (esUltimo) 0.75f else 0.5f),
                            acento.copy(alpha = if (esUltimo) 0.85f else 0.45f),
                            Color.Black.copy(alpha = 0.5f),
                        )
                    ),
                    shape = FORMA
                )
        )
    }
}

/**
 * Las tres capas de luz que dan el volumen de capsula.
 *
 * Van juntas en un Canvas y no como tres cajas con degradado de fondo: Brush pide las
 * coordenadas del centro y del radio en pixeles, y pasarle valores en dp deja el
 * reflejo del tamano equivocado y con un canto recto a la vista. Aqui el tamano llega
 * ya en pixeles y las tres se ubican contra el mismo sistema.
 */
@Composable
private fun LuzDeCapsula(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        /* Lustre: la mitad de arriba recibe luz y se apaga hacia el medio. Es lo que
         * hace leer la superficie como curva en vez de plana. */
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.11f), Color.Transparent),
                startY = 0f,
                endY = size.height * 0.55f
            ),
            size = Size(size.width, size.height * 0.55f)
        )

        /* Reflejo especular. Un unico punto de luz definido es lo que separa una
         * superficie lustrosa de una mate; sin el, el lustre solo aclara. */
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.34f, -size.height * 0.06f),
                radius = size.width * 0.85f
            ),
            radius = size.width * 0.85f,
            center = Offset(size.width * 0.34f, -size.height * 0.06f)
        )

        /* Penumbra de abajo. Sin ella la tarjeta se ve iluminada pero chata: el cuerpo
         * aparece recien cuando hay una zona que la luz no alcanza. */
        val altoSombra = size.height * 0.42f
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                startY = size.height - altoSombra,
                endY = size.height
            ),
            topLeft = Offset(0f, size.height - altoSombra),
            size = Size(size.width, altoSombra)
        )
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
            .background(Color.White.copy(alpha = 0.12f))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.45f), acento.copy(alpha = 0.2f))
                ),
                shape = RoundedCornerShape(99.dp)
            )
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

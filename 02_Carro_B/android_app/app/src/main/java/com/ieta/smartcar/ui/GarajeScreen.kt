package com.ieta.smartcar.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
            val altoTarjeta = (maxHeight * 0.62f).coerceIn(180.dp, 320.dp)

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

                Spacer(Modifier.height(18.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (carro in Garaje.todos) {
                        TarjetaCarro(
                            carro = carro,
                            esUltimo = carro.nombre == ultimoUsado?.nombre,
                            alto = altoTarjeta,
                            onClick = { onElegir(carro) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TarjetaCarro(
    carro: Carro,
    esUltimo: Boolean,
    alto: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val acento = when (carro.tipo) {
        TipoCarro.CARRO_B -> Neon.Cyan
        TipoCarro.EXPLORADOR -> Neon.Blue
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(alto * 0.86f)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    listOf(acento.copy(alpha = 0.10f), Neon.Surface.copy(alpha = 0.9f))
                )
            )
            .border(
                width = if (esUltimo) 2.dp else 1.dp,
                color = acento.copy(alpha = if (esUltimo) 0.85f else 0.4f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 12.dp)
    ) {
        IlustracionCarro(
            tipo = carro.tipo,
            acento = acento,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.15f)
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = carro.nombre.uppercase(),
            color = Neon.TextPrimary,
            fontSize = 15.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(3.dp))

        Text(
            text = "${carro.cerebro}  ·  ${carro.medio}",
            color = Neon.TextMuted,
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        // Lo que distingue a cada carro, dicho en una linea. Evita que alguien elija
        // el equivocado y descubra recien adentro que no tiene lo que buscaba.
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (etiqueta in etiquetasDe(carro.capacidades)) {
                Etiqueta(etiqueta, acento)
            }
        }

        if (esUltimo) {
            Spacer(Modifier.height(9.dp))
            Text(
                text = "ULTIMO USADO",
                color = acento.copy(alpha = 0.8f),
                fontSize = 8.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun Etiqueta(texto: String, acento: Color) {
    Text(
        text = texto,
        color = acento.copy(alpha = 0.9f),
        fontSize = 8.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(acento.copy(alpha = 0.13f))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

private fun etiquetasDe(capacidades: Capacidades): List<String> = buildList {
    add("MANDO")
    if (capacidades.radar) add("RADAR")
    if (capacidades.escaneo) add("MAPA")
    if (capacidades.trimsReversa) add("TRIMS")
}

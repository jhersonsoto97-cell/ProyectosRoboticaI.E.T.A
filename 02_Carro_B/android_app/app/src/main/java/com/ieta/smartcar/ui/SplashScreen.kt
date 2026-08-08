package com.ieta.smartcar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ieta.smartcar.R
import com.ieta.smartcar.ui.theme.Neon

/**
 * Presentacion de marca al abrir.
 *
 * Dura poco menos de un segundo y medio y se puede saltar tocando la pantalla: una
 * presentacion que no se puede omitir se vuelve un estorbo a la decima vez que se abre
 * la app, y durante una demostracion se abre muchas veces.
 *
 * La animacion son dos anillos que se expanden desde donde luego quedan los sticks. No
 * es decoracion arbitraria: anticipa la disposicion de la pantalla de manejo, asi que
 * cuando termina el ojo ya sabe donde mirar.
 */
@Composable
fun SplashScreen(onTerminado: () -> Unit) {
    val progreso = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progreso.animateTo(1f, animationSpec = tween(DURACION_MS, easing = LinearEasing))
        onTerminado()
    }

    // Sin indicacion ni efecto de pulsacion: es un area para saltar, no un boton.
    val interaccion = remember { MutableInteractionSource() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A1428), Neon.Background, Color(0xFF04060D))
                )
            )
            .clickable(
                interactionSource = interaccion,
                indication = null,
                onClick = onTerminado
            ),
        contentAlignment = Alignment.Center
    ) {
        val p = progreso.value
        // Se captura aca porque dentro del Column el receptor cambia y maxHeight, que
        // pertenece al ambito de BoxWithConstraints, deja de estar accesible.
        val altoLogo = (maxHeight * 0.13f).coerceIn(34.dp, 58.dp)

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Los anillos nacen donde van los sticks en la pantalla de manejo.
            val y = size.height * 0.58f
            val centros = listOf(
                Offset(size.width * 0.20f, y) to Neon.Cyan,
                Offset(size.width * 0.80f, y) to Neon.Blue
            )
            val radioMaximo = size.minDimension * 0.42f

            for ((centro, color) in centros) {
                // Tres anillos desfasados producen la sensacion de pulso que sale del
                // stick, en vez de un unico circulo que crece y desaparece.
                for (indice in 0 until ANILLOS) {
                    val desfase = indice * 0.16f
                    val avance = ((p - desfase) / (1f - desfase)).coerceIn(0f, 1f)
                    if (avance <= 0f) continue

                    val radio = radioMaximo * avance
                    // Se apaga hacia el final del recorrido, no linealmente, para que el
                    // borde exterior no se corte de golpe.
                    val alfa = (1f - avance) * (1f - avance) * 0.55f

                    drawCircle(
                        color = color.copy(alpha = alfa),
                        radius = radio,
                        center = centro,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // El logo entra despues de los anillos: primero el movimiento, luego la marca.
            val entradaLogo = ((p - 0.18f) / 0.35f).coerceIn(0f, 1f)

            Image(
                painter = painterResource(R.drawable.ic_brand),
                contentDescription = null,
                modifier = Modifier
                    .height(altoLogo)
                    .alpha(entradaLogo)
                    // Arranca levemente mas grande y se asienta: un logo que crece desde
                    // cero parece un globo, y desde poco mas del tamano final aterriza.
                    .scale(1.06f - 0.06f * entradaLogo)
            )

            Spacer(Modifier.height(14.dp))

            val entradaTexto = ((p - 0.42f) / 0.30f).coerceIn(0f, 1f)
            androidx.compose.material3.Text(
                text = "SMART CAR",
                color = Neon.TextMuted,
                fontSize = 11.sp,
                letterSpacing = 6.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alpha(entradaTexto * 0.9f)
            )
        }
    }
}

/** Corta, porque durante una demostracion la app se abre muchas veces. */
private const val DURACION_MS = 1400
private const val ANILLOS = 3

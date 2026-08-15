package com.ieta.smartcar.alerta

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Que tan cerca esta lo que hay al frente. */
enum class Riesgo { NINGUNO, CERCA, PELIGRO }

/**
 * Avisa por vibracion y sonido cuando el carro se acerca a algo.
 *
 * El radar ya muestra el obstaculo, pero manejando se mira el carro y no la pantalla.
 * Esta alerta llega sin mirar, que es cuando sirve.
 *
 * El ritmo se acelera con la cercania, como el sensor de parqueo de un auto. Es una
 * metafora que todo el mundo ya entiende, asi que no hay nada que aprender: si suena mas
 * seguido, hay menos espacio.
 */
class AlertaProximidad(contexto: Context) {

    private val vibrador: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (contexto.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        contexto.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /* Un generador de tonos evita empaquetar un archivo de audio y decodificarlo. Para
     * un pitido corto, traer un sonido propio seria peso y trabajo sin nada a cambio. */
    private val tonos: ToneGenerator? =
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUMEN) }.getOrNull()

    private var ultimoPulso = 0L

    var riesgo: Riesgo = Riesgo.NINGUNO
        private set

    /**
     * Se llama en cada tick del lazo de control, veinte veces por segundo.
     *
     * Decide aqui adentro si toca pulsar en vez de traer su propio temporizador: un
     * segundo reloj corriendo en paralelo se desincroniza del estado real y termina
     * avisando de obstaculos que ya se esquivaron.
     */
    fun evaluar(distanciaCm: Float?, avanzando: Boolean) {
        /* Solo yendo hacia adelante. El sonar mira al frente, asi que retrocediendo no
         * sabe nada de lo que hay atras, y avisar de algo que quedo adelante mientras se
         * retrocede es exactamente al reves de lo util.
         *
         * Con el carro quieto tampoco: no hay riesgo de chocar, y un carro detenido
         * frente a una pared vibrando sin parar es algo que se termina apagando. */
        if (!avanzando || distanciaCm == null || distanciaCm <= 0f) {
            riesgo = Riesgo.NINGUNO
            return
        }

        riesgo = when {
            distanciaCm <= DISTANCIA_PELIGRO -> Riesgo.PELIGRO
            distanciaCm <= DISTANCIA_AVISO -> Riesgo.CERCA
            else -> Riesgo.NINGUNO
        }

        if (riesgo == Riesgo.NINGUNO) {
            return
        }

        val ahora = SystemClock.uptimeMillis()
        if (ahora - ultimoPulso < periodoPara(distanciaCm)) {
            return
        }
        ultimoPulso = ahora

        pulsar(riesgo == Riesgo.PELIGRO)
    }

    /**
     * Cuanto esperar entre dos avisos, segun lo cerca que este.
     *
     * Interpolado y no por escalones: con dos o tres velocidades fijas, el aviso salta de
     * golpe y no se siente que uno se este acercando. Variando parejo, el oido nota el
     * cambio antes de que la distancia cruce ningun umbral.
     */
    private fun periodoPara(distanciaCm: Float): Long {
        val recortada = distanciaCm.coerceIn(DISTANCIA_MINIMA, DISTANCIA_AVISO)
        val fraccion =
            (recortada - DISTANCIA_MINIMA) / (DISTANCIA_AVISO - DISTANCIA_MINIMA)
        return (PERIODO_MINIMO_MS + fraccion * (PERIODO_MAXIMO_MS - PERIODO_MINIMO_MS)).toLong()
    }

    /**
     * Vibra con una figura y no con un golpe suelto.
     *
     * Los sticks y los botones ya vibran una vez al tocarlos, asi que un pulso unico de
     * la alerta se siente exactamente igual y no se distingue de haber rozado un control.
     * Un ritmo si se reconoce sin pensarlo: dos toques cortos avisan, y un zumbido largo
     * y continuo no se parece a nada mas de la interfaz.
     */
    private fun pulsar(peligro: Boolean) {
        val figura = if (peligro) FIGURA_PELIGRO else FIGURA_CERCA
        val fuerzas = if (peligro) FUERZAS_PELIGRO else FUERZAS_CERCA

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrador?.vibrate(VibrationEffect.createWaveform(figura, fuerzas, -1))
            } else {
                /* Antes de Android 8 no se puede pedir intensidad, solo el ritmo. El
                 * ritmo es lo que distingue el aviso, asi que se conserva lo que importa. */
                @Suppress("DEPRECATION")
                vibrador?.vibrate(figura, -1)
            }
        }

        /* Dos tonos distintos y no uno mas fuerte: el volumen se pierde en un salon con
         * ruido, pero un tono mas agudo se distingue igual. */
        runCatching {
            tonos?.startTone(
                if (peligro) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_PROP_BEEP,
                if (peligro) 90 else 45
            )
        }
    }

    /** Corta cualquier aviso en curso. Se llama al soltar el mando. */
    fun silenciar() {
        riesgo = Riesgo.NINGUNO
        runCatching { tonos?.stopTone() }
    }

    fun liberar() {
        runCatching { tonos?.release() }
    }

    private companion object {
        /** Debajo de esto el aviso ya es continuo; el sensor tampoco mide mas cerca. */
        const val DISTANCIA_MINIMA = 10f

        /* Medidos en el piso: con 20 y 45 el aviso llegaba tarde para frenar a paso
         * normal. El carro necesita mas espacio del que sugiere mirarlo quieto. */
        const val DISTANCIA_PELIGRO = 30f
        const val DISTANCIA_AVISO = 55f

        const val PERIODO_MINIMO_MS = 110f
        const val PERIODO_MAXIMO_MS = 650f

        /* Espera, toque, silencio, toque. Dos golpes cortos no se confunden con el
         * unico que dan los sticks al agarrarlos. */
        val FIGURA_CERCA = longArrayOf(0, 18, 55, 18)
        val FUERZAS_CERCA = intArrayOf(0, 150, 0, 150)

        /* Un zumbido largo y sostenido. Nada mas en la interfaz vibra asi, y por eso se
         * reconoce sin haberlo aprendido. */
        val FIGURA_PELIGRO = longArrayOf(0, 140)
        val FUERZAS_PELIGRO = intArrayOf(0, 255)

        /* Ochenta sobre cien: se oye en un salon sin volverse molesto en la mano. */
        const val VOLUMEN = 80
    }
}

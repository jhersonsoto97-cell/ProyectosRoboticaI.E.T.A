package com.ieta.smartcar.alerta

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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

    /* El pitido se sintetiza en vez de usar los tonos del sistema o un archivo.
     *
     * Los tonos del sistema nacieron para marcar numeros de telefono y ninguno se parece
     * a un sensor de parqueo. Un archivo de audio daria el sonido justo, pero hay que
     * empaquetarlo, decodificarlo y mantenerlo. Generando la onda queda la frecuencia
     * exacta que se quiera, pesa unos kilobytes de memoria y no suma nada al APK.
     *
     * Los dos van por el canal de musica y no por el de notificaciones: ese ultimo queda
     * mudo con el telefono en vibrador, que es como lo lleva casi todo el mundo. */
    private val pitidoCerca = crearPitido(FRECUENCIA_HZ, DURACION_CERCA_MS)
    private val pitidoPeligro = crearPitido(FRECUENCIA_HZ, DURACION_PELIGRO_MS)

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

        /* Misma nota en los dos casos, como en un carro de verdad: lo que avisa es el
         * ritmo, no el tono. Cambiando tambien la nota, el aviso deja de leerse como una
         * misma cosa que se acelera y pasa a sonar como dos alarmas distintas. */
        sonar(if (peligro) pitidoPeligro else pitidoCerca)
    }

    /**
     * Reproduce un pitido ya sintetizado.
     *
     * Se corta el anterior antes de empezar. En peligro los avisos casi se tocan, y
     * dejando que se superpongan el sonido se vuelve un zumbido sucio en vez de una
     * seguidilla de pitidos.
     */
    private fun sonar(pitido: AudioTrack?) {
        val pista = pitido ?: return
        runCatching {
            pista.stop()
            pista.reloadStaticData()
            pista.play()
        }
    }

    /**
     * Arma un pitido de onda senoidal, listo para repetir sin volver a calcularlo.
     *
     * Los bordes entran y salen con una rampa. Cortar la onda de golpe produce un
     * chasquido audible que suena a error, y esa rampa de unos pocos milisegundos es la
     * diferencia entre un pitido limpio y uno que parece roto.
     */
    private fun crearPitido(frecuencia: Int, duracionMs: Int): AudioTrack? = runCatching {
        val muestras = MUESTREO_HZ * duracionMs / 1000
        val onda = ShortArray(muestras)

        val subida = MUESTREO_HZ * 3 / 1000     /* 3 ms */
        val bajada = MUESTREO_HZ * 8 / 1000     /* 8 ms */

        for (i in 0 until muestras) {
            val fase = 2.0 * Math.PI * frecuencia * i / MUESTREO_HZ
            val sobre = when {
                i < subida -> i.toFloat() / subida
                i > muestras - bajada -> (muestras - i).toFloat() / bajada
                else -> 1f
            }
            onda[i] = (kotlin.math.sin(fase) * sobre * Short.MAX_VALUE * 0.75).toInt().toShort()
        }

        val bytes = onda.size * 2
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(MUESTREO_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(onda, 0, onda.size) }
    }.getOrNull()

    /** Corta cualquier aviso en curso. Se llama al soltar el mando. */
    fun silenciar() {
        riesgo = Riesgo.NINGUNO
        runCatching { pitidoCerca?.stop() }
        runCatching { pitidoPeligro?.stop() }
    }

    fun liberar() {
        runCatching { pitidoCerca?.release() }
        runCatching { pitidoPeligro?.release() }
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

        /* 2.6 kHz: la nota que usan los sensores de parqueo. Cae donde el oido humano
         * es mas sensible y atraviesa el ruido de un salon sin necesidad de volumen. */
        const val FRECUENCIA_HZ = 2600

        /* Alcanza de sobra para 2.6 kHz y deja los buffers en unos pocos kilobytes. */
        const val MUESTREO_HZ = 22050

        /* Cada pitido dura menos que el hueco hasta el siguiente, incluso en la cadencia
         * mas rapida: asi la seguidilla se oye como pitidos sueltos y no como un zumbido
         * continuo, que es lo que distingue a un sensor de parqueo. */
        const val DURACION_CERCA_MS = 45
        const val DURACION_PELIGRO_MS = 70
    }
}

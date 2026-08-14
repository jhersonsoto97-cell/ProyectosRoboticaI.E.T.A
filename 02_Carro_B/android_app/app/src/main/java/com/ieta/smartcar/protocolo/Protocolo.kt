package com.ieta.smartcar.protocolo

/**
 * El idioma de cada carro, separado del medio por el que viaja.
 *
 * Hasta ahora el formato de las tramas vivia dentro del ViewModel, que armaba el
 * texto del Mega con sus propias manos. Con un solo carro alcanzaba; con dos que
 * hablan distinto, esa mezcla obliga a preguntar "que carro es" en cada punto
 * donde se manda o se recibe algo.
 *
 * Separandolos, el transporte y el protocolo se combinan libremente: el simulador
 * del PC puede hablar JSON por TCP, y un carro con Mega podria pasar a WiFi sin
 * tocar una linea de esta capa.
 */

/** Lo que la app le pide al carro, dicho en el dominio y no en el cable. */
sealed interface OrdenCarro {
    data class Conducir(val izquierda: Int, val derecha: Int) : OrdenCarro
    data class TrimReversa(val izquierda: Int, val derecha: Int) : OrdenCarro
    data object Detener : OrdenCarro
    data object Escanear : OrdenCarro

    /** Sostener el brazo del sonar en el centro para poder montarlo. */
    data class CentrarServo(val activo: Boolean) : OrdenCarro
    data object Autoprueba : OrdenCarro
}

/** Un eco del sonar, en el marco del carro: 0 grados es al frente. */
data class PuntoSonar(val angulo: Int, val distanciaCm: Float)

/**
 * Un eco ya en pantalla, con el instante en que llego.
 *
 * La edad decide cuanto se ve y cuando desaparece. Sin ella, un punto medido antes de
 * que el carro girara sigue dibujado como si el obstaculo siguiera ahi.
 */
data class EcoRadar(val distanciaCm: Float, val instante: Long)

/** Lo que el carro cuenta de vuelta, ya interpretado. */
sealed interface EventoCarro {
    /** Linea suelta sin estructura. Es todo lo que manda el Mega. */
    data class Texto(val linea: String) : EventoCarro

    data class Lectura(val punto: PuntoSonar) : EventoCarro
    data class Progreso(val porcentaje: Int) : EventoCarro
    data class Plano(val puntos: List<PuntoSonar>) : EventoCarro
}

interface Protocolo {
    /**
     * Trama lista para enviar, o null si este carro no entiende esa orden.
     *
     * Devolver null en vez de lanzar una excepcion es deliberado: que un carro no
     * sepa escanear no es un error, y quien llama no deberia tener que consultar
     * antes una tabla de capacidades para poder pedir algo.
     */
    fun codificar(orden: OrdenCarro): String?

    /** Traduce lo que llega. Lista vacia si esa entrada no dice nada util. */
    fun decodificar(entrante: String): List<EventoCarro>
}

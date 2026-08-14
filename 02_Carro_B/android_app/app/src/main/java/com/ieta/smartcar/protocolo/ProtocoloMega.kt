package com.ieta.smartcar.protocolo

/**
 * Carro B: Arduino Mega detras de un modulo serie Bluetooth.
 *
 * Texto plano y corto, porque del otro lado hay un parser escrito a mano sobre un
 * enlace de 9600 baudios. Los delimitadores distintos por tipo de trama, `<>` para
 * conducir y `{}` para calibrar, le permiten al firmware reconocer cada una sin
 * mantener estado entre bytes.
 */
object ProtocoloMega : Protocolo {

    override fun codificar(orden: OrdenCarro): String? = when (orden) {
        is OrdenCarro.Conducir -> "<${orden.izquierda},${orden.derecha}>\n"
        is OrdenCarro.TrimReversa -> "{${orden.izquierda},${orden.derecha}}\n"
        OrdenCarro.Detener -> "<0,0>\n"
        OrdenCarro.Autoprueba -> "T\n"

        /* Este carro no tiene sonar. */
        OrdenCarro.Escanear -> null
        is OrdenCarro.CentrarServo -> null
    }

    /* El Mega manda lineas de texto pensadas para que las lea una persona, no un
     * programa. Se pasan tal cual a la consola de la app. */
    override fun decodificar(entrante: String): List<EventoCarro> {
        val linea = entrante.trim()
        return if (linea.isEmpty()) emptyList() else listOf(EventoCarro.Texto(linea))
    }
}

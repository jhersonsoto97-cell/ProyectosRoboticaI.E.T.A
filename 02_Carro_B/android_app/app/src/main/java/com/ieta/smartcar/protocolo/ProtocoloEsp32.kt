package com.ieta.smartcar.protocolo

import org.json.JSONObject

/**
 * Explorador: ESP32 con punto de acceso propio y WebSocket.
 *
 * JSON en los dos sentidos. Cuesta mas bytes que el texto del Mega, pero aqui no
 * hay 9600 baudios de por medio y a cambio el carro puede mandar cosas que no son
 * un numero suelto: un barrido de sonar, el avance de un escaneo, un plano entero.
 *
 * Se usa org.json, que viene en Android. Traer una libreria de serializacion por
 * cuatro formas de trama sumaria peso al APK sin ahorrar codigo.
 */
object ProtocoloEsp32 : Protocolo {

    override fun codificar(orden: OrdenCarro): String? = when (orden) {
        is OrdenCarro.Conducir ->
            """{"t":"c","l":${orden.izquierda},"r":${orden.derecha}}"""
        OrdenCarro.Detener -> """{"t":"stop"}"""
        OrdenCarro.Escanear -> """{"t":"scan"}"""
        is OrdenCarro.CentrarServo ->
            """{"t":"centrar","v":${if (orden.activo) 1 else 0}}"""

        /* La compensacion de reversa la resuelve este carro con sus propios trims,
         * guardados en su memoria y ajustables desde su panel. */
        is OrdenCarro.TrimReversa -> null

        /* Las pruebas de hardware van por HTTP a /probar y no por el socket: son
         * bloqueantes de varios segundos y el firmware las corre en su lazo
         * principal, fuera del manejador del WebSocket. */
        OrdenCarro.Autoprueba -> null
    }

    override fun decodificar(entrante: String): List<EventoCarro> {
        val texto = entrante.trim()
        if (!texto.startsWith("{")) {
            return emptyList()
        }

        return try {
            val json = JSONObject(texto)
            when (json.optString("t")) {
                "s" -> listOf(
                    EventoCarro.Lectura(
                        PuntoSonar(
                            angulo = json.optInt("a"),
                            distanciaCm = json.optDouble("d", -1.0).toFloat()
                        )
                    )
                )

                "p" -> listOf(EventoCarro.Progreso(json.optInt("v")))

                "e" -> {
                    val arreglo = json.optJSONArray("pts")
                    val puntos = ArrayList<PuntoSonar>(arreglo?.length() ?: 0)
                    for (i in 0 until (arreglo?.length() ?: 0)) {
                        val p = arreglo!!.getJSONObject(i)
                        puntos += PuntoSonar(
                            angulo = p.optInt("a"),
                            distanciaCm = p.optDouble("d", -1.0).toFloat()
                        )
                    }
                    listOf(EventoCarro.Plano(puntos))
                }

                else -> emptyList()
            }
        } catch (malFormado: Exception) {
            /* Una trama cortada por el camino no es motivo para tumbar el enlace:
             * a 20 tramas por segundo, la siguiente llega en 50 ms. */
            emptyList()
        }
    }
}

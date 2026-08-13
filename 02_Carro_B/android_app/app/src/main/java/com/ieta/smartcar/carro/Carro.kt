package com.ieta.smartcar.carro

import com.ieta.smartcar.protocolo.Protocolo
import com.ieta.smartcar.protocolo.ProtocoloEsp32
import com.ieta.smartcar.protocolo.ProtocoloMega

/**
 * Los carros que la app sabe manejar.
 *
 * Cada uno declara lo que puede hacer en vez de que la interfaz pregunte "cual es
 * este". Sin eso, cada pantalla terminaria con su propia cadena de condicionales,
 * y sumar un tercer carro obligaria a revisarlas todas.
 */

enum class TipoCarro { CARRO_B, EXPLORADOR }

/**
 * Que sabe hacer un carro.
 *
 * La interfaz muestra u oculta segun esto, asi que agregar un carro nuevo es
 * declarar sus capacidades y no editar pantallas.
 */
data class Capacidades(
    /** Compensacion de reversa que se calibra desde la app y viaja al carro. */
    val trimsReversa: Boolean = false,
    /** Manda ecos de sonar mientras se maneja. */
    val radar: Boolean = false,
    /** Puede levantar un plano del entorno estando quieto. */
    val escaneo: Boolean = false,
    /** Sirve su propia pagina de calibracion y diagnostico. */
    val panelPropio: Boolean = false,
)

data class Carro(
    val tipo: TipoCarro,
    val nombre: String,
    val cerebro: String,
    val medio: String,
    val protocolo: Protocolo,
    val capacidades: Capacidades,
    /** Red del carro, cuando levanta la suya propia. */
    val red: String? = null,
    val clave: String? = null,
    val panelUrl: String? = null,
)

object Garaje {

    val CARRO_B = Carro(
        tipo = TipoCarro.CARRO_B,
        nombre = "Carro B",
        cerebro = "Arduino Mega",
        medio = "Bluetooth",
        protocolo = ProtocoloMega,
        capacidades = Capacidades(trimsReversa = true),
    )

    val EXPLORADOR = Carro(
        tipo = TipoCarro.EXPLORADOR,
        nombre = "Explorador",
        cerebro = "ESP32",
        medio = "WiFi propio",
        protocolo = ProtocoloEsp32,
        capacidades = Capacidades(radar = true, escaneo = true, panelPropio = true),
        red = "SmartCar-03",
        clave = "explorador",
        panelUrl = "http://192.168.4.1/diag",
    )

    val todos = listOf(CARRO_B, EXPLORADOR)

    fun porNombre(nombre: String?): Carro =
        todos.firstOrNull { it.nombre == nombre } ?: CARRO_B
}

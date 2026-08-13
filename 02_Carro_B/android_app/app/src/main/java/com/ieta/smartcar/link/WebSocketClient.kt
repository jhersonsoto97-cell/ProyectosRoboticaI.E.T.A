package com.ieta.smartcar.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import android.util.Base64

/**
 * Enlace por WebSocket contra el carro que levanta su propio punto de acceso.
 *
 * Escrito a mano en vez de traer una libreria. La razon de peso no es el peso: es que
 * el socket queda a la vista, y este enlace necesita atarlo a la red del carro antes de
 * conectar. Con una libreria por encima eso hay que pedirlo a traves de una fabrica de
 * sockets, que es el mismo trabajo con una capa mas de por medio y sin poder mirar
 * adentro cuando algo falla.
 *
 * De lo que define el protocolo se implementa lo que este carro usa: apreton de manos,
 * tramas de texto, ping y cierre. Nada de fragmentacion, compresion ni binario, que el
 * firmware tampoco manda.
 */
class WebSocketClient(
    scope: CoroutineScope,
    private val redWifi: RedWifi,
) : CarLink(scope) {

    private var socket: Socket? = null
    private var salida: OutputStream? = null
    private var entrada: DataInputStream? = null
    private var lector: Job? = null

    private var host: String = HOST_POR_DEFECTO
    private var puerto: Int = PUERTO_POR_DEFECTO
    private var ruta: String = RUTA_POR_DEFECTO

    private val azar = SecureRandom()

    fun connectTo(
        host: String = HOST_POR_DEFECTO,
        puerto: Int = PUERTO_POR_DEFECTO,
        ruta: String = RUTA_POR_DEFECTO,
    ) {
        this.host = host
        this.puerto = puerto
        this.ruta = ruta
        beginConnection()
    }

    override fun openTransport(): String {
        val abierto = Socket()

        // Antes de conectar, no despues: atar un socket ya conectado no reencamina el
        // trafico. Si la app no pidio la red, esto no hace nada y el socket sale por
        // donde el sistema decida, que cubre a quien se unio a mano desde los ajustes.
        redWifi.atar(abierto)

        // Sin Nagle: son tramas de veinte bytes veinte veces por segundo, y agruparlas
        // para ahorrar cabeceras le mete retraso al mando.
        abierto.tcpNoDelay = true
        abierto.connect(InetSocketAddress(host, puerto), TIEMPO_CONEXION_MS)
        abierto.soTimeout = TIEMPO_LECTURA_MS

        socket = abierto
        salida = abierto.getOutputStream()
        entrada = DataInputStream(abierto.getInputStream().buffered())

        apretonDeManos()

        lector?.cancel()
        lector = scope.launch(Dispatchers.IO) { bombearEntrada() }

        return "$host$ruta"
    }

    /**
     * Apreton de manos.
     *
     * No se valida la respuesta del servidor contra la clave enviada. Esa verificacion
     * protege de un intermediario que responda por otro, y aqui el otro extremo es un
     * carro en una red de un solo equipo levantada por el mismo carro.
     */
    private fun apretonDeManos() {
        val clave = ByteArray(16).also { azar.nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.NO_WRAP) }

        val pedido = buildString {
            append("GET $ruta HTTP/1.1\r\n")
            append("Host: $host:$puerto\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $clave\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }

        salida?.write(pedido.toByteArray(Charsets.US_ASCII))
        salida?.flush()

        val respuesta = leerCabeceras()
        if (!respuesta.contains("101")) {
            throw IOException("El carro no acepto el WebSocket: ${respuesta.lineSequence().firstOrNull()}")
        }
    }

    private fun leerCabeceras(): String {
        val flujo = entrada ?: throw IOException("Sin flujo de entrada")
        val acumulado = StringBuilder()

        while (!acumulado.endsWith("\r\n\r\n")) {
            val byte = flujo.read()
            if (byte < 0) throw IOException("El carro corto durante el apreton de manos")
            acumulado.append(byte.toChar())
            if (acumulado.length > 2048) throw IOException("Cabeceras demasiado largas")
        }
        return acumulado.toString()
    }

    /* ------------------------------------------------------------
     *   Entrada
     * ------------------------------------------------------------ */
    private suspend fun bombearEntrada() {
        try {
            while (scope.isActive) {
                val trama = leerTrama() ?: continue
                publishTelemetry(trama)
            }
        } catch (cancelado: CancellationException) {
            throw cancelado
        } catch (error: Exception) {
            onLinkLost(error)
        }
    }

    /** Devuelve el texto de la trama, o null si era de control y no trae nada que mostrar. */
    private fun leerTrama(): String? {
        val flujo = entrada ?: throw IOException("Sin flujo de entrada")

        val cabecera = flujo.read()
        if (cabecera < 0) throw IOException("El carro cerro el enlace")

        val codigo = cabecera and 0x0F
        val segundo = flujo.read()
        if (segundo < 0) throw IOException("El carro cerro el enlace")

        /* El servidor no enmascara; el cliente si. Si llegara enmascarado habria que
         * descartar la clave, pero este firmware nunca lo hace. */
        val enmascarado = (segundo and 0x80) != 0
        var largo = (segundo and 0x7F).toLong()

        if (largo == 126L) {
            largo = ((flujo.read().toLong() shl 8) or flujo.read().toLong())
        } else if (largo == 127L) {
            largo = 0
            repeat(8) { largo = (largo shl 8) or flujo.read().toLong() }
        }

        if (largo > LARGO_MAXIMO) {
            throw IOException("Trama de $largo bytes, mas de lo que este enlace admite")
        }

        val mascara = if (enmascarado) ByteArray(4).also { flujo.readFully(it) } else null
        val carga = ByteArray(largo.toInt()).also { flujo.readFully(it) }
        mascara?.let { for (i in carga.indices) carga[i] = (carga[i].toInt() xor it[i % 4].toInt()).toByte() }

        return when (codigo) {
            CODIGO_TEXTO -> String(carga, Charsets.UTF_8)

            /* Responder el ping es lo que mantiene vivo el enlace: sin pong, el otro
             * extremo da el enlace por muerto y lo cierra a los pocos segundos. */
            CODIGO_PING -> { enviarTrama(CODIGO_PONG, carga); null }

            CODIGO_CIERRE -> throw IOException("El carro cerro el enlace")
            else -> null
        }
    }

    /* ------------------------------------------------------------
     *   Salida
     * ------------------------------------------------------------ */
    override fun writeFrame(frame: String) {
        enviarTrama(CODIGO_TEXTO, frame.toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    private fun enviarTrama(codigo: Int, carga: ByteArray) {
        val flujo = salida ?: throw IOException("Enlace cerrado")
        val salidaTrama = ArrayList<Byte>(carga.size + 8)

        salidaTrama.add((0x80 or codigo).toByte())

        /* El bit alto del largo marca que la carga viaja enmascarada. Del cliente al
         * servidor eso es obligatorio, aun sobre una red privada. */
        if (carga.size < 126) {
            salidaTrama.add((0x80 or carga.size).toByte())
        } else {
            salidaTrama.add((0x80 or 126).toByte())
            salidaTrama.add(((carga.size shr 8) and 0xFF).toByte())
            salidaTrama.add((carga.size and 0xFF).toByte())
        }

        val mascara = ByteArray(4).also { azar.nextBytes(it) }
        mascara.forEach { salidaTrama.add(it) }
        for (i in carga.indices) {
            salidaTrama.add((carga[i].toInt() xor mascara[i % 4].toInt()).toByte())
        }

        flujo.write(salidaTrama.toByteArray())
        flujo.flush()
    }

    override fun closeTransport() {
        lector?.cancel()
        lector = null
        runCatching { socket?.close() }
        socket = null
        salida = null
        entrada = null
    }

    companion object {
        const val HOST_POR_DEFECTO = "192.168.4.1"
        const val PUERTO_POR_DEFECTO = 80
        const val RUTA_POR_DEFECTO = "/ws"

        private const val TIEMPO_CONEXION_MS = 5000

        /* Mas largo que cualquier silencio esperable del carro. El firmware manda
         * telemetria cada 40 ms, pero durante un escaneo se calla varios segundos. */
        private const val TIEMPO_LECTURA_MS = 20_000

        /* El plano completo de un escaneo ronda los 3 KB. 64 KB deja margen de sobra y
         * evita que una trama corrupta pida reservar memoria sin limite. */
        private const val LARGO_MAXIMO = 65_536L

        private const val CODIGO_TEXTO = 0x1
        private const val CODIGO_CIERRE = 0x8
        private const val CODIGO_PING = 0x9
        private const val CODIGO_PONG = 0xA
    }
}

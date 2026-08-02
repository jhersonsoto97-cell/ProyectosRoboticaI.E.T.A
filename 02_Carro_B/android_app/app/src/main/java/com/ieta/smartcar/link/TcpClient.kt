package com.ieta.smartcar.link

import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Enlace contra el simulador del PC.
 *
 * El emulador de Android no expone Bluetooth Classic, asi que no hay forma de probar
 * la app en el computador sobre SPP. Este transporte habla el mismo protocolo <L,R>
 * sobre TCP, de modo que el simulador ve exactamente las mismas tramas que veria el
 * Arduino y la logica de control se valida sin depender del hardware.
 */
class TcpClient(scope: CoroutineScope) : CarLink(scope) {

    private var host: String = DEFAULT_HOST
    private var port: Int = DEFAULT_PORT
    private var socket: Socket? = null

    fun connectTo(endpoint: String) {
        val parts = endpoint.trim().split(":")
        host = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST
        port = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT
        beginConnection()
    }

    override fun openEndpoint(): Endpoint {
        val opened = Socket()
        // TCP_NODELAY apaga el algoritmo de Nagle. Sin esto el sistema agruparia las
        // tramas pequenas para ahorrar cabeceras y el mando respondería con retraso.
        opened.tcpNoDelay = true
        opened.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket = opened

        if (!opened.isConnected) throw IOException("Sin respuesta de $host:$port")

        return Endpoint(
            name = "SIM $host:$port",
            input = opened.getInputStream(),
            output = opened.getOutputStream()
        )
    }

    override fun closeEndpoint() {
        socket?.close()
        socket = null
    }

    companion object {
        /** Alias fijo del emulador de Android hacia el equipo anfitrion. */
        const val DEFAULT_HOST = "10.0.2.2"
        const val DEFAULT_PORT = 8080
        const val DEFAULT_ENDPOINT = "$DEFAULT_HOST:$DEFAULT_PORT"
        private const val CONNECT_TIMEOUT_MS = 4000
    }
}

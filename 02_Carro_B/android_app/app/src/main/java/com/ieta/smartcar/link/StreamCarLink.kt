package com.ieta.smartcar.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

/**
 * Enlace sobre un par de flujos de bytes: RFCOMM del Bluetooth Classic y TCP del
 * simulador se comportan igual una vez abierto el socket.
 */
abstract class StreamCarLink(scope: CoroutineScope) : CarLink(scope) {

    /** Flujos ya abiertos mas el nombre legible del extremo remoto. */
    protected data class Endpoint(
        val name: String,
        val input: InputStream,
        val output: OutputStream
    )

    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null

    /** Abre el socket concreto. Bloquea; corre en IO y puede lanzar excepciones. */
    protected abstract fun openStreams(): Endpoint

    /** Cierra el socket concreto. */
    protected abstract fun closeSocket()

    final override fun openTransport(): String {
        val endpoint = openStreams()
        input = endpoint.input
        output = endpoint.output
        startReader()
        return endpoint.name
    }

    final override fun writeFrame(frame: String) {
        output?.write(frame.toByteArray(Charsets.US_ASCII))
        output?.flush()
    }

    final override fun closeTransport() {
        readerJob?.cancel()
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { closeSocket() }
        input = null
        output = null
    }

    private fun startReader() {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(256)
            val line = StringBuilder()
            try {
                while (isActive) {
                    val read = input?.read(buffer) ?: break
                    if (read <= 0) continue
                    for (index in 0 until read) {
                        val character = buffer[index].toInt().toChar()
                        if (character == '\n') {
                            publishTelemetry(line.toString().trim())
                            line.setLength(0)
                        } else if (character != '\r' && line.length < 120) {
                            line.append(character)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onLinkLost(error)
            }
        }
    }
}

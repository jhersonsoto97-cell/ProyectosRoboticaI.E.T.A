package com.ieta.smartcar.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

/** Estados posibles del enlace con el carro. */
enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Enlace de comandos hacia el carro.
 *
 * El medio fisico cambia (RFCOMM sobre Bluetooth con el HC-05 real, TCP contra el
 * simulador del PC) pero el trafico es identico: tramas de texto que salen y lineas de
 * telemetria que entran. Toda esa mecanica vive aqui una sola vez; cada subclase solo
 * aporta como se abre y se cierra su socket.
 */
abstract class CarLink(protected val scope: CoroutineScope) {

    /** Par de flujos ya abiertos mas el nombre legible del extremo remoto. */
    protected data class Endpoint(
        val name: String,
        val input: InputStream,
        val output: OutputStream
    )

    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _endpointName = MutableStateFlow<String?>(null)
    val endpointName: StateFlow<String?> = _endpointName.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Ultima linea recibida del carro; deja ver el FAILSAFE en vivo durante pruebas. */
    private val _telemetry = MutableStateFlow("")
    val telemetry: StateFlow<String> = _telemetry.asStateFlow()

    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var writerJob: Job? = null
    private var readerJob: Job? = null

    // CONFLATED a proposito: si el medio se atasca preferimos descartar tramas viejas y
    // mandar la posicion actual del joystick. En control en tiempo real un dato viejo es
    // peor que ningun dato, porque encolar produce un mando con retraso creciente.
    private var outbox = Channel<String>(Channel.CONFLATED)

    /** Abre el medio. Se invoca siempre en Dispatchers.IO y puede lanzar excepciones. */
    protected abstract fun openEndpoint(): Endpoint

    /** Libera el socket propio de cada implementacion. */
    protected abstract fun closeEndpoint()

    protected fun reportError(message: String) {
        _lastError.value = message
    }

    protected fun beginConnection() {
        if (_state.value == LinkState.CONNECTING || _state.value == LinkState.CONNECTED) return

        _state.value = LinkState.CONNECTING
        _lastError.value = null

        scope.launch(Dispatchers.IO) {
            try {
                val endpoint = openEndpoint()
                input = endpoint.input
                output = endpoint.output
                outbox = Channel(Channel.CONFLATED)

                _endpointName.value = endpoint.name
                _state.value = LinkState.CONNECTED

                startWriter()
                startReader()
            } catch (error: Exception) {
                teardown()
                _lastError.value = error.message ?: "No se pudo conectar"
                _state.value = LinkState.ERROR
            }
        }
    }

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            teardown()
            _state.value = LinkState.DISCONNECTED
            _endpointName.value = null
        }
    }

    /** Encola una trama. No bloquea: si hay una pendiente sin enviar, la reemplaza. */
    fun send(frame: String) {
        if (_state.value != LinkState.CONNECTED) return
        outbox.trySend(frame)
    }

    private fun startWriter() {
        writerJob?.cancel()
        writerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val frame = outbox.receive()
                    output?.write(frame.toByteArray(Charsets.US_ASCII))
                    output?.flush()
                }
            } catch (error: Exception) {
                onLinkLost(error)
            }
        }
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
                            _telemetry.value = line.toString().trim()
                            line.setLength(0)
                        } else if (character != '\r' && line.length < 120) {
                            line.append(character)
                        }
                    }
                }
            } catch (error: Exception) {
                onLinkLost(error)
            }
        }
    }

    private fun onLinkLost(error: Exception) {
        if (_state.value != LinkState.CONNECTED) return
        _lastError.value = error.message ?: "Enlace interrumpido"
        _state.value = LinkState.ERROR
        teardown()
    }

    private fun teardown() {
        writerJob?.cancel()
        readerJob?.cancel()
        outbox.close()
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { closeEndpoint() }
        input = null
        output = null
    }
}

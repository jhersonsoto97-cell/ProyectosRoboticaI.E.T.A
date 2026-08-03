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

/** Estados posibles del enlace con el carro. */
enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Enlace de comandos hacia el carro.
 *
 * El medio cambia por completo entre transportes: RFCOMM sobre Bluetooth Classic,
 * caracteristicas GATT sobre Bluetooth LE, o un socket TCP contra el simulador. Lo que
 * no cambia es el trafico: salen tramas de texto y entran lineas de telemetria.
 *
 * Aqui vive esa parte comun una sola vez, para que un transporte no pueda comportarse
 * distinto de otro y volver enganosas las pruebas hechas sobre uno solo.
 */
abstract class CarLink(protected val scope: CoroutineScope) {

    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _endpointName = MutableStateFlow<String?>(null)
    val endpointName: StateFlow<String?> = _endpointName.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Ultima linea recibida del carro; deja ver el FAILSAFE en vivo durante pruebas. */
    private val _telemetry = MutableStateFlow("")
    val telemetry: StateFlow<String> = _telemetry.asStateFlow()

    private var writerJob: Job? = null

    // CONFLATED a proposito: si el medio se atasca preferimos descartar tramas viejas y
    // mandar la posicion actual del joystick. En control en tiempo real un dato viejo es
    // peor que ningun dato, porque encolar produce un mando con retraso creciente.
    private var outbox = Channel<String>(Channel.CONFLATED)

    /** Abre el medio y devuelve el nombre legible del extremo. Bloquea; corre en IO. */
    protected abstract fun openTransport(): String

    /** Envia una trama. Bloquea hasta que el medio la acepte; corre en IO. */
    protected abstract fun writeFrame(frame: String)

    /** Libera el recurso propio de cada transporte. */
    protected abstract fun closeTransport()

    protected fun reportError(message: String) {
        _lastError.value = message
    }

    /** Las subclases publican aqui cada linea completa que llega del carro. */
    protected fun publishTelemetry(line: String) {
        _telemetry.value = line
    }

    protected fun beginConnection() {
        if (_state.value == LinkState.CONNECTING || _state.value == LinkState.CONNECTED) return

        _state.value = LinkState.CONNECTING
        _lastError.value = null

        scope.launch(Dispatchers.IO) {
            try {
                val name = openTransport()
                outbox = Channel(Channel.CONFLATED)
                _endpointName.value = name
                _state.value = LinkState.CONNECTED
                startWriter()
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

    protected fun onLinkLost(error: Exception) {
        if (_state.value != LinkState.CONNECTED) return
        _lastError.value = error.message ?: "Enlace interrumpido"
        _state.value = LinkState.ERROR
        teardown()
    }

    private fun startWriter() {
        writerJob?.cancel()
        writerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    writeFrame(outbox.receive())
                }
            } catch (error: Exception) {
                onLinkLost(error)
            }
        }
    }

    private fun teardown() {
        writerJob?.cancel()
        outbox.close()
        runCatching { closeTransport() }
    }
}

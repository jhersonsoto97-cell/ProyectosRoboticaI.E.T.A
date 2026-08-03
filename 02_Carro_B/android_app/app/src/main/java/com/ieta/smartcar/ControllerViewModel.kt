package com.ieta.smartcar

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ieta.smartcar.control.DriveMixer
import com.ieta.smartcar.control.DriveMode
import com.ieta.smartcar.control.WheelPower
import com.ieta.smartcar.link.BleClient
import com.ieta.smartcar.link.BtDevice
import com.ieta.smartcar.link.CarLink
import com.ieta.smartcar.link.DeviceScanner
import com.ieta.smartcar.link.LinkState
import com.ieta.smartcar.link.Radio
import com.ieta.smartcar.link.SppClient
import com.ieta.smartcar.link.TcpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Dueno del estado de control. Mantiene un lazo fijo de 20 Hz que transmite la posicion
 * de los sticks aunque no cambien: ese flujo constante es lo que alimenta el failsafe
 * del firmware, asi que cortarlo equivale a ordenar un frenado.
 */
class ControllerViewModel(application: Application) : AndroidViewModel(application) {

    private val adapter = (application.getSystemService(Context.BLUETOOTH_SERVICE)
            as BluetoothManager).adapter

    val scanner = DeviceScanner(application, adapter, viewModelScope)

    val spp = SppClient(adapter, viewModelScope)
    val ble = BleClient(application, adapter, viewModelScope)
    val tcp = TcpClient(viewModelScope)

    /** Enlace en uso. Los tres hablan el mismo protocolo, solo cambia el medio. */
    var link: CarLink by mutableStateOf(spp); private set

    private var connectJob: Job? = null

    var leftStickX by mutableStateOf(0f); private set
    var leftStickY by mutableStateOf(0f); private set
    var rightStickX by mutableStateOf(0f); private set
    var rightStickY by mutableStateOf(0f); private set

    var mode by mutableStateOf(DriveMode.ARCADE); private set
    var emergencyStop by mutableStateOf(false); private set
    var speedCapIndex by mutableStateOf(SPEED_CAPS.lastIndex); private set

    /** Potencia calculada en el ultimo tick; la interfaz la muestra como telemetria. */
    var wheelPower by mutableStateOf(WheelPower(0, 0)); private set

    val speedCap: Float get() = SPEED_CAPS[speedCapIndex]
    val speedCapLabel: String get() = "${(speedCap * 100).toInt()}%"

    init {
        viewModelScope.launch {
            while (isActive) {
                transmit()
                delay(FRAME_PERIOD_MS)
            }
        }
    }

    fun onLeftStick(x: Float, y: Float) {
        leftStickX = x
        leftStickY = y
    }

    fun onRightStick(x: Float, y: Float) {
        rightStickX = x
        rightStickY = y
    }

    fun toggleMode() {
        mode = if (mode == DriveMode.ARCADE) DriveMode.TANK else DriveMode.ARCADE
    }

    fun cycleSpeedCap() {
        speedCapIndex = (speedCapIndex + 1) % SPEED_CAPS.size
    }

    fun toggleEmergencyStop() {
        emergencyStop = !emergencyStop
    }

    /** Arma el paro sin poder desarmarlo por accidente; solo el boton PARO lo libera. */
    fun engageEmergencyStop() {
        emergencyStop = true
    }

    fun startScan() = scanner.start()

    fun stopScan() = scanner.stop()

    /**
     * Conecta eligiendo el transporte segun la radio que anuncia el modulo, y si falla
     * prueba el otro.
     *
     * El tipo declarado no siempre es confiable: un modulo nunca visto por el sistema
     * suele reportar UNKNOWN, y varios clones BLE se anuncian como duales. Probar la
     * alternativa cuesta unos segundos y evita que el usuario tenga que saber que
     * tecnologia usa su modulo para poder manejar el carro.
     */
    fun connectBluetooth(device: BtDevice) {
        scanner.stop()
        connectJob?.cancel()

        connectJob = viewModelScope.launch {
            val orden = when (device.radio) {
                Radio.LE -> listOf(ble, spp)
                Radio.CLASSIC -> listOf(spp)
                else -> listOf(spp, ble)
            }

            for ((indice, candidato) in orden.withIndex()) {
                switchTo(candidato)
                when (candidato) {
                    is BleClient -> candidato.connectTo(device.address)
                    is SppClient -> candidato.connectTo(device.address)
                    else -> continue
                }

                val resultado = withTimeoutOrNull(CONNECT_WINDOW_MS) {
                    candidato.state.first { it == LinkState.CONNECTED || it == LinkState.ERROR }
                }

                if (resultado == LinkState.CONNECTED) return@launch
                if (indice < orden.lastIndex) candidato.disconnect()
            }
        }
    }

    fun connectSimulator(endpoint: String) {
        connectJob?.cancel()
        switchTo(tcp)
        tcp.connectTo(endpoint)
    }

    fun disconnect() {
        connectJob?.cancel()
        link.disconnect()
    }

    private fun switchTo(target: CarLink) {
        if (link !== target) link.disconnect()
        link = target
    }

    private fun transmit() {
        val power = if (emergencyStop) {
            WheelPower(0, 0)
        } else {
            DriveMixer.mix(mode, leftStickY, leftStickX, rightStickY, rightStickX, speedCap)
        }
        wheelPower = power

        if (link.state.value == LinkState.CONNECTED) {
            link.send("<${power.left},${power.right}>\n")
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanner.stop()   // deja registrado el BroadcastReceiver si no se cancela
        connectJob?.cancel()
        link.disconnect()
    }

    private companion object {
        /** 20 Hz: holgado frente al failsafe de 400 ms y solo ~220 B/s sobre 9600 baudios. */
        const val FRAME_PERIOD_MS = 50L
        val SPEED_CAPS = floatArrayOf(0.4f, 0.7f, 1.0f)

        /** Margen antes de descartar un transporte y probar el otro. */
        const val CONNECT_WINDOW_MS = 25_000L
    }
}

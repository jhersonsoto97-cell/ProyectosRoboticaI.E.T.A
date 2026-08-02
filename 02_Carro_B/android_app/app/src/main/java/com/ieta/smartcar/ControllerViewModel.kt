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
import com.ieta.smartcar.link.CarLink
import com.ieta.smartcar.link.LinkState
import com.ieta.smartcar.link.PairedDevice
import com.ieta.smartcar.link.SppClient
import com.ieta.smartcar.link.TcpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dueno del estado de control. Mantiene un lazo fijo de 20 Hz que transmite la posicion
 * de los sticks aunque no cambien: ese flujo constante es lo que alimenta el failsafe
 * del firmware, asi que cortarlo equivale a ordenar un frenado.
 */
class ControllerViewModel(application: Application) : AndroidViewModel(application) {

    private val adapter = (application.getSystemService(Context.BLUETOOTH_SERVICE)
            as BluetoothManager).adapter

    val spp = SppClient(adapter, viewModelScope)
    val tcp = TcpClient(viewModelScope)

    /** Enlace en uso. Ambos hablan el mismo protocolo, solo cambia el medio fisico. */
    var link: CarLink by mutableStateOf(spp); private set

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

    fun pairedDevices(): List<PairedDevice> = spp.pairedDevices()

    fun connectBluetooth(address: String) {
        switchTo(spp)
        spp.connectTo(address)
    }

    fun connectSimulator(endpoint: String) {
        switchTo(tcp)
        tcp.connectTo(endpoint)
    }

    fun disconnect() = link.disconnect()

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
        link.disconnect()
    }

    private companion object {
        /** 20 Hz: holgado frente al failsafe de 400 ms y solo ~220 B/s sobre 9600 baudios. */
        const val FRAME_PERIOD_MS = 50L
        val SPEED_CAPS = floatArrayOf(0.4f, 0.7f, 1.0f)
    }
}

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
import com.ieta.smartcar.control.DriveTuning
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

    // Compensacion de reversa, ajustable en caliente. Vive en la app y no en el firmware
    // para poder calibrar manejando; se guarda en disco porque perderla al cerrar la app
    // obligaria a repetir toda la calibracion.
    private val prefs = application.getSharedPreferences("calibracion", Context.MODE_PRIVATE)

    var reverseTrimLeft by mutableStateOf(prefs.getInt(KEY_TRIM_LEFT, DEFAULT_TRIM))
        private set
    var reverseTrimRight by mutableStateOf(prefs.getInt(KEY_TRIM_RIGHT, DEFAULT_TRIM))
        private set

    private var lastTrimSent = 0L

    // Sensibilidad del mando, en porcentaje para poder mostrarla y ajustarla con enteros.
    // A diferencia del trim, no viaja al carro: se aplica aqui, sobre la posicion del
    // stick, antes de calcular la potencia.
    var throttleExpo by mutableStateOf(prefs.getInt(KEY_THROTTLE_EXPO, DEFAULT_THROTTLE_EXPO))
        private set
    var steerExpo by mutableStateOf(prefs.getInt(KEY_STEER_EXPO, DEFAULT_STEER_EXPO))
        private set
    var steerAuthority by mutableStateOf(prefs.getInt(KEY_STEER_AUTHORITY, DEFAULT_AUTHORITY))
        private set

    /** Cuanto mas debe recorrer el dedo respecto del circulo dibujado, en porcentaje. */
    var stickTravel by mutableStateOf(prefs.getInt(KEY_STICK_TRAVEL, DEFAULT_TRAVEL))
        private set

    val stickTravelScale: Float get() = stickTravel / 100f

    private val tuning: DriveTuning
        get() = DriveTuning(
            throttleExpo = throttleExpo / 100f,
            steerExpo = steerExpo / 100f,
            steerAuthority = steerAuthority / 100f
        )

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

    fun adjustReverseTrimLeft(delta: Int) {
        reverseTrimLeft = (reverseTrimLeft + delta).coerceIn(TRIM_MIN, TRIM_MAX)
        prefs.edit().putInt(KEY_TRIM_LEFT, reverseTrimLeft).apply()
        lastTrimSent = 0L   // fuerza el envio inmediato en vez de esperar al proximo tick
    }

    fun adjustReverseTrimRight(delta: Int) {
        reverseTrimRight = (reverseTrimRight + delta).coerceIn(TRIM_MIN, TRIM_MAX)
        prefs.edit().putInt(KEY_TRIM_RIGHT, reverseTrimRight).apply()
        lastTrimSent = 0L
    }

    fun adjustThrottleExpo(delta: Int) {
        throttleExpo = (throttleExpo + delta).coerceIn(0, 90)
        prefs.edit().putInt(KEY_THROTTLE_EXPO, throttleExpo).apply()
    }

    fun adjustSteerExpo(delta: Int) {
        steerExpo = (steerExpo + delta).coerceIn(0, 90)
        prefs.edit().putInt(KEY_STEER_EXPO, steerExpo).apply()
    }

    fun adjustStickTravel(delta: Int) {
        // El tope es 200 porque los sticks van pegados a las esquinas: mas recorrido que
        // ese no cabe entre el centro del stick y el borde de la pantalla, y el tope del
        // rango quedaria fuera del alcance del dedo.
        stickTravel = (stickTravel + delta).coerceIn(100, 200)
        prefs.edit().putInt(KEY_STICK_TRAVEL, stickTravel).apply()
    }

    fun adjustSteerAuthority(delta: Int) {
        // El minimo no es cero: sin autoridad la direccion deja de existir y el carro
        // solo puede ir en linea recta.
        steerAuthority = (steerAuthority + delta).coerceIn(20, 100)
        prefs.edit().putInt(KEY_STEER_AUTHORITY, steerAuthority).apply()
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
            DriveMixer.mix(
                mode, leftStickY, leftStickX, rightStickY, rightStickX, speedCap, tuning
            )
        }
        wheelPower = power

        if (link.state.value != LinkState.CONNECTED) return

        // La calibracion se reenvia periodicamente en lugar de una sola vez al cambiar.
        // Evita toda la logica de reintentos: si un paquete se pierde, o el carro se
        // reinicia a mitad de sesion, el siguiente restablece los valores solo.
        //
        // Ocupa su propio tick en vez de acompanar a la trama de conduccion. El canal de
        // escritura es CONFLATED, asi que dos envios seguidos harian que el segundo pise
        // al primero; y concatenarlos daria 21 bytes, uno mas de lo que entra en una
        // escritura BLE con el MTU minimo. Saltear una trama de conduccion no cuesta
        // nada: son 50 ms frente a los 400 ms del failsafe.
        val ahora = System.currentTimeMillis()
        if (ahora - lastTrimSent >= TRIM_PERIOD_MS) {
            lastTrimSent = ahora
            link.send("{$reverseTrimLeft,$reverseTrimRight}\n")
            return
        }

        link.send("<${power.left},${power.right}>\n")
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

        const val TRIM_PERIOD_MS = 1000L
        const val DEFAULT_TRIM = 100

        /** Debajo de 25 el techo cae al piso de torque y el acelerador deja de actuar. */
        const val TRIM_MIN = 25
        const val TRIM_MAX = 100

        const val KEY_TRIM_LEFT = "reverse_trim_left"
        const val KEY_TRIM_RIGHT = "reverse_trim_right"

        // Arrancan en valores suaves y no lineales: con respuesta directa el carro
        // resulta imposible de dosificar, que es la queja mas comun al probarlo.
        const val DEFAULT_THROTTLE_EXPO = 55
        const val DEFAULT_STEER_EXPO = 60
        const val DEFAULT_AUTHORITY = 65

        /** 150 %: con el circulo dibujado solo, un pulgar grueso barre todo el rango. */
        const val DEFAULT_TRAVEL = 150

        const val KEY_THROTTLE_EXPO = "throttle_expo"
        const val KEY_STEER_EXPO = "steer_expo"
        const val KEY_STEER_AUTHORITY = "steer_authority"
        const val KEY_STICK_TRAVEL = "stick_travel"

        /** Margen antes de descartar un transporte y probar el otro. */
        const val CONNECT_WINDOW_MS = 25_000L
    }
}

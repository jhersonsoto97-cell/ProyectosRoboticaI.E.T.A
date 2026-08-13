package com.ieta.smartcar

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ieta.smartcar.carro.Carro
import com.ieta.smartcar.carro.Garaje
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
import com.ieta.smartcar.link.RedWifi
import com.ieta.smartcar.link.SppClient
import com.ieta.smartcar.link.TcpClient
import com.ieta.smartcar.link.WebSocketClient
import com.ieta.smartcar.protocolo.EventoCarro
import com.ieta.smartcar.protocolo.OrdenCarro
import com.ieta.smartcar.protocolo.Protocolo
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

    val redWifi = RedWifi(application)
    val websocket = WebSocketClient(viewModelScope, redWifi)

    /** Medio por el que viajan las tramas. */
    var link: CarLink by mutableStateOf(spp); private set

    /** Carro elegido en el garaje. De el salen el idioma y lo que la interfaz muestra. */
    var carro: Carro by mutableStateOf(Garaje.CARRO_B); private set

    /**
     * Idioma del carro, derivado y no guardado aparte.
     *
     * Separado del transporte a proposito: el medio y el formato no tienen por que ir
     * de la mano. El simulador del PC habla el idioma del Mega sobre TCP, y el mismo
     * idioma viaja igual por BLE.
     */
    val protocolo: Protocolo get() = carro.protocolo

    /**
     * Cambia de carro y corta lo que hubiera abierto.
     *
     * Cortar es obligatorio: seguir conectado al carro anterior mientras la interfaz
     * dice otra cosa termina en tramas de un idioma mandadas a quien habla el otro.
     */
    fun elegirCarro(nuevo: Carro) {
        if (nuevo.nombre != carro.nombre) {
            connectJob?.cancel()
            link.disconnect()
        }
        carro = nuevo
        prefs.edit().putString(KEY_ULTIMO_CARRO, nuevo.nombre).apply()
    }

    val ultimoCarroUsado: Carro?
        get() = prefs.getString(KEY_ULTIMO_CARRO, null)?.let { Garaje.porNombre(it) }

    private var connectJob: Job? = null
    private var telemetriaJob: Job? = null

    /** Ultima distancia medida en cada direccion, en centimetros. */
    val ecos = mutableStateMapOf<Int, Float>()

    /** Hacia donde apunta el sonar ahora mismo, o null si no hay lecturas. */
    var anguloSonar by mutableStateOf<Int?>(null); private set

    /** Avance del escaneo en curso, de 0 a 100. En 100 el plano ya llego. */
    var progresoEscaneo by mutableStateOf(100); private set


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

    /**
     * Hasta donde llega el borde del radar, en centimetros.
     *
     * Se guarda en disco como el resto de la calibracion: quien maneja en un pasillo
     * angosto lo deja fino y no quiere volver a bajarlo en cada sesion.
     */
    var alcanceRadar by mutableStateOf(prefs.getInt(KEY_ALCANCE_RADAR, DEFAULT_ALCANCE))
        private set

    fun ajustarAlcanceRadar(cm: Int) {
        alcanceRadar = cm.coerceIn(30, 250)
        prefs.edit().putInt(KEY_ALCANCE_RADAR, alcanceRadar).apply()
    }

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

    /**
     * Se une a la red del carro. El resto lo dispara solo al quedar unido.
     *
     * No conecta aqui mismo porque el dialogo del sistema puede tardar lo que el usuario
     * tarde en aceptarlo, y hasta que no acepte no existe la red a la que atar el socket.
     */
    fun unirseAlExplorador() {
        val elegido = carro
        val red = elegido.red ?: return
        redWifi.unirse(red, elegido.clave.orEmpty())
    }

    /** Abre el WebSocket contra el carro. Requiere estar ya en su red. */
    fun conectarExplorador() {
        connectJob?.cancel()
        switchTo(websocket)
        websocket.connectTo()
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

        // Cada enlace trae su propio flujo de telemetria, asi que la escucha se rearma
        // al cambiar de transporte. Sin esto seguiriamos leyendo el flujo del anterior,
        // que ya no recibe nada.
        telemetriaJob?.cancel()
        ecos.clear()
        anguloSonar = null
        telemetriaJob = viewModelScope.launch {
            target.telemetry.collect { leerTelemetria(it) }
        }
    }

    /**
     * Traduce lo que llega y guarda lo que la interfaz necesita.
     *
     * Los ecos se guardan por angulo y no en una lista: el servo barre de ida y de
     * vuelta, asi que un mismo angulo se vuelve a medir cada pocos segundos y lo que
     * interesa es la ultima lectura de cada direccion, no el historial completo.
     */
    private fun leerTelemetria(entrante: String) {
        for (evento in protocolo.decodificar(entrante)) {
            when (evento) {
                is EventoCarro.Lectura -> {
                    anguloSonar = evento.punto.angulo
                    if (evento.punto.distanciaCm > 0f) {
                        ecos[evento.punto.angulo] = evento.punto.distanciaCm
                    } else {
                        // Sin eco tambien es informacion: ahi no hay nada, y dejar el
                        // punto viejo dibujado inventaria un obstaculo que ya no esta.
                        ecos.remove(evento.punto.angulo)
                    }
                }

                is EventoCarro.Plano -> {
                    ecos.clear()
                    for (punto in evento.puntos) {
                        if (punto.distanciaCm > 0f) ecos[punto.angulo] = punto.distanciaCm
                    }
                }

                is EventoCarro.Progreso -> progresoEscaneo = evento.porcentaje
                is EventoCarro.Texto -> Unit
            }
        }
    }

    /** Pide al carro que levante el plano del entorno. */
    fun escanear() {
        protocolo.codificar(OrdenCarro.Escanear)?.let { link.send(it) }
        progresoEscaneo = 0
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
            // Un carro que resuelve la compensacion por su cuenta devuelve null aqui, y
            // en ese caso el tick no se desperdicia: sigue de largo y manda conduccion.
            val calibracion = protocolo.codificar(
                OrdenCarro.TrimReversa(reverseTrimLeft, reverseTrimRight)
            )
            if (calibracion != null) {
                lastTrimSent = ahora
                link.send(calibracion)
                return
            }
        }

        protocolo.codificar(OrdenCarro.Conducir(power.left, power.right))?.let { link.send(it) }
    }

    override fun onCleared() {
        super.onCleared()
        scanner.stop()   // deja registrado el BroadcastReceiver si no se cancela
        redWifi.soltar() // si no, el telefono queda atado a una red sin internet
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
        const val KEY_ULTIMO_CARRO = "ultimo_carro"
        const val KEY_ALCANCE_RADAR = "alcance_radar"

        /** Un metro y medio: alcanza para ver una pared de frente sin
         *  aplastar contra el centro lo que hay cerca. */
        const val DEFAULT_ALCANCE = 150

        /** Margen antes de descartar un transporte y probar el otro. */
        const val CONNECT_WINDOW_MS = 25_000L
    }
}

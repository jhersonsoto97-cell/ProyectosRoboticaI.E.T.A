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
import com.ieta.smartcar.alerta.AlertaProximidad
import com.ieta.smartcar.alerta.Riesgo
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
import com.ieta.smartcar.protocolo.EcoRadar
import com.ieta.smartcar.protocolo.EventoCarro
import com.ieta.smartcar.protocolo.OrdenCarro
import com.ieta.smartcar.protocolo.Protocolo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

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

    /**
     * Ultima medida de cada direccion, con el instante en que llego.
     *
     * La marca de tiempo no es un detalle: un eco de hace cinco segundos puede haberse
     * medido antes de que el carro girara, y dibujarlo como si siguiera ahi es inventar
     * un obstaculo. Guardandolos sin fecha, la pantalla acumulaba puntos viejos que se
     * veian exactamente igual que lecturas falsas.
     */
    val ecos = mutableStateMapOf<Int, EcoRadar>()

    /** Hacia donde apunta el sonar ahora mismo, o null si no hay lecturas. */
    var anguloSonar by mutableStateOf<Int?>(null); private set

    /** Avance del escaneo en curso, de 0 a 100. En 100 el plano ya llego. */
    var progresoEscaneo by mutableStateOf(100); private set

    private val alerta = AlertaProximidad(application)

    /**
     * Distancia al obstaculo mas cercano dentro del cono de avance.
     *
     * Solo el frente: un eco a 90 grados esta al costado, y frenar por algo que el carro
     * va a pasar de largo entrena a ignorar el aviso.
     */
    val distanciaFrontal: Float?
        get() {
            // Solo lo medido hace poco. El radar conserva los ecos unos segundos para
            // dibujar un rastro, pero el aviso no puede usarlos: al alejarse de una
            // pared, el eco cercano de hace un rato seguia sonando como si el obstaculo
            // continuara ahi, y el aviso no se apagaba hasta que vencia solo.
            val ahora = System.currentTimeMillis()
            return ecos.entries
                .filter {
                    kotlin.math.abs(it.key) <= CONO_FRONTAL_GRADOS &&
                        ahora - it.value.instante <= FRESCURA_ALERTA_MS
                }
                .minOfOrNull { it.value.distanciaCm }
        }

    /**
     * Lo mismo, pero sin descartar por antiguedad.
     *
     * El escudo no puede usar la ventana corta del aviso. El servo tarda mas de un
     * segundo en volver a pasar por el mismo angulo, asi que mientras apunta al costado
     * un obstaculo angosto que sigue justo enfrente desaparece de esa ventana y el
     * escudo suelta. Es el hueco que deja cualquier ventana mas corta que el tiempo de
     * revisita, y con un freno ese hueco termina en golpe.
     *
     * Aqui se toma la ultima medida conocida de cada direccion, tenga la edad que tenga.
     * Cada angulo se corrige solo cuando el barrido vuelve a pasar por el: si el
     * obstaculo ya no esta, esa direccion se reemplaza por una lectura lejana o se borra,
     * y el escudo suelta con evidencia en vez de por olvido.
     *
     * Las dos reglas conviven a proposito. Al aviso le sale barato equivocarse callando;
     * al escudo, equivocarse soltando le cuesta un choque.
     */
    private val distanciaFrontalRetenida: Float?
        get() = ecos.entries
            .filter { kotlin.math.abs(it.key) <= CONO_FRONTAL_GRADOS }
            .minOfOrNull { it.value.distanciaCm }

    /** Que tan cerca esta lo de adelante. La interfaz lo muestra y la alerta lo suena. */
    val riesgoProximidad: Riesgo get() = alerta.riesgo

    /** True mientras el brazo del sonar se sostiene en el centro para poder montarlo. */
    var servoCentrado by mutableStateOf(false); private set

    fun alternarCentradoServo() {
        servoCentrado = !servoCentrado
        ultimoCentradoEnviado = 0L   // que salga en el proximo tick, no dentro de un segundo
    }


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
    private var ultimoCentradoEnviado = 0L

    /**
     * Compensacion de marcha recta, en puntos de recorte sobre la rueda que corre mas.
     *
     * Positivo corrige hacia la derecha, negativo hacia la izquierda, cero es sin
     * compensar. Un solo numero y no dos trims sueltos: con dos es facil bajar los dos a
     * la vez, que solo deja el carro mas lento y sigue torcido igual.
     *
     * No se guarda en disco a proposito. Depende de los motores del carro y no del
     * telefono, asi que el que manda es el carro: la app lo lee al conectarse. Si cada
     * tablet guardara el suyo, la primera en conectarse le impondria su calibracion a
     * un carro que quiza ya estaba bien ajustado.
     */
    var balanceRecto by mutableStateOf(0); private set

    /** Recorte de cada rueda que sale del balance. La que no se toca queda en 100. */
    val trimRectoIzquierda: Int get() = if (balanceRecto < 0) 100 + balanceRecto else 100
    val trimRectoDerecha: Int get() = if (balanceRecto > 0) 100 - balanceRecto else 100

    private var ultimoTrimRectoEnviado = 0L
    private var reenviarTrimRectoHasta = 0L

    /**
     * Otro mando esta manejando este carro y aqui solo se mira.
     *
     * Sin esto, el carro simplemente no responde y la app parece rota. Es la
     * situacion normal en un salon con una tablet por estudiante.
     */
    var mandoOcupado by mutableStateOf(false); private set
    private var ultimoAvisoOcupado = 0L

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
    var stickTravel by mutableStateOf(
        prefs.getInt(KEY_STICK_TRAVEL, DEFAULT_TRAVEL).coerceIn(TRAVEL_MIN, TRAVEL_MAX)
    )
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

    /**
     * Escudo: impide acelerar hacia adelante con algo pegado al frente.
     *
     * Arranca encendido y se recuerda. Es una proteccion, y una proteccion que hay que
     * acordarse de encender en cada sesion no protege de nada.
     */
    var escudoActivo by mutableStateOf(prefs.getBoolean(KEY_ESCUDO, true))
        private set

    /** True en el instante en que el escudo esta reteniendo el avance. */
    var escudoFrenando by mutableStateOf(false); private set

    fun alternarEscudo() {
        escudoActivo = !escudoActivo
        prefs.edit().putBoolean(KEY_ESCUDO, escudoActivo).apply()
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

    /** Positivo endereza a un carro que se va a la izquierda, y al reves. */
    fun ajustarBalanceRecto(delta: Int) {
        balanceRecto = (balanceRecto + delta).coerceIn(-BALANCE_MAX, BALANCE_MAX)

        // Se reenvia un rato en vez de una sola vez. El canal de escritura es CONFLATED,
        // asi que un envio suelto puede quedar pisado por la trama de conduccion
        // siguiente y perderse sin que nadie se entere. Y en vez de repetirlo para
        // siempre, se corta solo: repetirlo sin fin haria que dos tablets sobre el mismo
        // carro se pisaran la calibracion todo el tiempo.
        reenviarTrimRectoHasta = System.currentTimeMillis() + INSISTIR_TRIM_MS
        ultimoTrimRectoEnviado = 0L
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
        stickTravel = (stickTravel + delta).coerceIn(TRAVEL_MIN, TRAVEL_MAX)
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

        connectJob = viewModelScope.launch {
            val enlazado = withTimeoutOrNull(PLAZO_CALIBRACION_MS) {
                websocket.state.first { it == LinkState.CONNECTED }
            }
            if (enlazado != null) {
                leerCalibracionDelCarro()
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
                    val ahora = System.currentTimeMillis()
                    anguloSonar = evento.punto.angulo

                    if (evento.punto.distanciaCm > 0f) {
                        ecos[evento.punto.angulo] =
                            EcoRadar(evento.punto.distanciaCm, ahora)
                    } else {
                        // Sin eco tambien es informacion: ahi no hay nada, y dejar el
                        // punto viejo dibujado inventaria un obstaculo que ya no esta.
                        ecos.remove(evento.punto.angulo)
                    }

                    // Los que el barrido no volvio a confirmar se caen solos. Un angulo
                    // deja de refrescarse cuando el servo cambia de recorrido o cuando
                    // el carro giro, y en los dos casos lo que habia ahi ya no es cierto.
                    ecos.entries.removeAll { ahora - it.value.instante > VIDA_ECO_MS }
                }

                is EventoCarro.Plano -> {
                    // El plano llega completo y de una sola vez: reemplaza todo en vez de
                    // mezclarse con lecturas sueltas de antes del escaneo.
                    val ahora = System.currentTimeMillis()
                    ecos.clear()
                    for (punto in evento.puntos) {
                        if (punto.distanciaCm > 0f) {
                            ecos[punto.angulo] = EcoRadar(punto.distanciaCm, ahora)
                        }
                    }
                }

                is EventoCarro.Progreso -> progresoEscaneo = evento.porcentaje

                EventoCarro.MandoOcupado -> {
                    mandoOcupado = true
                    ultimoAvisoOcupado = System.currentTimeMillis()
                }

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
        var power = if (emergencyStop) {
            WheelPower(0, 0)
        } else {
            DriveMixer.mix(
                mode, leftStickY, leftStickX, rightStickY, rightStickX, speedCap, tuning
            )
        }
        power = aplicarEscudo(power)
        wheelPower = power

        if (link.state.value != LinkState.CONNECTED) {
            alerta.silenciar()
            mandoOcupado = false
            return
        }

        // El carro repite el aviso mientras dure el rechazo, asi que se da por
        // terminado cuando dejan de llegar. Se espera mas de un periodo completo:
        // con el plazo justo, un solo aviso perdido haria parpadear el cartel.
        if (mandoOcupado &&
            System.currentTimeMillis() - ultimoAvisoOcupado > VIGENCIA_OCUPADO_MS
        ) {
            mandoOcupado = false
        }

        // La alerta se evalua en este mismo tick y no en un temporizador aparte: un
        // segundo reloj se desincroniza del estado real y termina avisando de obstaculos
        // que ya se esquivaron.
        alerta.evaluar(
            distanciaCm = if (carro.capacidades.radar) distanciaFrontal else null,
            avanzando = (power.left + power.right) / 2 > 0
        )

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

        // El centrado se reenvia igual que la calibracion, en su propio tick. El canal de
        // escritura es CONFLATED, asi que una orden mandada una sola vez puede quedar
        // pisada por la trama de conduccion siguiente y perderse sin que nadie se entere.
        // Repitiendola, tambien se recupera sola si el carro se reinicia.
        if (ahora - ultimoCentradoEnviado >= CENTRADO_PERIOD_MS) {
            val centrado = protocolo.codificar(OrdenCarro.CentrarServo(servoCentrado))
            if (centrado != null) {
                ultimoCentradoEnviado = ahora
                link.send(centrado)
                return
            }
        }

        // La compensacion de marcha recta solo ocupa un tick mientras se la esta
        // corrigiendo. Fuera de esa ventana no se manda nada: la guarda el carro, y
        // repetirsela sin motivo seria pisar la de quien la haya ajustado despues.
        if (ahora < reenviarTrimRectoHasta &&
            ahora - ultimoTrimRectoEnviado >= TRIM_RECTO_PERIOD_MS
        ) {
            val recto = protocolo.codificar(
                OrdenCarro.TrimRecto(trimRectoIzquierda, trimRectoDerecha)
            )
            if (recto != null) {
                ultimoTrimRectoEnviado = ahora
                link.send(recto)
                return
            }
        }

        protocolo.codificar(OrdenCarro.Conducir(power.left, power.right))?.let { link.send(it) }
    }

    /**
     * Adopta la compensacion que el carro trae guardada.
     *
     * Se lee al conectarse y no se guarda nada en el telefono. Con varias tablets sobre
     * el mismo carro, esto es lo que hace que todas vean el mismo valor en vez de que
     * cada una arranque en cero y lo pise apenas alguien toque un boton.
     *
     * Falla en silencio: es un dato de conveniencia. Si el carro no contesta, el mando
     * funciona igual y el peor caso es que el numero en pantalla arranque en cero.
     */
    private suspend fun leerCalibracionDelCarro() {
        val url = carro.ajustesUrl ?: return
        val cuerpo = withContext(Dispatchers.IO) { redWifi.leerTexto(url) } ?: return

        val json = runCatching { JSONObject(cuerpo) }.getOrNull() ?: return
        if (!json.has("trim_izquierda") || !json.has("trim_derecha")) {
            return
        }

        balanceRecto = (json.optInt("trim_izquierda", 100) - json.optInt("trim_derecha", 100))
            .coerceIn(-BALANCE_MAX, BALANCE_MAX)
    }

    /**
     * Anula el avance cuando hay algo pegado al frente, dejando intacto el giro.
     *
     * Se descompone la orden en avance y giro para poder tocar solo el avance. Cortando
     * las dos ruedas a secas, el carro quedaria clavado contra la pared sin manera de
     * salir; asi puede girar sobre su eje y retroceder, que es como se sale de ahi.
     *
     * Sin lectura fresca no frena. Un sensor que se queda callado no es prueba de que
     * haya un obstaculo, y un carro que deja de responder por falta de datos es peor que
     * uno que golpea despacio.
     */
    private fun aplicarEscudo(pedido: WheelPower): WheelPower {
        val distancia = distanciaFrontalRetenida
        val bloquear = escudoActivo &&
            carro.capacidades.radar &&
            distancia != null &&
            distancia > 0f &&
            distancia <= DISTANCIA_ESCUDO_CM

        val avance = (pedido.left + pedido.right) / 2
        escudoFrenando = bloquear && avance > 0

        if (!escudoFrenando) {
            return pedido
        }

        val giro = (pedido.left - pedido.right) / 2
        return WheelPower(left = giro, right = -giro)
    }

    override fun onCleared() {
        super.onCleared()
        alerta.liberar()
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
        const val CENTRADO_PERIOD_MS = 1000L

        /**
         * Compensacion de marcha recta: cuanto se insiste y cada cuanto.
         *
         * Tres segundos a 300 ms son unos diez envios por cada toque, de sobra para que
         * uno llegue aunque el canal conflado se coma varios. Despues calla: el valor ya
         * quedo en la memoria del carro y seguir repitiendolo solo serviria para pisar
         * a quien lo ajuste desde otra tablet.
         */
        const val TRIM_RECTO_PERIOD_MS = 300L
        const val INSISTIR_TRIM_MS = 3000L

        /**
         * Tope del balance, en puntos de recorte.
         *
         * Cincuenta es mucho mas de lo que un desbalance normal necesita: entre motores
         * de la misma tanda no pasa de diez o quince. Que haga falta mas es senal de una
         * falla mecanica, y ahi compensar por software tapa el problema en vez de
         * resolverlo, asi que el tope tambien sirve de aviso.
         */
        const val BALANCE_MAX = 50

        /** Lo que se espera al enlace antes de rendirse a leer la calibracion del carro. */
        const val PLAZO_CALIBRACION_MS = 8000L

        /**
         * Cuanto vale un aviso de "otro tiene el mando" antes de darlo por vencido.
         *
         * El carro lo repite cada segundo. Con dos segundos y medio se tolera
         * perder uno sin que el cartel parpadee, y el turno se ve libre enseguida
         * cuando el otro suelta de verdad.
         */
        const val VIGENCIA_OCUPADO_MS = 2500L

        /** Medio cono de avance. Fuera de el, el carro pasa de largo. */
        const val CONO_FRONTAL_GRADOS = 45

        /**
         * Edad maxima de un eco para que la alarma lo tenga en cuenta.
         *
         * Mas corto que la vida del punto en pantalla: el radar dibuja un rastro, pero
         * la alarma tiene que hablar del presente. Un segundo y algo cubre el hueco que
         * deja el servo mientras barre los costados, sin arrastrar lo de hace rato.
         */
        const val FRESCURA_ALERTA_MS = 1200L

        const val KEY_ESCUDO = "escudo_activo"

        /**
         * Un palmo escaso. Mas lejos estorbaria al maniobrar en un pasillo.
         *
         * Diez y no seis: a seis el sensor ya trabaja al filo de lo que puede medir, y
         * una lectura que se pierde ahi suelta el escudo justo cuando mas hace falta.
         */
        const val DISTANCIA_ESCUDO_CM = 10f
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

        /**
         * Recorrido del dedo respecto del circulo dibujado.
         *
         * Por debajo de 100 el dedo llega al tope antes del borde, y por encima tiene que
         * pasarse. Un telefono pide de mas porque el circulo es chico y un pulgar grueso
         * lo barre entero; una tablet pide de menos, porque el circulo ya es grande y
         * estirar el pulgar hasta ahi cansa.
         *
         * El maximo baja de 200 a 150: mas alla de eso el borde util queda fuera del
         * alcance del pulgar en cualquiera de los dos, asi que era rango que no servia.
         */
        const val TRAVEL_MIN = 50
        const val TRAVEL_MAX = 150

        /** Punto neutro: el dedo llega al tope justo en el borde dibujado. */
        const val DEFAULT_TRAVEL = 100

        const val KEY_THROTTLE_EXPO = "throttle_expo"
        const val KEY_STEER_EXPO = "steer_expo"
        const val KEY_STEER_AUTHORITY = "steer_authority"
        const val KEY_STICK_TRAVEL = "stick_travel"
        const val KEY_ULTIMO_CARRO = "ultimo_carro"
        const val KEY_ALCANCE_RADAR = "alcance_radar"

        /** Cuanto vive un eco sin que el barrido lo confirme.
         *
         *  Tres segundos son dos pasadas completas del servo: si en dos
         *  vueltas nadie volvio a ver ese obstaculo, no estaba. */
        const val VIDA_ECO_MS = 3000L

        /** Un metro y medio: alcanza para ver una pared de frente sin
         *  aplastar contra el centro lo que hay cerca. */
        const val DEFAULT_ALCANCE = 150

        /** Margen antes de descartar un transporte y probar el otro. */
        const val CONNECT_WINDOW_MS = 25_000L
    }
}

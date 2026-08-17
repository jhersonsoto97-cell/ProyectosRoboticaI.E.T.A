package com.ieta.smartcar.link

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * Union a la red que levanta el carro.
 *
 * Un carro que es su propio punto de acceso no da internet, y Android lo nota: en
 * cuanto ve que esa red no llega a ninguna parte, deja los datos moviles como salida
 * por defecto y manda todo por ahi. La app pide 192.168.4.1, el pedido sale por la red
 * celular, y en la red celular esa direccion no existe.
 *
 * El resultado es el peor de todos: el telefono dice estar conectado al carro, la app
 * no logra hablarle, y el error que aparece no apunta a la causa. Parece una falla del
 * carro cuando el carro esta perfecto.
 *
 * Por eso no alcanza con unirse. Hay que quedarse con el objeto Network que devuelve el
 * sistema y **atar cada socket a el**, para que ese trafico salga por WiFi sin importar
 * lo que el sistema haya decidido para el resto.
 */
class RedWifi(private val context: Context) {

    enum class Estado {
        FUERA,          /* sin pedir nada */
        PIDIENDO,       /* esperando que el usuario acepte el dialogo del sistema */
        UNIDO,          /* la red esta disponible y atada */
        RECHAZADO,      /* el usuario dijo que no, o no aparecio la red */
        NO_SOPORTADO,   /* Android anterior al 10 */
    }

    private val _estado = MutableStateFlow(Estado.FUERA)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    private val _detalle = MutableStateFlow<String?>(null)
    val detalle: StateFlow<String?> = _detalle.asStateFlow()

    private val gestor =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** La red del carro, ya disponible. Es lo que hay que atar a cada socket. */
    var red: Network? = null
        private set

    /**
     * Nombre de la red a la que el telefono esta unido ahora mismo, sin comillas.
     *
     * Sirve para no molestar con el dialogo del sistema a quien ya se unio a mano desde
     * los ajustes, que es lo que la mayoria hace la primera vez.
     *
     * Devuelve null mas seguido de lo que parece. Desde Android 8.1 leer este nombre exige
     * permiso de ubicacion concedido y el GPS del sistema encendido, porque la red a la
     * que uno esta unido delata donde esta. Sin eso el sistema contesta "<unknown ssid>"
     * aunque la conexion este perfecta, asi que **no sirve para decidir nada**: solo para
     * confirmar en pantalla cuando se deja leer.
     */
    fun redActual(): String? {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null

        @Suppress("DEPRECATION")
        val nombre = wifi.connectionInfo?.ssid ?: return null
        return nombre.trim('"').takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    /**
     * Pide unirse a la red del carro.
     *
     * Android muestra su propio dialogo; la app no ve ni maneja la contrasena mas alla
     * de proponerla. Desde Android 10 no existe forma de unir el telefono a una red sin
     * que el usuario lo confirme, y esta bien que sea asi.
     */
    fun unirse(ssid: String, clave: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _estado.value = Estado.NO_SOPORTADO
            _detalle.value = "Uní el teléfono a la red $ssid desde los ajustes de WiFi."
            return
        }

        // Se comprueba antes de pedir. Sin el permiso, el sistema descarta el pedido sin
        // lanzar nada y sin responder nunca, asi que el boton parece no hacer nada.
        val permisoWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (ContextCompat.checkSelfPermission(context, permisoWifi) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _estado.value = Estado.RECHAZADO
            _detalle.value = "Falta el permiso de dispositivos cercanos. Dáselo a la app " +
                "desde los ajustes del teléfono, o unite a $ssid a mano desde el WiFi."
            return
        }

        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi?.isWifiEnabled == false) {
            _estado.value = Estado.RECHAZADO
            _detalle.value = "El WiFi del teléfono está apagado."
            return
        }

        soltar()

        val especificacion = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(clave)
            .build()

        val pedido = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Sin esta linea el sistema exige que la red tenga internet y descarta la del
            // carro antes de ofrecerla. Es la capacidad que hay que quitar, no agregar.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(especificacion)
            .build()

        val nuevo = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                red = network
                _estado.value = Estado.UNIDO
                _detalle.value = null
            }

            override fun onUnavailable() {
                red = null
                _estado.value = Estado.RECHAZADO
                _detalle.value = "No se pudo unir a $ssid. Puede que el carro esté " +
                    "apagado, o que se haya rechazado el aviso del sistema."
            }

            override fun onLost(network: Network) {
                red = null
                if (_estado.value == Estado.UNIDO) {
                    _estado.value = Estado.FUERA
                    _detalle.value = "Se perdió la red del carro."
                }
            }
        }

        callback = nuevo
        _estado.value = Estado.PIDIENDO
        _detalle.value = null

        // Con plazo y no sin el. Sin plazo, el sistema no garantiza llamar a
        // onUnavailable, asi que un pedido que nunca se cumple deja la pantalla
        // esperando para siempre sin decir por que.
        runCatching { gestor.requestNetwork(pedido, nuevo, PLAZO_MS) }
            .onFailure {
                _estado.value = Estado.RECHAZADO
                _detalle.value = it.message ?: "No se pudo pedir la red"
            }
    }

    /**
     * Ata un socket a la red del carro.
     *
     * Es la linea sin la cual todo lo demas no sirve.
     *
     * Si la app no pidio la red, se busca la WiFi que el sistema ya tenga levantada. Ese
     * camino es el unico posible en Android 9 y anteriores, donde no existe forma de
     * pedir una red desde la app, y es tambien el de quien se unio a mano desde los
     * ajustes, que es lo que la mayoria hace la primera vez.
     *
     * Sin esta busqueda, en esos telefonos el socket salia por donde el sistema quisiera
     * y el atado, que es la razon de ser de esta clase, no ocurria nunca.
     */
    fun atar(socket: Socket) {
        (red ?: redWifiDelSistema())?.let { runCatching { it.bindSocket(socket) } }
    }

    /**
     * Lee una direccion del carro por HTTP, saliendo por la WiFi.
     *
     * La conexion se abre desde el objeto Network por la misma razon que se atan los
     * sockets: pedida al sistema, saldria por datos moviles y no llegaria nunca.
     *
     * Devuelve null ante cualquier tropiezo en vez de propagar. Quien llama esta
     * consultando un dato de conveniencia mientras se conecta, y que la app se caiga
     * porque el carro tardo en contestar seria mucho peor que no tener el dato.
     */
    fun leerTexto(url: String, plazoMs: Int = 2000): String? = runCatching {
        val destino = (red ?: redWifiDelSistema()) ?: return null

        val conexion = destino.openConnection(URL(url)) as HttpURLConnection
        conexion.connectTimeout = plazoMs
        conexion.readTimeout = plazoMs
        try {
            if (conexion.responseCode != HttpURLConnection.HTTP_OK) return null
            conexion.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conexion.disconnect()
        }
    }.getOrNull()

    /**
     * Si el telefono tiene alguna WiFi levantada, sin importar cual.
     *
     * Saber el nombre de la red exige permisos; saber que hay WiFi no exige ninguno.
     * Cuando el nombre no se deja leer, esto es lo unico cierto que queda para decirle
     * algo honesto a quien mira la pantalla, en vez de afirmar que no hay red.
     */
    fun hayWifi(): Boolean = (red ?: redWifiDelSistema()) != null

    /**
     * La WiFi que el telefono ya tiene levantada, la haya pedido la app o no.
     *
     * Envuelto entero y no solo por prolijidad: preguntar por las redes del sistema lanza
     * SecurityException si falta el permiso, y esta consulta se hace tanto al pintar la
     * pantalla como al abrir el socket. Que una consulta informativa pueda tumbar la app
     * es peor que no saber la respuesta.
     */
    private fun redWifiDelSistema(): Network? = runCatching {
        @Suppress("DEPRECATION")
        val disponibles = gestor.allNetworks
        disponibles.firstOrNull { candidata ->
            gestor.getNetworkCapabilities(candidata)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }.getOrNull()

    /**
     * Si este telefono puede unirse a una red desde la app.
     *
     * Antes de Android 10 no existe la API, asi que la union es siempre a mano y no
     * tiene sentido ofrecer un boton que no puede funcionar.
     */
    val puedeUnirseSolo: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private companion object {
        /* Un minuto: el aviso del sistema aparece al instante, pero quien lo lee por
         * primera vez se toma su tiempo antes de aceptarlo. */
        const val PLAZO_MS = 60_000
    }

    /** Suelta la red para que el telefono vuelva a sus datos moviles. */
    fun soltar() {
        callback?.let { runCatching { gestor.unregisterNetworkCallback(it) } }
        callback = null
        red = null
        if (_estado.value != Estado.NO_SOPORTADO) {
            _estado.value = Estado.FUERA
        }
    }
}

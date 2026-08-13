package com.ieta.smartcar.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Socket

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
            _detalle.value = "Uni el telefono a la red $ssid desde los ajustes de WiFi."
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
                _detalle.value = "No apareció $ssid. Verificá que el carro esté encendido."
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

        runCatching { gestor.requestNetwork(pedido, nuevo) }
            .onFailure {
                _estado.value = Estado.RECHAZADO
                _detalle.value = it.message ?: "No se pudo pedir la red"
            }
    }

    /**
     * Ata un socket a la red del carro.
     *
     * Es la linea sin la cual todo lo demas no sirve. Si no hay red pedida por la app,
     * no hace nada y deja que el socket salga por donde el sistema decida: eso cubre el
     * caso de quien se unio a mano desde los ajustes.
     */
    fun atar(socket: Socket) {
        red?.bindSocket(socket)
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

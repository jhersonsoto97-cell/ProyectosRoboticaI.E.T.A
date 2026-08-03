package com.ieta.smartcar.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.UUID

/** Dispositivo Bluetooth visible, ya sea emparejado o recien descubierto. */
data class BtDevice(
    val name: String,
    val address: String,
    val bonded: Boolean,
    val rssi: Int? = null
)

/** Enlace real contra el HC-05 usando Bluetooth Classic, perfil SPP sobre RFCOMM. */
@SuppressLint("MissingPermission")
class SppClient(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    scope: CoroutineScope
) : CarLink(scope) {

    private var targetAddress: String? = null
    private var socket: BluetoothSocket? = null

    private val _devices = MutableStateFlow<List<BtDevice>>(emptyList())
    val devices: StateFlow<List<BtDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var receiverRegistered = false

    val isBluetoothReady: Boolean
        get() = adapter != null && adapter.isEnabled

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                    ) ?: return
                    val rssi = intent.getShortExtra(
                        BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE
                    ).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                    merge(toBtDevice(device, rssi))
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
            }
        }
    }

    /** Carga los emparejados del sistema. Aparecen al instante, sin esperar el escaneo. */
    fun loadPairedDevices() {
        try {
            adapter?.bondedDevices?.forEach { merge(toBtDevice(it, null)) }
        } catch (security: SecurityException) {
            reportError("Falta el permiso de Bluetooth")
        }
    }

    fun startScan() {
        val localAdapter = adapter
        if (localAdapter == null || !localAdapter.isEnabled) {
            reportError("Activa el Bluetooth del telefono")
            return
        }

        loadPairedDevices()

        try {
            registerReceiver()
            if (localAdapter.isDiscovering) localAdapter.cancelDiscovery()
            _scanning.value = localAdapter.startDiscovery()
            if (!_scanning.value) {
                reportError("No se pudo iniciar la busqueda. Revisa el permiso de escaneo.")
            }
        } catch (security: SecurityException) {
            _scanning.value = false
            reportError("Falta el permiso para buscar dispositivos cercanos")
        }
    }

    fun stopScan() {
        try {
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
        } catch (security: SecurityException) {
            // Sin permiso no hay descubrimiento activo que cancelar.
        }
        _scanning.value = false
        unregisterReceiver()
    }

    fun connectTo(address: String) {
        // El descubrimiento satura la radio y hace fallar el connect(). Se corta antes.
        stopScan()
        targetAddress = address
        beginConnection()
    }

    override fun openEndpoint(): Endpoint {
        val localAdapter = adapter
            ?: throw IOException("Bluetooth no disponible en este telefono")
        val address = targetAddress
            ?: throw IOException("No se eligio ningun dispositivo")

        // cancelDiscovery es asincrono. Sin esta pausa la radio sigue escaneando
        // durante el connect() y lo tumba.
        localAdapter.cancelDiscovery()
        Thread.sleep(DISCOVERY_SETTLE_MS)

        val device = localAdapter.getRemoteDevice(address)
        ensureBonded(device)

        val opened = openSocket(device)
        socket = opened

        return Endpoint(
            name = device.name ?: address,
            input = opened.inputStream,
            output = opened.outputStream
        )
    }

    override fun closeEndpoint() {
        socket?.close()
        socket = null
    }

    /**
     * Un dispositivo recien descubierto no esta emparejado todavia. Se lanza el
     * emparejado y se espera a que el usuario acepte el PIN en el dialogo del sistema,
     * porque intentar abrir el socket antes de tener el vinculo falla siempre.
     */
    private fun ensureBonded(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return

        if (device.bondState == BluetoothDevice.BOND_NONE && !device.createBond()) {
            throw IOException("No se pudo iniciar el emparejado")
        }

        // openEndpoint ya corre en Dispatchers.IO, que existe justamente para bloqueos
        // como este mientras el usuario responde el dialogo del sistema.
        val limite = System.currentTimeMillis() + BOND_TIMEOUT_MS
        while (System.currentTimeMillis() < limite) {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    // El vinculo recien creado no queda utilizable de inmediato: el stack
                    // sigue cerrando el intercambio de claves. Conectar aqui mismo es la
                    // causa habitual de "read failed, socket might closed or timeout".
                    Thread.sleep(BOND_SETTLE_MS)
                    return
                }
                BluetoothDevice.BOND_NONE -> throw IOException(
                    "Emparejado rechazado. El PIN del HC-05 suele ser 1234 o 0000."
                )
                else -> Thread.sleep(300)
            }
        }
        throw IOException("Se agoto el tiempo de emparejado")
    }

    /**
     * Abre el canal RFCOMM probando las cuatro variantes conocidas.
     *
     * No hay una sola que funcione en todos los HC-05: los originales responden al
     * registro SDP seguro, varios clones solo aceptan el canal inseguro, y los mas
     * baratos ni publican SDP y hay que atacar el canal 1 por reflexion. Ademas la
     * primera conexion falla seguido aunque el modulo este bien, asi que se reintenta.
     */
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        val variantes: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "SDP seguro" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "SDP inseguro" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "canal 1 seguro" to { reflectSocket(device, "createRfcommSocket") },
            "canal 1 inseguro" to { reflectSocket(device, "createInsecureRfcommSocket") }
        )

        var ultimoFallo: Exception? = null

        repeat(CONNECT_ATTEMPTS) { intento ->
            for ((_, abrir) in variantes) {
                val candidato = try {
                    abrir()
                } catch (noSoportado: Exception) {
                    ultimoFallo = noSoportado
                    continue
                }

                try {
                    candidato.connect()
                    return candidato
                } catch (fallo: Exception) {
                    ultimoFallo = fallo
                    // Cerrar antes de la siguiente variante: un socket a medio abrir deja
                    // el canal tomado y hace fracasar todo lo que venga despues.
                    runCatching { candidato.close() }
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
            if (intento < CONNECT_ATTEMPTS - 1) Thread.sleep(ATTEMPT_DELAY_MS)
        }

        throw IOException(explicar(ultimoFallo))
    }

    private fun reflectSocket(device: BluetoothDevice, metodo: String): BluetoothSocket =
        device.javaClass
            .getMethod(metodo, Int::class.javaPrimitiveType)
            .invoke(device, 1) as BluetoothSocket

    /** Traduce el mensaje crudo del stack a algo sobre lo que se pueda actuar. */
    private fun explicar(error: Exception?): String {
        val crudo = error?.message.orEmpty()
        return when {
            crudo.contains("read failed", ignoreCase = true) ||
                crudo.contains("timeout", ignoreCase = true) ->
                "El HC-05 no acepto la conexion. Revisa que el LED parpadee rapido " +
                    "(dos por segundo). Si parpadea lento esta en modo AT y no recibe " +
                    "datos. Verifica tambien que no siga conectado a otro telefono."

            crudo.contains("Service discovery failed", ignoreCase = true) ->
                "El modulo no respondio al descubrimiento de servicios. Apaga y prende " +
                    "el carro y vuelve a intentar."

            crudo.contains("Device or resource busy", ignoreCase = true) ->
                "El modulo esta ocupado con otra conexion. Reinicia el HC-05."

            crudo.isBlank() -> "No se pudo abrir el canal con el modulo"
            else -> crudo
        }
    }

    private fun toBtDevice(device: BluetoothDevice, rssi: Int?) = BtDevice(
        name = device.name?.takeIf { it.isNotBlank() } ?: "Sin nombre",
        address = device.address,
        bonded = device.bondState == BluetoothDevice.BOND_BONDED,
        rssi = rssi
    )

    /**
     * El descubrimiento reporta el mismo dispositivo varias veces. Se indexa por
     * direccion MAC, que es lo unico estable: el nombre puede llegar vacio en el primer
     * anuncio y completarse despues.
     */
    private fun merge(nuevo: BtDevice) {
        val actuales = _devices.value.associateBy { it.address }.toMutableMap()
        val previo = actuales[nuevo.address]

        actuales[nuevo.address] = nuevo.copy(
            name = if (nuevo.name == "Sin nombre" && previo != null) previo.name else nuevo.name,
            rssi = nuevo.rssi ?: previo?.rssi
        )

        // Emparejados primero, luego los mas cercanos segun potencia de senal.
        _devices.value = actuales.values.sortedWith(
            compareByDescending<BtDevice> { it.bonded }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.name }
        )
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filtro = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(
            context, discoveryReceiver, filtro, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(discoveryReceiver) }
        receiverRegistered = false
    }

    private companion object {
        /** UUID estandar del Serial Port Profile; el HC-05 no expone ningun otro. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Margen para que el usuario alcance a escribir el PIN en el dialogo del sistema. */
        const val BOND_TIMEOUT_MS = 30_000L

        /** Espera tras crear el vinculo, antes de que el canal sea utilizable. */
        const val BOND_SETTLE_MS = 1_500L

        /** cancelDiscovery no es inmediato; la radio tarda en soltar el escaneo. */
        const val DISCOVERY_SETTLE_MS = 400L

        const val CONNECT_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 250L
        const val ATTEMPT_DELAY_MS = 900L
    }
}

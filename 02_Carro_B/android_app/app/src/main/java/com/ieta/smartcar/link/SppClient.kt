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

        localAdapter.cancelDiscovery()

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
                BluetoothDevice.BOND_BONDED -> return
                BluetoothDevice.BOND_NONE -> throw IOException(
                    "Emparejado rechazado. El PIN del HC-05 suele ser 1234 o 0000."
                )
                else -> Thread.sleep(300)
            }
        }
        throw IOException("Se agoto el tiempo de emparejado")
    }

    /**
     * Algunos clones de HC-05 no publican el registro SDP del perfil SPP y rechazan la
     * conexion estandar. El fallback abre el canal RFCOMM 1 por reflexion, que es el que
     * usan esos modulos de fabrica.
     */
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        return try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).apply { connect() }
        } catch (standardFailed: IOException) {
            val fallback = device.javaClass
                .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
            fallback.apply { connect() }
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
    }
}

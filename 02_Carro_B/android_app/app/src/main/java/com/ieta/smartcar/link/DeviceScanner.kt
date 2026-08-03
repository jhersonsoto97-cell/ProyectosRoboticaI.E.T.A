package com.ieta.smartcar.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Tecnologia por la que se alcanza un modulo. Decide que transporte usar. */
enum class Radio { CLASSIC, LE, DUAL, UNKNOWN }

/** Dispositivo Bluetooth visible, ya sea emparejado o recien descubierto. */
data class BtDevice(
    val name: String,
    val address: String,
    val bonded: Boolean,
    val radio: Radio,
    val rssi: Int? = null
)

/**
 * Busca modulos por las dos tecnologias a la vez.
 *
 * Muchos modulos vendidos como "HC-05" son en realidad clones BLE (AT-09, HM-10, JDY):
 * conservan el nombre pero no hablan RFCOMM. Un escaneo que solo hiciera inquiry
 * clasico jamas los encontraria, y el usuario concluiria que su modulo esta danado.
 */
@SuppressLint("MissingPermission")
class DeviceScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val scope: CoroutineScope
) {

    private val _devices = MutableStateFlow<List<BtDevice>>(emptyList())
    val devices: StateFlow<List<BtDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var receiverRegistered = false
    private var leScanning = false
    private var stopJob: Job? = null

    val isBluetoothReady: Boolean
        get() = adapter != null && adapter.isEnabled

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                    ) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        .toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                    merge(device, rssi)
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> updateScanningFlag()
            }
        }
    }

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            merge(result.device, result.rssi)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { merge(it.device, it.rssi) }
        }

        override fun onScanFailed(errorCode: Int) {
            leScanning = false
            _lastError.value = "Fallo el escaneo BLE (codigo $errorCode)"
            updateScanningFlag()
        }
    }

    /** Carga los emparejados del sistema. Aparecen al instante, sin esperar el escaneo. */
    fun loadBonded() {
        try {
            adapter?.bondedDevices?.forEach { merge(it, null) }
        } catch (security: SecurityException) {
            _lastError.value = "Falta el permiso de Bluetooth"
        }
    }

    fun start() {
        val localAdapter = adapter
        if (localAdapter == null || !localAdapter.isEnabled) {
            _lastError.value = "Activa el Bluetooth del telefono"
            return
        }

        _lastError.value = null
        loadBonded()

        startClassic(localAdapter)
        startLe(localAdapter)
        updateScanningFlag()

        // El inquiry clasico se detiene solo a los ~12 s, pero el escaneo BLE seguiria
        // indefinidamente consumiendo bateria y estorbando al connect().
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(SCAN_WINDOW_MS)
            stop()
        }
    }

    fun stop() {
        stopJob?.cancel()
        stopJob = null

        try {
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
        } catch (security: SecurityException) {
            // Sin permiso no hay descubrimiento activo que cancelar.
        }

        if (leScanning) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(leCallback) }
            leScanning = false
        }

        unregisterReceiver()
        _scanning.value = false
    }

    private fun startClassic(localAdapter: BluetoothAdapter) {
        try {
            registerReceiver()
            if (localAdapter.isDiscovering) localAdapter.cancelDiscovery()
            if (!localAdapter.startDiscovery()) {
                _lastError.value = "No se pudo iniciar la busqueda clasica"
            }
        } catch (security: SecurityException) {
            _lastError.value = "Falta el permiso para buscar dispositivos cercanos"
        }
    }

    private fun startLe(localAdapter: BluetoothAdapter) {
        val scanner = localAdapter.bluetoothLeScanner ?: return
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, leCallback)
            leScanning = true
        } catch (security: SecurityException) {
            leScanning = false
            _lastError.value = "Falta el permiso para buscar dispositivos cercanos"
        }
    }

    private fun updateScanningFlag() {
        _scanning.value = leScanning || (adapter?.isDiscovering == true)
    }

    /**
     * El descubrimiento reporta el mismo dispositivo varias veces, y un modulo dual
     * aparece por ambas radios. Se indexa por direccion MAC, que es lo unico estable:
     * el nombre puede llegar vacio en el primer anuncio y completarse despues.
     */
    private fun merge(device: BluetoothDevice, rssi: Int?) {
        val nombre = try {
            device.name?.takeIf { it.isNotBlank() }
        } catch (security: SecurityException) {
            null
        }

        val entrada = BtDevice(
            name = nombre ?: "Sin nombre",
            address = device.address,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            radio = when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> Radio.CLASSIC
                BluetoothDevice.DEVICE_TYPE_LE -> Radio.LE
                BluetoothDevice.DEVICE_TYPE_DUAL -> Radio.DUAL
                else -> Radio.UNKNOWN
            },
            rssi = rssi
        )

        val actuales = _devices.value.associateBy { it.address }.toMutableMap()
        val previo = actuales[entrada.address]

        actuales[entrada.address] = entrada.copy(
            name = if (entrada.name == "Sin nombre" && previo != null) previo.name else entrada.name,
            radio = if (entrada.radio == Radio.UNKNOWN && previo != null) previo.radio else entrada.radio,
            rssi = rssi ?: previo?.rssi
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
            context, classicReceiver, filtro, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(classicReceiver) }
        receiverRegistered = false
    }

    private companion object {
        const val SCAN_WINDOW_MS = 15_000L
    }
}

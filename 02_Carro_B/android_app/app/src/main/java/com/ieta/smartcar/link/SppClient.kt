package com.ieta.smartcar.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.util.UUID

/** Dispositivo emparejado que el usuario puede elegir en el selector. */
data class PairedDevice(val name: String, val address: String)

/** Enlace real contra el HC-05 usando Bluetooth Classic, perfil SPP sobre RFCOMM. */
@SuppressLint("MissingPermission")
class SppClient(
    private val adapter: BluetoothAdapter?,
    scope: CoroutineScope
) : CarLink(scope) {

    private var targetAddress: String? = null
    private var socket: BluetoothSocket? = null

    val isBluetoothReady: Boolean
        get() = adapter != null && adapter.isEnabled

    fun pairedDevices(): List<PairedDevice> = try {
        adapter?.bondedDevices
            ?.map { PairedDevice(it.name ?: "Sin nombre", it.address) }
            ?.sortedBy { it.name }
            ?: emptyList()
    } catch (security: SecurityException) {
        reportError("Falta el permiso de Bluetooth")
        emptyList()
    }

    fun connectTo(address: String) {
        targetAddress = address
        beginConnection()
    }

    override fun openEndpoint(): Endpoint {
        val localAdapter = adapter
            ?: throw IOException("Bluetooth no disponible en este telefono")
        val address = targetAddress
            ?: throw IOException("No se eligio ningun dispositivo")

        localAdapter.cancelDiscovery()  // El escaneo activo hace fallar el connect().

        val device = localAdapter.getRemoteDevice(address)
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

    private companion object {
        /** UUID estandar del Serial Port Profile; el HC-05 no expone ningun otro. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

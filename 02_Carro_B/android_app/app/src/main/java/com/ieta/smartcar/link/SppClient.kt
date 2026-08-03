package com.ieta.smartcar.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.util.UUID

/** Enlace contra modulos Bluetooth Classic: HC-05 y HC-06 genuinos, perfil SPP. */
@SuppressLint("MissingPermission")
class SppClient(
    private val adapter: BluetoothAdapter?,
    scope: CoroutineScope
) : StreamCarLink(scope) {

    private var targetAddress: String? = null
    private var socket: BluetoothSocket? = null

    fun connectTo(address: String) {
        targetAddress = address
        beginConnection()
    }

    override fun openStreams(): Endpoint {
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

    override fun closeSocket() {
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

        // openStreams ya corre en Dispatchers.IO, que existe justamente para bloqueos
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
     * No hay una sola que funcione en todos los modulos: los HC-05 originales responden
     * al registro SDP seguro, varios clones solo aceptan el canal inseguro, y los mas
     * baratos ni publican SDP y hay que atacar el canal 1 por reflexion. Ademas la
     * primera conexion falla seguido aunque el modulo este bien, asi que se reintenta.
     */
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        val variantes: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            { reflectSocket(device, "createRfcommSocket") },
            { reflectSocket(device, "createInsecureRfcommSocket") }
        )

        var ultimoFallo: Exception? = null

        repeat(CONNECT_ATTEMPTS) { intento ->
            for (abrir in variantes) {
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
                "El modulo no acepto la conexion clasica. Si el telefono lo lista como " +
                    "Bluetooth LE, no es un HC-05 real sino un clon BLE y hay que " +
                    "conectarlo por BLE."

            crudo.contains("Service discovery failed", ignoreCase = true) ->
                "El modulo no respondio al descubrimiento de servicios. Apaga y prende " +
                    "el carro y vuelve a intentar."

            crudo.contains("Device or resource busy", ignoreCase = true) ->
                "El modulo esta ocupado con otra conexion. Reinicia el modulo."

            crudo.isBlank() -> "No se pudo abrir el canal con el modulo"
            else -> crudo
        }
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

package com.ieta.smartcar.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Enlace sobre Bluetooth LE con modulos de UART transparente.
 *
 * Buena parte de los modulos que se venden rotulados "HC-05" son en realidad clones BLE
 * (AT-09, HM-10, JDY-08). Conservan el nombre pero no exponen RFCOMM, asi que el
 * transporte clasico nunca puede abrirlos. Estos modulos publican en cambio una
 * caracteristica GATT que se comporta como un puerto serie: lo que se escribe sale por
 * el TX del modulo y lo que entra por su RX llega como notificacion.
 */
@SuppressLint("MissingPermission")
class BleClient(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    scope: CoroutineScope
) : CarLink(scope) {

    private var targetAddress: String? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private var connectLatch: CountDownLatch? = null
    private var servicesLatch: CountDownLatch? = null
    private var writeLatch: CountDownLatch? = null
    private var connectFailure: String? = null

    private val inbound = StringBuilder()

    fun connectTo(address: String) {
        targetAddress = address
        beginConnection()
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectLatch?.countDown()
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        connectFailure = "El modulo corto la conexion (estado $status)"
                    }
                    connectLatch?.countDown()
                    servicesLatch?.countDown()
                    writeLatch?.countDown()
                    onLinkLost(IOException(connectFailure ?: "Enlace BLE interrumpido"))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectFailure = "No se pudieron leer los servicios del modulo"
            }
            servicesLatch?.countDown()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeLatch?.countDown()
        }

        @Deprecated("Ruta para Android 12 y anteriores")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let { acumular(it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            acumular(value)
        }
    }

    override fun openTransport(): String {
        val localAdapter = adapter
            ?: throw IOException("Bluetooth no disponible en este telefono")
        val address = targetAddress
            ?: throw IOException("No se eligio ningun dispositivo")

        // Un escaneo activo compite por la radio y hace fracasar el connectGatt.
        runCatching { localAdapter.cancelDiscovery() }

        connectFailure = null
        inbound.setLength(0)

        val device = localAdapter.getRemoteDevice(address)
        connectLatch = CountDownLatch(1)
        servicesLatch = CountDownLatch(1)

        val nuevo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        } ?: throw IOException("No se pudo abrir el canal GATT")
        gatt = nuevo

        if (connectLatch?.await(CONNECT_TIMEOUT_S, TimeUnit.SECONDS) != true) {
            throw IOException("El modulo no respondio. Verifica que este encendido y cerca.")
        }
        connectFailure?.let { throw IOException(it) }

        if (servicesLatch?.await(SERVICES_TIMEOUT_S, TimeUnit.SECONDS) != true) {
            throw IOException("El modulo no publico sus servicios a tiempo")
        }
        connectFailure?.let { throw IOException(it) }

        val (canalEscritura, canalNotificacion) = seleccionarCanales(nuevo)
            ?: throw IOException(
                "Este dispositivo BLE no expone un puerto serie. No parece ser el " +
                    "modulo del carro."
            )

        writeChar = canalEscritura
        habilitarNotificaciones(nuevo, canalNotificacion)

        // Sin prioridad alta el intervalo de conexion ronda los 50 ms y las tramas del
        // joystick se acumulan; con ella baja a unos 15 ms y el mando responde parejo.
        runCatching { nuevo.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        runCatching { nuevo.requestMtu(MTU) }

        return device.name ?: address
    }

    override fun writeFrame(frame: String) {
        val canal = writeChar ?: throw IOException("Canal de escritura no disponible")
        val activo = gatt ?: throw IOException("Enlace BLE cerrado")
        val datos = frame.toByteArray(Charsets.US_ASCII)

        val sinRespuesta = canal.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val tipo = if (sinRespuesta) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        writeLatch = CountDownLatch(1)

        val encolada = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activo.writeCharacteristic(canal, datos, tipo) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                canal.writeType = tipo
                canal.value = datos
                activo.writeCharacteristic(canal)
            }
        }

        // GATT admite una sola operacion en vuelo. Esperar el acuse es lo que impide
        // pisar la escritura anterior; si no llega a tiempo se descarta la trama y la
        // siguiente del lazo de 20 Hz ya trae la posicion actualizada del stick.
        if (encolada) {
            writeLatch?.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun closeTransport() {
        writeChar = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private fun acumular(datos: ByteArray) {
        for (byte in datos) {
            val caracter = byte.toInt().toChar()
            if (caracter == '\n') {
                publishTelemetry(inbound.toString().trim())
                inbound.setLength(0)
            } else if (caracter != '\r' && inbound.length < 120) {
                inbound.append(caracter)
            }
        }
    }

    /**
     * Devuelve el par (escritura, notificacion). Se prueban primero los UUID conocidos
     * de los modulos comunes y, si ninguno aparece, se toma la primera caracteristica
     * que sirva. Los clones baratos usan UUID propios sin documentar, asi que buscar
     * solo por lista fija dejaria fuera a varios modulos que si funcionan.
     */
    private fun seleccionarCanales(
        activo: BluetoothGatt
    ): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for ((servicio, escritura, notificacion) in PERFILES_CONOCIDOS) {
            val gattService = activo.getService(servicio) ?: continue
            val canalEscritura = gattService.getCharacteristic(escritura) ?: continue
            val canalNotificacion = gattService.getCharacteristic(notificacion) ?: continue
            return canalEscritura to canalNotificacion
        }

        var escritura: BluetoothGattCharacteristic? = null
        var notificacion: BluetoothGattCharacteristic? = null

        for (servicio in activo.services) {
            for (canal in servicio.characteristics) {
                val propiedades = canal.properties
                val puedeEscribir = propiedades and (
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                    ) != 0
                val puedeNotificar = propiedades and (
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE
                    ) != 0

                if (puedeEscribir && escritura == null) escritura = canal
                if (puedeNotificar && notificacion == null) notificacion = canal
            }
        }

        val canalEscritura = escritura ?: return null
        return canalEscritura to (notificacion ?: canalEscritura)
    }

    private fun habilitarNotificaciones(
        activo: BluetoothGatt,
        canal: BluetoothGattCharacteristic
    ) {
        activo.setCharacteristicNotification(canal, true)

        // setCharacteristicNotification solo avisa al sistema. Para que el modulo empiece
        // a emitir hay que escribirle su descriptor de configuracion.
        val descriptor = canal.getDescriptor(CCCD) ?: return
        val valor = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activo.writeDescriptor(descriptor, valor)
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = valor
                activo.writeDescriptor(descriptor)
            }
        }
    }

    private companion object {
        /** Descriptor estandar Client Characteristic Configuration. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun uuid(corto: String): UUID =
            UUID.fromString("0000$corto-0000-1000-8000-00805f9b34fb")

        /** (servicio, canal de escritura, canal de notificacion). */
        val PERFILES_CONOCIDOS = listOf(
            // HM-10, AT-09, JDY-08 y la mayoria de clones CC2541: un solo canal para todo.
            Triple(uuid("ffe0"), uuid("ffe1"), uuid("ffe1")),
            // Variante con canales separados presente en algunos JDY.
            Triple(uuid("ffe0"), uuid("ffe2"), uuid("ffe1")),
            // Nordic UART Service, usado por modulos basados en nRF.
            Triple(
                UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
            ),
            // Perfil de varios modulos chinos con transporte sobre FFF0.
            Triple(uuid("fff0"), uuid("fff2"), uuid("fff1"))
        )

        const val CONNECT_TIMEOUT_S = 12L
        const val SERVICES_TIMEOUT_S = 10L
        const val WRITE_TIMEOUT_MS = 300L

        /** 23 bytes menos 3 de cabecera dejan 20 utiles; con 64 sobra para las tramas. */
        const val MTU = 64
    }
}

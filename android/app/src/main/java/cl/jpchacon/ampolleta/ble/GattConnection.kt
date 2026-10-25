package cl.jpchacon.ampolleta.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import cl.jpchacon.ampolleta.tuya.TuyaUuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

class GattException(message: String) : Exception(message)

/**
 * Conexión GATT a una ampolleta Tuya, con las operaciones convertidas en funciones suspend.
 * Android permite una sola operación GATT a la vez, así que todas pasan por [opLock].
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION") // APIs viejas solo se usan en Android 12 o anterior
class GattConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onNotification: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val opLock = Mutex()

    @Volatile private var connected: CompletableDeferred<Unit>? = null
    @Volatile private var servicesDiscovered: CompletableDeferred<Unit>? = null
    @Volatile private var descriptorWritten: CompletableDeferred<Int>? = null
    @Volatile private var charWritten: CompletableDeferred<Int>? = null
    @Volatile private var ready = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val err = GattException("desconectado (estado $status)")
                connected?.completeExceptionally(err)
                servicesDiscovered?.completeExceptionally(err)
                descriptorWritten?.completeExceptionally(err)
                charWritten?.completeExceptionally(err)
                val wasReady = ready
                ready = false
                g.close()
                if (gatt === g) gatt = null
                if (wasReady) onDisconnected()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered?.complete(Unit)
            else servicesDiscovered?.completeExceptionally(GattException("no se pudieron leer los servicios ($status)"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorWritten?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            charWritten?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotification(value)
        }

        @Deprecated("Solo para Android 12 o anterior")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    onNotification(c.value ?: return)
            }
        }
    }

    /** Conecta, descubre servicios y activa notificaciones. Reintenta los errores típicos de Android (133). */
    suspend fun connect(attempts: Int = 4) {
        var last: Exception? = null
        for (i in 1..attempts) {
            try {
                connectOnce()
                ready = true
                return
            } catch (e: Exception) {
                last = e
                close()
                delay(500L * i)
            }
        }
        throw last ?: GattException("no se pudo conectar")
    }

    private suspend fun connectOnce() {
        val c = CompletableDeferred<Unit>().also { connected = it }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw GattException("connectGatt devolvió null")
        withTimeout(15_000) { c.await() }

        val g = gatt ?: throw GattException("desconectado")
        val s = CompletableDeferred<Unit>().also { servicesDiscovered = it }
        if (!g.discoverServices()) throw GattException("discoverServices falló")
        withTimeout(15_000) { s.await() }

        val service = g.getService(UUID.fromString(TuyaUuids.SERVICE)) ?: throw GattException("la ampolleta no tiene el servicio Tuya")
        val notify = service.getCharacteristic(UUID.fromString(TuyaUuids.CHAR_NOTIFY)) ?: throw GattException("falta la característica de notificación")
        writeChar = service.getCharacteristic(UUID.fromString(TuyaUuids.CHAR_WRITE)) ?: throw GattException("falta la característica de escritura")

        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(UUID.fromString(TuyaUuids.CCCD)) ?: throw GattException("falta el descriptor de notificaciones")
        val d = CompletableDeferred<Int>().also { descriptorWritten = it }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            cccd.value = value
            g.writeDescriptor(cccd)
        }
        if (!started) throw GattException("no se pudieron activar las notificaciones")
        val status = withTimeout(10_000) { d.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("activar notificaciones falló ($status)")
    }

    suspend fun write(packet: ByteArray) = opLock.withLock {
        val g = gatt ?: throw GattException("desconectado")
        val c = writeChar ?: throw GattException("desconectado")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val w = CompletableDeferred<Int>().also { charWritten = it }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, packet, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = type
            c.value = packet
            g.writeCharacteristic(c)
        }
        if (!started) throw GattException("no se pudo escribir")
        val status = withTimeout(5_000) { w.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("escritura falló ($status)")
    }

    fun close() {
        ready = false
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }
}

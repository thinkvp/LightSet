package app.lightset.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Coroutine wrapper over [BluetoothGatt]. Android allows one outstanding GATT
 * operation at a time, so operations are serialised and each awaits its callback.
 */
@SuppressLint("MissingPermission")
class GattClient private constructor(
    private val onNotify: (UUID, ByteArray) -> Unit,
    private val onDisconnect: () -> Unit,
) {
    private lateinit var gatt: BluetoothGatt
    private val ops = Mutex()
    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var lastRead: ByteArray = ByteArray(0)

    /** Negotiated ATT MTU; a write can carry mtu − 3 bytes. */
    @Volatile var mtu: Int = 23
        private set
    private val connected = CompletableDeferred<Unit>()

    /** False once the link drops; every later operation fails fast. */
    @Volatile var isConnected = false
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                isConnected = true
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                val e = IllegalStateException("Disconnected (status $status)")
                connected.completeExceptionally(e)
                pending?.completeExceptionally(e)
                onDisconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            lastRead = value
            pending?.complete(status)
        }

        @Deprecated("Called instead of the ByteArray overload before API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                lastRead = c.value ?: ByteArray(0)
                pending?.complete(status)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this@GattClient.mtu = mtu
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(c.uuid, value)
        }

        @Deprecated("Called instead of the ByteArray overload before API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") onNotify(c.uuid, c.value ?: ByteArray(0))
        }
    }

    val services: List<BluetoothGattService> get() = gatt.services

    suspend fun discoverServices() {
        val status = op { gatt.discoverServices() }
        check(status == BluetoothGatt.GATT_SUCCESS) { "Service discovery failed (status $status)" }
    }

    suspend fun enableNotifications(c: BluetoothGattCharacteristic) {
        check(gatt.setCharacteristicNotification(c, true)) { "Couldn't enable notifications" }
        val cccd = checkNotNull(c.getDescriptor(CCCD)) { "No notification descriptor" }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val status = op {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }
        check(status == BluetoothGatt.GATT_SUCCESS) { "Enabling notifications failed (status $status)" }
    }

    suspend fun read(c: BluetoothGattCharacteristic): ByteArray {
        val status = op { gatt.readCharacteristic(c) }
        check(status == BluetoothGatt.GATT_SUCCESS) { "Read failed (status $status)" }
        return lastRead
    }

    suspend fun requestMtu(mtu: Int): Int {
        op { gatt.requestMtu(mtu) }
        return this.mtu
    }

    /**
     * Write with response by default, so the next write waits for the light to accept
     * this one. Characteristics that only allow write-without-response (e.g. mesh proxy
     * data in) need [noResponse].
     */
    suspend fun write(c: BluetoothGattCharacteristic, bytes: ByteArray, noResponse: Boolean = false) {
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val status = op {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = bytes
                c.writeType = type
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(c)
            }
        }
        check(status == BluetoothGatt.GATT_SUCCESS) { "Write failed (status $status)" }
    }

    fun close() {
        isConnected = false
        gatt.disconnect()
        gatt.close()
    }

    private suspend fun op(start: () -> Boolean): Int = ops.withLock {
        check(isConnected) { "Light disconnected" }
        val d = CompletableDeferred<Int>()
        pending = d
        try {
            check(start()) { "Bluetooth busy or not connected" }
            withTimeout(5000) { d.await() }
        } finally {
            pending = null
        }
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        suspend fun connect(
            context: Context,
            device: BluetoothDevice,
            onNotify: (UUID, ByteArray) -> Unit,
            onDisconnect: () -> Unit = {},
        ): GattClient {
            val client = GattClient(onNotify, onDisconnect)
            client.gatt = device.connectGatt(context, false, client.callback, BluetoothDevice.TRANSPORT_LE)
            try {
                withTimeout(15000) { client.connected.await() }
            } catch (e: Throwable) {
                client.close()
                throw e
            }
            return client
        }
    }
}

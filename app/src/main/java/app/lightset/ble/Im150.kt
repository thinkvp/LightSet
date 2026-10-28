package app.lightset.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import app.lightset.color.Xy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * The Illuminati IM150 meter's GATT protocol, decoded from an HCI snoop of the
 * Illuminati app (2026-10-29). Readings stream about once a second as notifications.
 */
object Im150Protocol {
    const val NAME = "Illuminati Meter"
    val SERVICE: UUID = uuid16(0xFE1D)

    /** Notify: float32 lux, float32 CIE x, float32 CIE y, u32 ticks (32768/s), little-endian. */
    val READING: UUID = uuid16(0x6045)

    /**
     * Measuring mode. The app writes 84 c5 00 00 after connecting, which gets one reading,
     * and [MODE_STREAM] to have readings sent continuously, about once a second.
     */
    val MODE: UUID = uuid16(0x6020)
    val MODE_STREAM = byteArrayOf(0xA3.toByte(), 0xC5.toByte(), 0, 0)

    val BATTERY_SERVICE: UUID = uuid16(0x180F)
    val BATTERY: UUID = uuid16(0x2A19)

    class Sample(val lux: Double, val xy: Xy)

    /** A reading, or null before the meter has measured anything (it sends NaN then). */
    fun parse(b: ByteArray): Sample? {
        if (b.size < 12) return null
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val lux = buf.float.toDouble()
        val x = buf.float.toDouble()
        val y = buf.float.toDouble()
        if (lux.isNaN() || x.isNaN() || y.isNaN() || y <= 0.0 || lux < 0.0) return null
        return Sample(lux, Xy(x, y))
    }

    private fun uuid16(v: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(v))
}

/**
 * Connection to an IM150. While [wanted], [keepConnected] finds and holds the meter
 * and [sample] carries its latest reading. The meter takes one connection at a time,
 * so the Illuminati app has to be closed.
 */
@SuppressLint("MissingPermission")
class Im150(private val app: Application) {
    enum class Status { OFF, SEARCHING, CONNECTED }

    class Sample(val lux: Double, val xy: Xy, val timeMs: Long)

    private val prefs = app.getSharedPreferences("im150", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status
    private val _sample = MutableStateFlow<Sample?>(null)
    val sample: StateFlow<Sample?> = _sample
    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Connect while the app is visible: once a meter has been used, or while something [need]s it. */
    private val _wanted = MutableStateFlow(address != null)
    val wanted: StateFlow<Boolean> = _wanted

    private var client: GattClient? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val address: String? get() = prefs.getString(KEY_ADDRESS, null)

    fun need(needed: Boolean) {
        _wanted.value = needed || address != null
    }

    /** Stops using the meter until it's chosen again. */
    fun forget() {
        prefs.edit().remove(KEY_ADDRESS).apply()
        _wanted.value = false
    }

    fun retry() {
        wake.trySend(Unit)
    }

    /** Holds a connection to the meter, reconnecting as needed, until cancelled. */
    suspend fun keepConnected() {
        try {
            while (true) {
                if (client?.isConnected != true) {
                    try {
                        connect()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "connect failed: ${e.message}")
                        _status.value = Status.OFF
                        _error.value = e.message
                    }
                }
                val connected = client?.isConnected == true
                withTimeoutOrNull(if (connected) 5000L else 10000L) { wake.receive() }
            }
        } finally {
            client?.close()
            client = null
            _status.value = Status.OFF
        }
    }

    /** The next reading that arrives after [afterMs]. */
    suspend fun next(afterMs: Long = System.currentTimeMillis(), timeoutMs: Long = 6000): Sample =
        withTimeout(timeoutMs) { sample.filterNotNull().first { it.timeMs > afterMs } }

    private suspend fun connect() {
        client?.close()
        client = null
        _status.value = Status.SEARCHING
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        checkNotNull(adapter) { "No Bluetooth" }
        check(adapter.isEnabled) { "Bluetooth is off" }
        val addr = scan(adapter) ?: address?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
            ?: error("IM150 not found")
        val c = GattClient.connect(app, adapter.getRemoteDevice(addr), ::onNotify, onDisconnect = {
            _status.value = Status.SEARCHING
            wake.trySend(Unit)
        })
        try {
            c.discoverServices()
            val svc = c.services.firstOrNull { it.uuid == Im150Protocol.SERVICE } ?: error("That isn't an IM150")
            val reading = checkNotNull(svc.getCharacteristic(Im150Protocol.READING)) { "IM150 reading not found" }
            c.services.firstOrNull { it.uuid == Im150Protocol.BATTERY_SERVICE }
                ?.getCharacteristic(Im150Protocol.BATTERY)?.let { b ->
                    runCatching { c.read(b).firstOrNull()?.let { _battery.value = it.toInt() and 0xFF } }
                    runCatching { c.enableNotifications(b) }
                }
            c.enableNotifications(reading)
            val mode = checkNotNull(svc.getCharacteristic(Im150Protocol.MODE)) { "IM150 mode not found" }
            c.write(mode, Im150Protocol.MODE_STREAM)
            client = c
            prefs.edit().putString(KEY_ADDRESS, addr).apply()
            _status.value = Status.CONNECTED
            _error.value = null
            Log.d(TAG, "connected to $addr, streaming")
        } catch (e: Throwable) {
            c.close()
            client = null
            throw e
        }
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        when (uuid) {
            Im150Protocol.READING -> Im150Protocol.parse(value)?.let {
                _sample.value = Sample(it.lux, it.xy, System.currentTimeMillis())
            }
            Im150Protocol.BATTERY -> value.firstOrNull()?.let { _battery.value = it.toInt() and 0xFF }
        }
    }

    /** Scans briefly for the meter's advert by name; returns its address, or null. */
    private suspend fun scan(adapter: BluetoothAdapter): String? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: return
                if (name.trim('\u0000', ' ').startsWith(Im150Protocol.NAME)) found.complete(result.device.address)
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        if (runCatching { scanner.startScan(null, settings, callback) }.isFailure) return null
        return try {
            withTimeoutOrNull(SCAN_MS) { found.await() }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    private companion object {
        const val TAG = "LightSetIm150"
        const val KEY_ADDRESS = "address"
        const val SCAN_MS = 5000L
    }
}

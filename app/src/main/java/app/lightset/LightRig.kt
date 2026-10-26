package app.lightset

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.util.Log
import app.lightset.ble.NanliteProtocol
import app.lightset.color.LightSetting
import app.lightset.mesh.LightModel
import app.lightset.mesh.MeshNode
import app.lightset.mesh.MeshStore
import app.lightset.mesh.NanliteMeshLink
import app.lightset.mesh.Provisioner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class LightSetApp : Application() {
    val rig by lazy { LightRig(this) }
}

/**
 * The app's lights. One Bluetooth connection to any reachable light (its mesh proxy)
 * carries messages to all of them by address. Each light's state is owned here and
 * sent whole on every change, because the lights re-apply their stored values
 * (which reset on reconnect) whenever any one setting changes.
 */
@SuppressLint("MissingPermission")
class LightRig(private val app: Application) {
    enum class Status { OFFLINE, CONNECTING, ONLINE }
    enum class Mode { CCT, HSI }

    /** What the app has a light set to. Brightness is null until the user sets it. */
    data class LightState(
        val brightness: Int?,
        val cct: Int,
        val gm: Int,
        val mode: Mode = Mode.CCT,
        val hue: Int = 0,
        val sat: Int = 100,
    )

    val mesh = MeshStore(app)
    private val lock = Mutex()
    private val prefs = app.getSharedPreferences("rig", android.content.Context.MODE_PRIVATE)
    private var link: NanliteMeshLink? = null
    private var proxyAddress: String? = prefs.getString(KEY_PROXY, null)

    private val _log = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val log: SharedFlow<String> = _log

    private val _nodes = MutableStateFlow(mesh.nodes())
    val nodes: StateFlow<List<MeshNode>> = _nodes

    private val _status = MutableStateFlow(Status.OFFLINE)
    /** Whether the app has a Bluetooth connection into the network. */
    val status: StateFlow<Status> = _status

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _reachable = MutableStateFlow<Set<Int>>(emptySet())
    /** Lights that answered recently, by unicast address. */
    val reachable: StateFlow<Set<Int>> = _reachable

    private val _states = MutableStateFlow(_nodes.value.associate { it.unicast to loadState(it.unicast) })
    val states: StateFlow<Map<Int, LightState>> = _states

    /** Mode each light was last switched to over this connection; missing = unknown. */
    private val sentMode = HashMap<Int, Mode>()

    val connected: Boolean get() = link?.connected == true

    fun node(unicast: Int): MeshNode? = _nodes.value.firstOrNull { it.unicast == unicast }
    fun state(unicast: Int): LightState = _states.value[unicast] ?: loadState(unicast)

    private fun log(line: String) {
        Log.d("LightSetBle", line)
        _log.tryEmit(line)
    }

    // --- Connection ------------------------------------------------------------

    /**
     * Keeps a connection into the network while the app is in the foreground and checks
     * which lights answer, so each shows online/offline. Cancel to stop.
     */
    suspend fun keepConnected() {
        var lastPing = 0L
        while (true) {
            if (!connected && _status.value != Status.CONNECTING) runCatching { link() }
            if (connected && System.currentTimeMillis() - lastPing > PING_INTERVAL_MS) {
                pingAll()
                lastPing = System.currentTimeMillis()
            }
            delay(if (connected) 2000 else 6000)
        }
    }

    suspend fun reconnect() {
        runCatching { link() }
    }

    suspend fun link(): NanliteMeshLink = lock.withLock {
        link?.let { l ->
            if (l.connected) return@withLock l
            log("Reconnecting…")
            l.close()
            link = null
        }
        _status.value = Status.CONNECTING
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        try {
            checkNotNull(adapter) { "No Bluetooth" }
            check(adapter.isEnabled) { "Bluetooth is off" }
            check(_nodes.value.isNotEmpty()) { "No lights added yet" }
        } catch (e: Exception) {
            _status.value = Status.OFFLINE
            _lastError.value = e.message
            throw e
        }
        // Try the light that worked last time first, then the others.
        val candidates = _nodes.value.map { it.address }.distinct()
            .sortedByDescending { it == proxyAddress }
        var failure: Throwable? = null
        for (address in candidates) {
            val l = NanliteMeshLink(app, mesh.netKey, mesh.ivIndex, mesh.ourAddress, DEFAULT_KEYS, ::log,
                onDisconnect = {
                    _status.value = Status.OFFLINE
                    _reachable.value = emptySet()
                },
                deviceKeys = { u -> deviceKey(u) })
            try {
                l.connect(adapter!!.getRemoteDevice(address))
                link = l
                sentMode.clear()
                proxyAddress = address
                prefs.edit().putString(KEY_PROXY, address).apply()
                _nodes.value.firstOrNull { it.address == address }?.let { n -> _reachable.update { it + n.unicast } }
                _status.value = Status.ONLINE
                _lastError.value = null
                return@withLock l
            } catch (e: Throwable) {
                l.close()
                failure = e
            }
        }
        _status.value = Status.OFFLINE
        _lastError.value = if (failure is kotlinx.coroutines.TimeoutCancellationException) {
            if (candidates.size == 1) "Light not found — is it on?" else "No lights found — are they on?"
        } else failure?.message
        throw failure ?: IllegalStateException("Couldn't connect")
    }

    fun disconnect() {
        link?.close()
        link = null
        _status.value = Status.OFFLINE
        _reachable.value = emptySet()
    }

    /** Reads CCT from every light; the ones that answer are reachable. */
    suspend fun pingAll() {
        val l = link ?: return
        val ok = mutableSetOf<Int>()
        for (n in _nodes.value) {
            // Lights we added answer Config Default TTL Get (device key); not every
            // model answers Nanlite reads (the PavoTube acts on writes but stays silent).
            val dk = deviceKey(n.unicast)
            val reply = runCatching {
                withTimeoutOrNull(5000) {
                    if (dk != null) l.configRequest(n.unicast, dk, byteArrayOf(0x80.toByte(), 0x0C), byteArrayOf(0x80.toByte(), 0x0E))
                    else l.read(n.unicast, NanliteProtocol.Param.CCT.id)
                }
            }.getOrNull()
            if (reply != null) ok += n.unicast
        }
        _nodes.value.firstOrNull { it.address == proxyAddress }?.let { ok += it.unicast }
        _reachable.value = ok
    }

    // --- Adding and removing lights -----------------------------------------------

    /** Joins a light that's waiting to join (Bluetooth reset) to our network. */
    suspend fun addLight(device: BluetoothDevice, name: String, progress: (String) -> Unit): MeshNode {
        val unicast = mesh.nextUnicast()
        val result = Provisioner(app, ::log).provision(device, mesh.netKey, mesh.ivIndex, unicast, progress)
        val node = MeshNode(
            unicast = unicast,
            name = name,
            model = LightModel.fromName(name),
            address = device.address,
            deviceKey = result.deviceKey.joinToString("") { "%02x".format(it) },
        )
        val list = _nodes.value.filterNot { it.address == node.address } + node
        mesh.save(list)
        _nodes.value = list
        _states.update { it + (unicast to loadState(unicast)) }
        log("Added ${node.name} at %04x".format(unicast))
        // Give it a moment to restart as a network member, then set it up for control.
        progress("Setting up…")
        delay(2500)
        runCatching { configure(unicast) }.onSuccess(progress).onFailure { progress("Set-up: ${it.message}") }
        return node
    }

    private fun deviceKey(unicast: Int): ByteArray? = node(unicast)?.deviceKey?.let { h ->
        ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * Sets a light up for control: installs the shared app key and links it to the
     * Nanlite vendor model, using the light's device key. Some firmware does this by
     * itself after joining; this makes sure. Returns a one-line summary.
     */
    suspend fun configure(unicast: Int): String {
        val dk = checkNotNull(deviceKey(unicast)) { "No device key for this light (it was added by another app)" }
        val l = link()
        val comp = l.configRequest(unicast, dk, byteArrayOf(0x80.toByte(), 0x08, 0x00), byteArrayOf(0x02))
            ?: return "Light didn't answer — is it in range of ${node(unicast)?.name ?: "it"}?"
        log("Composition: ${comp.joinToString("") { "%02x".format(it) }}")
        val addKey = byteArrayOf(0x00, 0x00, 0x00, 0x00) + NanliteMeshLink.NANLITE_DEFAULT_APP_KEY
        val keyStatus = l.configRequest(unicast, dk, addKey, byteArrayOf(0x80.toByte(), 0x03))?.get(0)?.toInt()
        val bind = byteArrayOf(0x80.toByte(), 0x3D, unicast.toByte(), (unicast ushr 8).toByte(), 0x00, 0x00, 0x11, 0x11, 0x11, 0x11)
        val bindStatus = l.configRequest(unicast, dk, bind, byteArrayOf(0x80.toByte(), 0x3E))?.get(0)?.toInt()
        // Have the vendor model send its replies/status to us, for firmware that only
        // answers via publication. Publish to our address with app key 0, TTL 5.
        val me = mesh.ourAddress
        val pub = byteArrayOf(
            0x03, unicast.toByte(), (unicast ushr 8).toByte(),
            me.toByte(), (me ushr 8).toByte(),
            0x00, 0x00, 0x05, 0x00, 0x00,
            0x11, 0x11, 0x11, 0x11,
        )
        val pubStatus = l.configRequest(unicast, dk, pub, byteArrayOf(0x80.toByte(), 0x19))?.get(0)?.toInt()
        val summary = "App key: ${statusName(keyStatus)}; vendor model bind: ${statusName(bindStatus)}; " +
            "replies to app: ${statusName(pubStatus)}"
        log(summary)
        pingAll()
        return summary
    }

    private fun statusName(code: Int?) = when (code) {
        null -> "no reply"
        0x00 -> "OK"
        0x02 -> "invalid model"
        0x03 -> "invalid app key index"
        0x04 -> "invalid net key index"
        0x06 -> "already set (different key)"
        else -> "status %02x".format(code)
    }

    fun updateNode(node: MeshNode) {
        val list = _nodes.value.map { if (it.unicast == node.unicast) node else it }
        mesh.save(list)
        _nodes.value = list
    }

    /** Forgets a light. It stays in the network until its Bluetooth is reset. */
    fun removeLight(unicast: Int) {
        val list = _nodes.value.filterNot { it.unicast == unicast }
        mesh.save(list)
        _nodes.value = list
        _states.update { it - unicast }
    }

    // --- Settings --------------------------------------------------------------

    suspend fun setBrightness(unicast: Int, percent: Int) =
        send(unicast, state(unicast).copy(brightness = percent.coerceIn(0, 100)))

    suspend fun set(unicast: Int, s: LightState) = send(unicast, s)

    /** Sends CCT and G/M (switching to CCT mode), keeping the user's brightness. */
    suspend fun apply(unicast: Int, setting: LightSetting) =
        send(unicast, state(unicast).copy(mode = Mode.CCT, cct = setting.cct, gm = setting.gm))

    /** Turns a light to 0% (for an ambient-only reading) without forgetting its brightness. */
    suspend fun lightOff(unicast: Int) = send(unicast, state(unicast).copy(brightness = 0), remember = false)

    /** Re-sends the remembered state, e.g. after [lightOff]. */
    suspend fun restore(unicast: Int) = send(unicast, state(unicast))

    /** Diagnostics: one parameter alone, without touching the remembered state. */
    suspend fun writeOnly(unicast: Int, param: NanliteProtocol.Param, value: Int) =
        link().write(unicast, param.id, value and 0xFFFF, 0)

    private suspend fun send(unicast: Int, s: LightState, remember: Boolean = true) {
        try {
            write(unicast, s)
        } catch (e: IllegalStateException) {
            // Link died mid-send: reconnect once and resend the whole state.
            log("Send failed (${e.message}); reconnecting")
            disconnect()
            write(unicast, s)
        }
        if (!remember) return
        _states.update { it + (unicast to s) }
        prefs.edit()
            .putInt("b_$unicast", s.brightness ?: -1)
            .putInt("cct_$unicast", s.cct)
            .putInt("gm_$unicast", s.gm)
            .putString("mode_$unicast", s.mode.name)
            .putInt("hue_$unicast", s.hue)
            .putInt("sat_$unicast", s.sat)
            .apply()
    }

    private suspend fun write(dst: Int, s: LightState) {
        val l = link()
        if (s.mode == Mode.HSI) {
            // Colour needs the extended set_mode packet: separate hue/sat writes
            // don't switch the light out of CCT mode.
            l.sendExtended(dst) { seq ->
                NanliteProtocol.setMode(seq, s.brightness ?: 50, NanliteProtocol.MODE_HSI, 0x01,
                    NanliteProtocol.hsiParams(s.hue.coerceIn(0, 359), s.sat.coerceIn(0, 100)))
            }
            sentMode[dst] = Mode.HSI
            return
        }
        if (sentMode[dst] != Mode.CCT) {
            // Switch to CCT mode first (from colour, or unknown after reconnecting).
            l.sendExtended(dst) { seq ->
                NanliteProtocol.setMode(seq, s.brightness ?: 50, NanliteProtocol.MODE_CCT, 0x02,
                    NanliteProtocol.cctParams(s.cct, s.gm))
            }
            sentMode[dst] = Mode.CCT
            delay(WRITE_GAP_MS)
        }
        // G/M goes last: a later brightness or CCT write was observed to leave the
        // light's G/M unchanged even after a G/M write. Spaced so none get dropped.
        s.brightness?.let {
            l.write(dst, NanliteProtocol.Param.BRIGHTNESS.id, it, 0)
            delay(WRITE_GAP_MS)
        }
        l.write(dst, NanliteProtocol.Param.CCT.id, s.cct, 0)
        delay(WRITE_GAP_MS)
        l.write(dst, NanliteProtocol.Param.GM.id, s.gm and 0xFFFF, 0)
    }

    private fun loadState(unicast: Int): LightState {
        // The single-light build stored the 60C's state under *_60c keys.
        if (unicast == 0x0002 && !prefs.contains("cct_2") && prefs.contains("cct_60c")) {
            return LightState(
                brightness = prefs.getInt("brightness_60c", -1).takeIf { it >= 0 },
                cct = prefs.getInt("cct_60c", 5600),
                gm = prefs.getInt("gm_60c", 0),
                mode = runCatching { Mode.valueOf(prefs.getString("mode_60c", null) ?: "CCT") }.getOrDefault(Mode.CCT),
                hue = prefs.getInt("hue_60c", 0),
                sat = prefs.getInt("sat_60c", 100),
            )
        }
        return LightState(
            brightness = prefs.getInt("b_$unicast", -1).takeIf { it >= 0 },
            cct = prefs.getInt("cct_$unicast", 5600),
            gm = prefs.getInt("gm_$unicast", 0),
            mode = runCatching { Mode.valueOf(prefs.getString("mode_$unicast", null) ?: "CCT") }.getOrDefault(Mode.CCT),
            hue = prefs.getInt("hue_$unicast", 0),
            sat = prefs.getInt("sat_$unicast", 100),
        )
    }

    private companion object {
        const val KEY_PROXY = "proxy_address"
        const val WRITE_GAP_MS = 60L
        const val PING_INTERVAL_MS = 10_000L
        val DEFAULT_KEYS = linkedMapOf("default" to NanliteMeshLink.NANLITE_DEFAULT_APP_KEY)
    }
}

package app.lightset.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lightset.LightRig
import app.lightset.LightSetApp
import app.lightset.ble.NanliteProtocol
import app.lightset.data.Scene
import app.lightset.data.SceneStore
import app.lightset.mesh.LightModel
import app.lightset.mesh.MeshNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** A light seen while scanning that's waiting to join a network. */
data class JoinCandidate(val address: String, val name: String?, val rssi: Int)

/** The Add light flow: scanning for lights after a Bluetooth reset, then joining one. */
data class AddLightUi(
    val scanning: Boolean = true,
    val candidates: List<JoinCandidate> = emptyList(),
    val joining: String? = null,
    val progress: String? = null,
    val error: String? = null,
    val added: String? = null,
)

data class LightsUiState(
    val lights: List<MeshNode> = emptyList(),
    /** What the app has each light set to, including changes still being sent. */
    val states: Map<Int, LightRig.LightState> = emptyMap(),
    val reachable: Set<Int> = emptySet(),
    val status: LightRig.Status = LightRig.Status.OFFLINE,
    val statusError: String? = null,
    val controlError: String? = null,
    val add: AddLightUi? = null,
    val log: List<String> = emptyList(),
    /** Result of the last per-light action (e.g. set-up), shown under the light. */
    val notice: Pair<Int, String>? = null,
    val scenes: List<Scene> = emptyList(),
)

@SuppressLint("MissingPermission")
class LightsViewModel(app: Application) : AndroidViewModel(app) {
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val rig = (app as LightSetApp).rig
    private val scenes = SceneStore(app)
    private val _state = MutableStateFlow(LightsUiState(scenes = scenes.load()))
    val state: StateFlow<LightsUiState> = _state

    /** Control changes not yet sent, per light; sends run one at a time and skip stale values. */
    private val pending = MutableStateFlow<Map<Int, LightRig.LightState>>(emptyMap())

    /** New order of the lights, after a drag on the Lights tab; Match lists them the same way. */
    fun reorder(unicasts: List<Int>) = rig.reorder(unicasts)

    init {
        viewModelScope.launch {
            combine(rig.nodes, rig.states, pending, rig.reachable, rig.status) { nodes, states, p, reach, st ->
                _state.value.copy(lights = nodes, states = states + p, reachable = reach, status = st)
            }.collect { s -> _state.update { it.copy(lights = s.lights, states = s.states, reachable = s.reachable, status = s.status) } }
        }
        viewModelScope.launch { rig.lastError.collect { e -> _state.update { it.copy(statusError = e) } } }
        viewModelScope.launch { rig.log.collect { line -> _state.update { it.copy(log = (it.log + line).takeLast(300)) } } }
        viewModelScope.launch {
            pending.collect { map ->
                for ((u, s) in map) {
                    try {
                        rig.set(u, s)
                        _state.update { it.copy(controlError = null) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _state.update { it.copy(controlError = e.message) }
                    }
                    // Done with this light unless a newer value arrived meanwhile.
                    pending.update { m -> if (m[u] == s) m - u else m }
                }
            }
        }
    }

    /** The all-lights switch: every connected light off, or back on at its settings. */
    fun setAllOn(on: Boolean) {
        val s = _state.value
        pending.update { p ->
            p + s.reachable.mapNotNull { u -> s.states[u]?.let { u to it.copy(on = on) } }
        }
    }

    /** From the light controls; the UI shows the new values straight away. */
    fun setLight(unicast: Int, target: LightRig.LightState) = pending.update { it + (unicast to target) }

    // --- Scenes ----------------------------------------------------------------

    /** Saves every light's current settings (including ones still being sent). */
    fun saveScene(name: String) {
        val s = _state.value
        val scene = Scene(Scene.newId(), name.ifBlank { "Scene" }, System.currentTimeMillis(),
            s.lights.mapNotNull { n -> s.states[n.unicast]?.let { n.unicast to it } }.toMap())
        updateScenes(s.scenes + scene)
    }

    /** Sends each light in the scene its saved settings; lights not in it are left alone. */
    fun loadScene(scene: Scene) {
        val known = rig.nodes.value.map { it.unicast }.toSet()
        pending.update { it + scene.lights.filterKeys { u -> u in known } }
    }

    fun deleteScene(id: String) = updateScenes(_state.value.scenes.filterNot { it.id == id })

    private fun updateScenes(list: List<Scene>) {
        scenes.save(list)
        _state.update { it.copy(scenes = list) }
    }

    /** Connects (agreeing to, if the start-up prompt was put off) or checks the lights now. */
    fun refresh() = rig.connectNow()

    fun rename(node: MeshNode, name: String) = rig.updateNode(node.copy(name = name.ifBlank { node.name }))

    fun setModel(node: MeshNode, model: LightModel) = rig.updateNode(node.copy(model = model))

    fun remove(node: MeshNode) = rig.removeLight(node.unicast)

    /** Re-runs set-up (app key + vendor model binding) for a light that isn't answering. */
    fun configure(node: MeshNode) {
        viewModelScope.launch {
            _state.update { it.copy(notice = node.unicast to "Setting up…") }
            val msg = try {
                rig.configure(node.unicast)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Set-up failed"
            }
            _state.update { it.copy(notice = node.unicast to msg) }
        }
    }

    /** Diagnostics: reads every parameter of one light. */
    fun readAll(node: MeshNode) {
        viewModelScope.launch {
            try {
                val link = rig.link()
                log("Reading ${node.name} (%04x)".format(node.unicast))
                for (param in NanliteProtocol.Param.entries) {
                    val p = link.read(node.unicast, param.id)
                    log(if (p == null) "  ${param.label}: no reply" else "  ${param.label} = ${if (param.signed) p.s16 else p.u16}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("✗ ${e.message}")
            }
        }
    }

    // --- Add light -------------------------------------------------------------

    private val found = ConcurrentHashMap<String, JoinCandidate>()
    private var scanJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rec = result.scanRecord ?: return
            // Lights waiting to join advertise Mesh Provisioning (0x1827) in service data or UUIDs.
            val uuids = (rec.serviceUuids.orEmpty() + rec.serviceData?.keys.orEmpty()).map { it.uuid.toString() }
            val address = result.device.address
            val waiting = uuids.any { it.startsWith("00001827") }
            if (!waiting && !found.containsKey(address)) return
            val prev = found[address]
            found[address] = JoinCandidate(address, rec.deviceName ?: result.device.name ?: prev?.name, result.rssi)
            val known = rig.nodes.value.map { it.address }.toSet()
            _state.update { s ->
                s.copy(add = s.add?.copy(candidates = found.values.filter { it.address !in known }.sortedByDescending { it.rssi }))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.update { it.copy(add = it.add?.copy(scanning = false, error = "Scan failed (error $errorCode)")) }
        }
    }

    fun startAdd() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            _state.update { it.copy(add = AddLightUi(scanning = false, error = "Bluetooth is off")) }
            return
        }
        found.clear()
        _state.update { it.copy(add = AddLightUi()) }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            delay(30_000)
            stopScan()
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        _state.update { it.copy(add = it.add?.copy(scanning = false)) }
    }

    fun closeAdd() {
        stopScan()
        _state.update { it.copy(add = null) }
    }

    fun join(candidate: JoinCandidate, name: String) {
        if (_state.value.add?.joining != null) return
        stopScan()
        viewModelScope.launch {
            _state.update { it.copy(add = it.add?.copy(joining = candidate.address, progress = "Starting…", error = null)) }
            try {
                val device = adapter!!.getRemoteDevice(candidate.address)
                val node = rig.addLight(device, name.ifBlank { candidate.name ?: "Light" }) { step ->
                    _state.update { it.copy(add = it.add?.copy(progress = step)) }
                }
                _state.update {
                    it.copy(add = it.add?.copy(joining = null, progress = null, added = node.name,
                        candidates = it.add.candidates.filterNot { c -> c.address == candidate.address }))
                }
                // The light restarts as a network member; give it a moment, then check it answers.
                delay(3000)
                runCatching { rig.pingAll() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(add = it.add?.copy(joining = null, progress = null, error = e.message ?: "Couldn't add the light")) }
            }
        }
    }

    private fun log(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(300)) }

    override fun onCleared() {
        stopScan()
    }
}

package app.lightset.ui

import android.app.Application
import android.graphics.SurfaceTexture
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lightset.LightRig
import app.lightset.LightSetApp
import app.lightset.ble.NanliteProtocol
import app.lightset.camera.CameraMeter
import app.lightset.camera.ExposureWarning
import app.lightset.camera.Reading
import app.lightset.color.AmbientSubtraction
import app.lightset.color.CalPoint
import app.lightset.color.Calibration
import app.lightset.color.CctDuv
import app.lightset.color.DialIn
import app.lightset.color.LightLimits
import app.lightset.color.LightSetting
import app.lightset.color.Xy
import app.lightset.color.cctDuv
import app.lightset.color.delta
import app.lightset.data.Reference
import app.lightset.data.ReferenceStore
import app.lightset.mesh.MeshNode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DIAL_MAX_STEPS = 8
/** Time for the light to change and the camera's exposure to settle. */
private const val SETTLE_MS = 900L

private const val KEY_SELECTED = "selected_light"

/** Colour temps a trusted light is set to during calibration, at G/M 0. */
val CALIBRATION_TARGETS = listOf(2700.0, 3200.0, 4300.0, 5600.0, 6500.0)

/** In-progress manual calibration: one raw reading slot per target, and the step being measured. */
data class CalSession(
    val readings: List<CctDuv?> = CALIBRATION_TARGETS.map { null },
    val index: Int = 0,
    val error: String? = null,
) {
    val complete: Boolean get() = readings.all { it != null }
}

/** A running or finished light task (dial-in, auto-calibration, G/M test). */
data class TaskUi(
    val title: String,
    val running: Boolean = true,
    val lines: List<String> = emptyList(),
    val result: String? = null,
    val success: Boolean = false,
)

data class MeterUiState(
    val cameraInfo: CameraMeter.Info? = null,
    val cameraOpen: Boolean = false,
    val reading: Reading? = null,
    val references: List<Reference> = emptyList(),
    val activeId: String? = null,
    val measuring: Boolean = false,
    val live: Boolean = false,
    val error: String? = null,
    val calibration: Calibration? = null,
    val calSession: CalSession? = null,
    val task: TaskUi? = null,
    /** Lights in the network, and the one used for matching and calibration. */
    val lights: List<MeshNode> = emptyList(),
    val selected: Int? = null,
    /** What the app has the selected light set to. */
    val light: LightRig.LightState? = null,
    val lightStatus: LightRig.Status = LightRig.Status.OFFLINE,
    val lightError: String? = null,
) {
    val selectedNode: MeshNode? get() = lights.firstOrNull { it.unicast == selected }

    val active: Reference? get() = references.firstOrNull { it.id == activeId }
    val busy: Boolean get() = measuring || task?.running == true

    /** A light task can start: camera running, light reachable, on, brightness chosen. */
    val canRunLightTask: Boolean
        get() = cameraOpen && !busy && !live && calSession == null &&
            lightStatus == LightRig.Status.ONLINE && light?.on == true && (light.brightness ?: 0) > 0

    /** Sensor-model reading with the stored calibration applied, if there is one. */
    fun corrected(xy: Xy): CctDuv = cctDuv(xy).let { calibration?.apply(it) ?: it }
}

class MeterViewModel(app: Application) : AndroidViewModel(app) {
    private val meter = CameraMeter(app)
    private val store = ReferenceStore(app)
    private val rig = (app as LightSetApp).rig

    private val _state = MutableStateFlow(
        MeterUiState(references = store.load(), activeId = store.activeId, calibration = store.calibration)
    )
    val state: StateFlow<MeterUiState> = _state

    private var liveJob: Job? = null
    private var taskJob: Job? = null

    private val prefs = app.getSharedPreferences("match", android.content.Context.MODE_PRIVATE)
    private val selectedFlow = MutableStateFlow(prefs.getInt(KEY_SELECTED, -1).takeIf { it >= 0 })

    init {
        viewModelScope.launch {
            combine(rig.nodes, rig.states, rig.status, rig.reachable, selectedFlow) { nodes, states, st, reach, sel ->
                val u = sel?.takeIf { s -> nodes.any { it.unicast == s } } ?: nodes.firstOrNull()?.unicast
                val lightStatus = when {
                    u == null -> LightRig.Status.OFFLINE
                    st == LightRig.Status.ONLINE && u in reach -> LightRig.Status.ONLINE
                    st == LightRig.Status.ONLINE || st == LightRig.Status.CONNECTING -> LightRig.Status.CONNECTING
                    else -> LightRig.Status.OFFLINE
                }
                SelectedLight(nodes, u, u?.let { states[it] }, lightStatus)
            }.collect { sl ->
                _state.update { it.copy(lights = sl.nodes, selected = sl.unicast, light = sl.state, lightStatus = sl.status) }
            }
        }
        viewModelScope.launch { rig.lastError.collect { e -> _state.update { it.copy(lightError = e) } } }
    }

    private class SelectedLight(
        val nodes: List<MeshNode>, val unicast: Int?, val state: LightRig.LightState?, val status: LightRig.Status,
    )

    fun selectLight(unicast: Int) {
        prefs.edit().putInt(KEY_SELECTED, unicast).apply()
        selectedFlow.value = unicast
    }

    /** The selected light's address; tasks need one. */
    private fun target(): Int? = _state.value.selected

    // --- Camera and readings -------------------------------------------------

    fun cameraInfo(): CameraMeter.Info? = runCatching { meter.probe() }
        .onSuccess { info -> _state.update { it.copy(cameraInfo = info) } }
        .onFailure { e -> _state.update { it.copy(error = e.message) } }
        .getOrNull()

    fun startCamera(texture: SurfaceTexture) {
        viewModelScope.launch {
            try {
                meter.open(texture)
                _state.update { it.copy(cameraOpen = true, cameraInfo = meter.info, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Camera: ${e.message}") }
            }
        }
    }

    fun stopCamera() {
        stopTask()
        setLive(false)
        meter.close()
        _state.update { it.copy(cameraOpen = false, measuring = false) }
    }

    fun measureOnce() {
        if (_state.value.busy || _state.value.live) return
        viewModelScope.launch {
            _state.update { it.copy(measuring = true) }
            takeReading(frames = 4)
            _state.update { it.copy(measuring = false) }
        }
    }

    fun setLive(on: Boolean) {
        liveJob?.cancel()
        liveJob = null
        _state.update { it.copy(live = on) }
        if (!on) return
        liveJob = viewModelScope.launch {
            while (isActive) {
                takeReading(frames = 2)
                delay(200)
            }
        }
    }

    private suspend fun takeReading(frames: Int): Reading? {
        return try {
            meter.measure(frames).also { r -> _state.update { it.copy(reading = r, error = null) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_state.value.cameraOpen) _state.update { it.copy(error = "Measure: ${e.message}") }
            delay(500)
            null
        }
    }

    // --- References ----------------------------------------------------------

    fun saveReference(name: String) {
        val s = _state.value
        val r = s.reading ?: return
        val shown = s.corrected(r.xy)
        val ref = Reference(
            id = Reference.newId(),
            name = name.ifBlank { "Reference" },
            cct = shown.cct,
            duv = shown.duv,
            x = r.xy.x,
            y = r.xy.y,
            source = r.source,
            createdAt = r.timestampMs,
        )
        val list = listOf(ref) + s.references
        store.save(list)
        store.activeId = ref.id
        _state.update { it.copy(references = list, activeId = ref.id) }
    }

    fun selectReference(id: String?) {
        store.activeId = id
        _state.update { it.copy(activeId = id) }
    }

    fun deleteReference(id: String) {
        val list = _state.value.references.filterNot { it.id == id }
        store.save(list)
        val active = _state.value.activeId.takeUnless { it == id }
        store.activeId = active
        _state.update { it.copy(references = list, activeId = active) }
    }

    // --- Light ---------------------------------------------------------------

    fun setLightBrightness(percent: Int) = lightCall { target()?.let { rig.setBrightness(it, percent) } }

    fun setLightOn(on: Boolean) = lightCall { target()?.let { rig.setOn(it, on) } }

    fun reconnectLight() = lightCall { rig.reconnect() }

    private fun lightCall(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Light: ${e.message}") }
            }
        }
    }

    // --- Manual calibration --------------------------------------------------

    fun startCalibration() {
        setLive(false)
        _state.update { it.copy(calSession = CalSession(), task = null) }
    }

    fun cancelCalibration() = _state.update { it.copy(calSession = null) }

    fun selectCalStep(index: Int) = _state.update { s ->
        s.copy(calSession = s.calSession?.copy(index = index, error = null))
    }

    fun measureCalStep() {
        val session = _state.value.calSession ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(measuring = true) }
            val r = takeReading(frames = 6)
            _state.update { s ->
                val cur = s.calSession ?: return@update s.copy(measuring = false)
                if (r == null) return@update s.copy(measuring = false)
                val readings = cur.readings.toMutableList().also { it[session.index] = r.cctDuv }
                val next = readings.indexOfFirst { it == null }.takeIf { it >= 0 } ?: session.index
                val warn = when (r.warning) {
                    ExposureWarning.CLIPPED -> "That reading was overexposed — consider redoing it."
                    ExposureWarning.TOO_DARK -> "That reading was very dark — consider redoing it."
                    ExposureWarning.NONE -> null
                }
                s.copy(measuring = false, calSession = cur.copy(readings = readings, index = next, error = warn))
            }
        }
    }

    fun saveCalibration() {
        val session = _state.value.calSession ?: return
        val points = CALIBRATION_TARGETS.zip(session.readings).mapNotNull { (k, r) -> r?.let { CalPoint(k, it) } }
        try {
            val cal = Calibration(points)
            store.calibration = cal
            _state.update { it.copy(calibration = cal, calSession = null) }
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(calSession = session.copy(error = e.message)) }
        }
    }

    fun clearCalibration() {
        store.calibration = null
        _state.update { it.copy(calibration = null) }
    }

    // --- Light tasks ---------------------------------------------------------

    /** Matches the test light to the active reference, adjusting CCT and G/M only. */
    fun startDialIn() {
        val s = _state.value
        val ref = s.active ?: return
        val brightness = s.light?.brightness ?: return
        val node = s.selectedNode ?: return
        val target = s.corrected(ref.xy)
        runTask("Matching ${node.name} to ${ref.name}") {
            val d = DialIn(target, node.model.limits)
            var setting = d.initial()
            line("Target ${fmt(target)} · brightness $brightness%")
            val ambient = lockAndReadAmbient(node.unicast, setting) ?: return@runTask
            for (i in 1..DIAL_MAX_STEPS) {
                delay(SETTLE_MS)
                val m = readLight(ambient) ?: return@runTask
                val dd = delta(m.first, target)
                line("$i  ${setting.cct} K, G/M %+d → %s  (%+.0f mired, tint %+.1f)"
                    .format(setting.gm, fmt(m.first), dd.mired, dd.duv * 1000))
                when (val step = d.next(m.first)) {
                    is DialIn.Step.Matched ->
                        return@runTask finish(true, "Matched: ${step.setting.cct} K, G/M %+d".format(step.setting.gm))
                    is DialIn.Step.Partial -> return@runTask finish(false, step.reason)
                    is DialIn.Step.OutOfRange -> return@runTask finish(false, step.reason)
                    is DialIn.Step.Send -> {
                        setting = step.setting
                        rig.apply(node.unicast, setting)
                    }
                }
            }
            finish(false, "Not within tolerance after $DIAL_MAX_STEPS readings")
        }
    }

    /**
     * Calibrates the meter against the test light: the app steps the light through
     * the calibration colour temps at G/M 0 and records what the sensor model reads,
     * with ambient subtracted, so it works with room light on.
     */
    fun startAutoCalibration() {
        val start = _state.value.light ?: return
        val node = _state.value.selectedNode ?: return
        runTask("Calibrating meter with ${node.name}") {
            line("Hold the card in the light; it will step through ${CALIBRATION_TARGETS.size} colour temps")
            val ambient = lockAndReadAmbient(node.unicast, LightSetting(5600, 0)) ?: return@runTask
            val points = mutableListOf<CalPoint>()
            for (k in CALIBRATION_TARGETS) {
                rig.apply(node.unicast, LightSetting(k.toInt(), 0))
                delay(SETTLE_MS + 300)
                val m = readLight(ambient, calibrated = false) ?: return@runTask
                points += CalPoint(k, m.first)
                line("%,d K → read %s".format(k.toInt(), fmt(m.first)))
            }
            rig.set(node.unicast, start)
            try {
                val cal = Calibration(points)
                store.calibration = cal
                _state.update { it.copy(calibration = cal) }
                finish(true, "Meter calibrated (${points.size} points). ${node.name} restored")
            } catch (e: IllegalArgumentException) {
                finish(false, e.message ?: "Calibration readings were inconsistent")
            }
        }
    }

    /** Diagnostics: the light's own tint (ambient subtracted) at G/M 0 and both extremes. */
    fun startGmTest() {
        val start = _state.value.light ?: return
        val node = _state.value.selectedNode ?: return
        runTask("G/M response of ${node.name} at ${start.cct} K") {
            val ambient = lockAndReadAmbient(node.unicast, LightSetting(start.cct, 0)) ?: return@runTask
            for (g in listOf(0, node.model.limits.gmMin, node.model.limits.gmMax)) {
                rig.apply(node.unicast, LightSetting(start.cct, g))
                delay(SETTLE_MS + 300)
                val m = readLight(ambient) ?: return@runTask
                line("G/M %+4d → %s".format(g, fmt(m.first)))
            }
            rig.set(node.unicast, start)
            finish(true, "Done — ${node.name} restored")
        }
    }

    fun stopTask() {
        taskJob?.cancel()
    }

    fun dismissTask() = _state.update { it.copy(task = null) }

    private fun runTask(title: String, block: suspend () -> Unit) {
        if (!_state.value.canRunLightTask) return
        setLive(false)
        taskJob = viewModelScope.launch {
            _state.update { it.copy(task = TaskUi(title), measuring = true) }
            try {
                block()
            } catch (e: CancellationException) {
                finish(false, "Stopped")
                target()?.let { u -> runCatching { rig.restore(u) } }
                throw e
            } catch (e: Exception) {
                finish(false, "✗ ${e.message}")
            } finally {
                runCatching { meter.setExposureLock(false) }
                _state.update { it.copy(measuring = false) }
            }
        }
    }

    /**
     * Sets the light, lets exposure settle on the lit card and locks it, then reads
     * the card with the light off. Returns the ambient sample, or null if it failed.
     */
    private suspend fun lockAndReadAmbient(unicast: Int, setting: LightSetting): CameraMeter.Sample? {
        rig.apply(unicast, setting)
        delay(SETTLE_MS)
        if (meter.measure(4).warning == ExposureWarning.CLIPPED) {
            finish(false, "Card is overexposed even at minimum exposure — lower brightness or move back")
            return null
        }
        meter.setExposureLock(true)
        rig.lightOff(unicast)
        delay(SETTLE_MS)
        val ambient = meter.sample(6)
        rig.restore(unicast)
        line("Exposure locked; ambient measured with the light off")
        return ambient
    }

    /** The light's own colour (ambient subtracted), and its share of the card. */
    private suspend fun readLight(ambient: CameraMeter.Sample, calibrated: Boolean = true): Pair<CctDuv, Double>? {
        val on = meter.sample(4)
        if (on.warning == ExposureWarning.CLIPPED) {
            finish(false, "Card clipped with the light on — move the card or lower brightness")
            return null
        }
        val iso = AmbientSubtraction.isolate(on.rgb, ambient.rgb)
        if (iso.lightShare < AmbientSubtraction.MIN_SHARE) {
            finish(false, "The light is only %.0f%% of what's on the card — move it closer or raise brightness"
                .format(iso.lightShare * 100))
            return null
        }
        val r = meter.toReading(iso.lightRgb, on.level, ExposureWarning.NONE)
        _state.update { it.copy(reading = r) }
        return (if (calibrated) _state.value.corrected(r.xy) else r.cctDuv) to iso.lightShare
    }

    private fun line(t: String) = _state.update { s -> s.copy(task = s.task?.let { it.copy(lines = it.lines + t) }) }

    private fun finish(success: Boolean, result: String) = _state.update { s ->
        s.copy(task = (s.task ?: TaskUi("")).copy(running = false, success = success, result = result))
    }

    private fun fmt(c: CctDuv): String = "${formatKelvin(c.cct)} ${formatTint(c.duv)}"

    override fun onCleared() {
        meter.release()
    }
}

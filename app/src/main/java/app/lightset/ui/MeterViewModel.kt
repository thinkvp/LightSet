package app.lightset.ui

import android.app.Application
import android.graphics.SurfaceTexture
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lightset.LightRig
import app.lightset.LightSetApp
import app.lightset.ble.Im150
import app.lightset.ble.NanliteProtocol
import app.lightset.camera.CameraMeter
import app.lightset.camera.ExposureWarning
import app.lightset.camera.MeterSource
import app.lightset.camera.Reading
import app.lightset.color.AmbientSubtraction
import app.lightset.color.CalPoint
import app.lightset.color.Calibration
import app.lightset.color.CctDuv
import app.lightset.color.DialIn
import app.lightset.color.LightLimits
import app.lightset.color.LightSetting
import app.lightset.color.Spread
import app.lightset.color.Xy
import app.lightset.color.ambientSettled
import app.lightset.color.cctDuv
import app.lightset.color.delta
import app.lightset.color.mean
import app.lightset.color.xyToXyz
import app.lightset.color.xyzToXy
import app.lightset.data.Reference
import app.lightset.data.ReferenceStore
import app.lightset.mesh.MeshNode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DIAL_MAX_STEPS = 8
/** Time for auto-exposure to settle on the lit card before it's locked. */
private const val SETTLE_MS = 900L
/** Minimum wait after changing a light before the first reading. */
private const val MIN_SETTLE_MS = 300L
/** Longest wait for consecutive readings to agree; after that the last pair is used. */
private const val SETTLE_TIMEOUT_MS = 5000L

private const val KEY_SELECTED = "selected_light"
private const val KEY_PATCH = "patch"
private const val KEY_METER = "meter"

/** Below this share of the card's light, a calibration reading is flagged. */
private const val GOOD_SHARE = 0.5

/** Colour temps a trusted light is set to during calibration, at G/M 0. */
val CALIBRATION_TARGETS = listOf(2700.0, 3200.0, 4300.0, 5600.0, 6500.0)

/** What Match reads colour with. */
enum class ColourMeter { PHONE, IM150 }

/**
 * In-progress manual calibration: one raw reading slot per target, the IM150's reading
 * of the same light when one was connected, and the step being measured.
 */
data class CalSession(
    val readings: List<CctDuv?> = CALIBRATION_TARGETS.map { null },
    val references: List<CctDuv?> = CALIBRATION_TARGETS.map { null },
    val index: Int = 0,
    val error: String? = null,
) {
    val complete: Boolean get() = readings.all { it != null }
}

/**
 * How a light reading settled: the [spread] between the last two readings, whether
 * they agreed before the timeout, and how long it took after the light was changed.
 */
data class Settle(val spread: Spread, val settled: Boolean, val ms: Long)

/** A new calibration waiting for the user to save or discard it, with how each auto reading settled. */
data class CalReview(
    val calibration: Calibration,
    val warnings: List<String> = emptyList(),
    val settle: Map<Double, Settle> = emptyMap(),
)

/** A light reading with ambient subtracted, and the light's share of the card. */
private class LightRead(val c: CctDuv, val share: Double, val settle: Settle)

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
    /** The calibration last replaced or cleared, for undo. */
    val previousCalibration: Calibration? = null,
    val calSession: CalSession? = null,
    val calReview: CalReview? = null,
    /** Sampled box size, as a fraction of the frame. */
    val patch: Float = CameraMeter.DEFAULT_PATCH,
    val task: TaskUi? = null,
    /** Lights in the network, and the one used for matching and calibration. */
    val lights: List<MeshNode> = emptyList(),
    val selected: Int? = null,
    /** What the app has the selected light set to. */
    val light: LightRig.LightState? = null,
    val lightStatus: LightRig.Status = LightRig.Status.OFFLINE,
    val lightError: String? = null,
    val meter: ColourMeter = ColourMeter.PHONE,
    val im150Status: Im150.Status = Im150.Status.OFF,
    val im150Battery: Int? = null,
    val im150Error: String? = null,
) {
    val selectedNode: MeshNode? get() = lights.firstOrNull { it.unicast == selected }

    val active: Reference? get() = references.firstOrNull { it.id == activeId }
    val busy: Boolean get() = measuring || task?.running == true
    val im150Connected: Boolean get() = im150Status == Im150.Status.CONNECTED

    /** The chosen colour meter can take readings. */
    val meterReady: Boolean get() = if (meter == ColourMeter.IM150) im150Connected else cameraOpen

    /** The selected light can be used for a task: reachable, on, brightness chosen, nothing else running. */
    val lightReady: Boolean
        get() = !busy && !live && calSession == null &&
            lightStatus == LightRig.Status.ONLINE && light?.on == true && (light.brightness ?: 0) > 0

    /** A light task can start: meter ready, light reachable, on, brightness chosen. */
    val canRunLightTask: Boolean get() = meterReady && lightReady

    /** Auto-calibration calibrates the camera, so it needs the camera whichever meter is chosen. */
    val canAutoCalibrate: Boolean get() = cameraOpen && lightReady

    /** A reading's colour: the phone's with the stored calibration applied, the IM150's as it is. */
    fun corrected(xy: Xy, source: MeterSource): CctDuv =
        cctDuv(xy).let { if (source == MeterSource.IM150) it else calibration?.apply(it) ?: it }
}

class MeterViewModel(app: Application) : AndroidViewModel(app) {
    private val meter = CameraMeter(app)
    private val store = ReferenceStore(app)
    private val rig = (app as LightSetApp).rig
    private val im150 = (app as LightSetApp).im150

    private val _state = MutableStateFlow(
        MeterUiState(
            references = store.load(),
            activeId = store.activeId,
            calibration = store.calibration,
            previousCalibration = store.previousCalibration,
        )
    )
    val state: StateFlow<MeterUiState> = _state

    private var liveJob: Job? = null
    private var taskJob: Job? = null

    private val prefs = app.getSharedPreferences("match", android.content.Context.MODE_PRIVATE)
    /** Manual brightness/CCT/G/M changes not yet sent; sends run one at a time and skip stale values. */
    private val pending = MutableStateFlow<Map<Int, LightRig.LightState>>(emptyMap())
    private val selectedFlow = MutableStateFlow(prefs.getInt(KEY_SELECTED, -1).takeIf { it >= 0 })

    init {
        viewModelScope.launch {
            val shownStates = combine(rig.states, pending) { states, p -> states + p }
            combine(rig.nodes, shownStates, rig.status, rig.reachable, selectedFlow) { nodes, states, st, reach, sel ->
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
        viewModelScope.launch { im150.status.collect { st -> _state.update { it.copy(im150Status = st) } } }
        viewModelScope.launch { im150.battery.collect { b -> _state.update { it.copy(im150Battery = b) } } }
        viewModelScope.launch { im150.error.collect { e -> _state.update { it.copy(im150Error = e) } } }
        val chosen = runCatching { ColourMeter.valueOf(prefs.getString(KEY_METER, null)!!) }.getOrDefault(ColourMeter.PHONE)
        _state.update { it.copy(meter = chosen) }
        im150.need(chosen == ColourMeter.IM150)
        viewModelScope.launch {
            pending.collect { map ->
                for ((u, s) in map) {
                    try {
                        rig.set(u, s)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _state.update { it.copy(error = "Light: ${e.message}") }
                    }
                    // Done with this light unless a newer value arrived meanwhile.
                    pending.update { m -> if (m[u] == s) m - u else m }
                }
            }
        }
        prefs.getFloat(KEY_PATCH, CameraMeter.DEFAULT_PATCH).let { p ->
            _state.update { it.copy(patch = p) }
            viewModelScope.launch { meter.setPatch(p) }
        }
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

    // --- Meter choice --------------------------------------------------------

    fun setMeter(m: ColourMeter) {
        if (_state.value.busy || _state.value.meter == m) return
        setLive(false)
        prefs.edit().putString(KEY_METER, m.name).apply()
        _state.update { it.copy(meter = m, reading = null, error = null) }
        im150.need(m == ColourMeter.IM150)
    }

    fun retryIm150() = im150.retry()

    /**
     * Calibration calibrates the camera, so it needs the phone chosen (the camera only
     * runs then). The IM150 stays connected, to be the reference.
     */
    fun useCameraForCalibration() = setMeter(ColourMeter.PHONE)

    /** Stops connecting to the IM150 and goes back to the phone. */
    fun forgetIm150() {
        setMeter(ColourMeter.PHONE)
        im150.forget()
    }

    // --- Camera and readings -------------------------------------------------

    /** Resizes the box while dragging; the meter switches to it on [commitPatch]. */
    fun dragPatch(delta: Float) = _state.update {
        it.copy(patch = (it.patch + delta).coerceIn(CameraMeter.MIN_PATCH, CameraMeter.MAX_PATCH))
    }

    fun commitPatch() {
        val p = _state.value.patch
        prefs.edit().putFloat(KEY_PATCH, p).apply()
        viewModelScope.launch { runCatching { meter.setPatch(p) } }
    }

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

    /** A reading from the chosen meter: the camera's ([frames] averaged) or the IM150's next one. */
    private suspend fun takeReading(frames: Int): Reading? =
        if (_state.value.meter == ColourMeter.IM150) takeIm150Reading() else takeCameraReading(frames)

    private suspend fun takeIm150Reading(): Reading? {
        return try {
            im150Reading(im150.next()).also { r -> _state.update { it.copy(reading = r, error = null) } }
        } catch (e: TimeoutCancellationException) {
            _state.update { it.copy(error = "No reading from the IM150") }
            null
        }
    }

    private fun im150Reading(s: Im150.Sample): Reading =
        Reading(s.xy, cctDuv(s.xy), MeterSource.IM150, level = 0.0, warning = ExposureWarning.NONE,
            timestampMs = s.timeMs, lux = s.lux)

    private suspend fun takeCameraReading(frames: Int): Reading? {
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
        val shown = s.corrected(r.xy, r.source)
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

    /** Brightness from the slider (turns the light on); shown straight away, sent in the background. */
    fun setLightBrightness(percent: Int) {
        val u = target() ?: return
        val l = _state.value.light ?: return
        if (_state.value.task?.running == true) return
        pending.update { it + (u to l.copy(brightness = percent.coerceIn(0, 100), on = true)) }
    }

    /** Manual CCT and G/M from the sliders; shown straight away, sent in the background. */
    fun setLightColour(cct: Int, gm: Int) {
        val u = target() ?: return
        val l = _state.value.light ?: return
        if (_state.value.task?.running == true) return
        pending.update { it + (u to l.copy(mode = LightRig.Mode.CCT, cct = cct, gm = gm)) }
    }

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
        useCameraForCalibration()
        setLive(false)
        _state.update { it.copy(calSession = CalSession(), task = null) }
    }

    fun cancelCalibration() = _state.update { it.copy(calSession = null) }

    fun selectCalStep(index: Int) = _state.update { s ->
        s.copy(calSession = s.calSession?.copy(index = index, error = null))
    }

    /** Reads the card with the camera and, if one is connected, the light with the IM150. */
    fun measureCalStep() {
        val session = _state.value.calSession ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(measuring = true) }
            val r = takeCameraReading(frames = 6)
            val ref = if (r != null && _state.value.im150Connected) {
                try {
                    cctDuv(im150.next().xy)
                } catch (e: TimeoutCancellationException) {
                    null
                }
            } else null
            _state.update { s ->
                val cur = s.calSession ?: return@update s.copy(measuring = false)
                if (r == null) return@update s.copy(measuring = false)
                val readings = cur.readings.toMutableList().also { it[session.index] = r.cctDuv }
                val refs = cur.references.toMutableList().also { it[session.index] = ref }
                val next = readings.indexOfFirst { it == null }.takeIf { it >= 0 } ?: session.index
                val warn = when (r.warning) {
                    ExposureWarning.CLIPPED -> "That reading was overexposed — consider redoing it."
                    ExposureWarning.TOO_DARK -> "That reading was very dark — consider redoing it."
                    ExposureWarning.NONE -> null
                }
                s.copy(measuring = false,
                    calSession = cur.copy(readings = readings, references = refs, index = next, error = warn))
            }
        }
    }

    /**
     * Finishes manual calibration; the result still has to be reviewed and saved. Steps
     * the IM150 read use its reading as the truth; the rest assume the light's setting.
     */
    fun finishManualCalibration() {
        val session = _state.value.calSession ?: return
        val points = CALIBRATION_TARGETS.indices.mapNotNull { i ->
            val r = session.readings[i] ?: return@mapNotNull null
            val ref = session.references[i]
            CalPoint(ref?.cct ?: CALIBRATION_TARGETS[i], r, targetDuv = ref?.duv ?: 0.0, setKelvin = CALIBRATION_TARGETS[i])
        }
        val withIm150 = session.references.any { it != null }
        try {
            val cal = Calibration(points, source = if (withIm150) "manual + IM150" else "manual")
            _state.update { it.copy(calSession = null, calReview = CalReview(cal)) }
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(calSession = session.copy(error = e.message)) }
        }
    }

    /** Saves the reviewed calibration; the one it replaces is kept for undo. */
    fun saveReviewedCalibration() {
        val cal = _state.value.calReview?.calibration ?: return
        replaceCalibration(cal)
        _state.update { it.copy(calReview = null) }
    }

    fun discardReviewedCalibration() = _state.update { it.copy(calReview = null) }

    /** Clears the calibration; it can still be restored. */
    fun clearCalibration() = replaceCalibration(null)

    /** Swaps back to the calibration in use before the last save or clear. */
    fun restorePreviousCalibration() {
        val prev = _state.value.previousCalibration ?: return
        replaceCalibration(prev)
    }

    private fun replaceCalibration(cal: Calibration?) {
        val old = _state.value.calibration
        store.calibration = cal
        store.previousCalibration = old
        _state.update { it.copy(calibration = cal, previousCalibration = old) }
    }

    // --- Light tasks ---------------------------------------------------------

    /** Matches the test light to the active reference, adjusting CCT and G/M only. */
    fun startDialIn() {
        val s = _state.value
        val ref = s.active ?: return
        val brightness = s.light?.brightness ?: return
        val node = s.selectedNode ?: return
        val target = s.corrected(ref.xy, ref.source)
        val probe = Probe(im150 = s.meter == ColourMeter.IM150)
        runTask("Matching ${node.name} to ${ref.name}") {
            val d = DialIn(target, node.model.limits)
            var setting = d.initial()
            line("Target ${fmt(target)} · brightness $brightness% · reading with the ${probe.label}")
            if ((ref.source == MeterSource.IM150) != probe.im150) {
                line("Note: the reference was measured with the ${if (probe.im150) "phone" else "IM150"}")
            }
            val ambient = lockAndReadAmbient(node.unicast, setting, listOf(probe))?.single() ?: return@runTask
            for (i in 1..DIAL_MAX_STEPS) {
                val m = readLight(probe, ambient) ?: return@runTask
                val dd = delta(m.c, target)
                line("$i  ${setting.cct} K, G/M %+d → %s  (%+.0f mired, tint %+.1f)  %s"
                    .format(setting.gm, fmt(m.c), dd.mired, dd.duv * 1000, fmtSettle(m.settle)))
                when (val step = d.next(m.c)) {
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
     * Calibrates the camera against the test light: the app steps the light through
     * the calibration colour temps at G/M 0 and records what the sensor model reads,
     * with ambient subtracted, so it works with room light on. With the IM150
     * connected, its reading of each step is the truth instead of the light's setting.
     */
    fun startAutoCalibration() {
        val s = _state.value
        val start = s.light ?: return
        val node = s.selectedNode ?: return
        val camera = Probe(im150 = false)
        val reference = if (s.im150Connected) Probe(im150 = true) else null
        _state.update { it.copy(calReview = null) }
        runTask("Calibrating meter with ${node.name}", allowed = s.canAutoCalibrate) {
            line("Hold the card in the light; it will step through ${CALIBRATION_TARGETS.size} colour temps")
            if (reference != null) line("The IM150's readings are the targets: keep it beside the card, facing the light")
            val ambient = lockAndReadAmbient(node.unicast, LightSetting(5600, 0), listOfNotNull(camera, reference))
                ?: return@runTask
            val points = mutableListOf<CalPoint>()
            val settle = mutableMapOf<Double, Settle>()
            var minShare = 1.0
            for (k in CALIBRATION_TARGETS) {
                rig.apply(node.unicast, LightSetting(k.toInt(), 0))
                val m = readLight(camera, ambient[0], calibrated = false) ?: return@runTask
                val ref = reference?.let { readLight(it, ambient[1]) ?: return@runTask }
                points += CalPoint(ref?.c?.cct ?: k, m.c, targetDuv = ref?.c?.duv ?: 0.0, setKelvin = k)
                settle[k] = m.settle
                minShare = minOf(minShare, m.share)
                line("%,d K → read %s%s  (light %.0f%% of the card)  %s".format(
                    k.toInt(), fmt(m.c), ref?.let { ", IM150 ${fmt(it.c)}" } ?: "", m.share * 100, fmtSettle(m.settle)))
            }
            rig.set(node.unicast, start)
            try {
                val cal = Calibration(points, source = node.name + if (reference != null) " + IM150" else "")
                val warnings = buildList {
                    // Ambient is subtracted, but its noise isn't: the more of the card's
                    // light comes from the room, the less the readings can be trusted.
                    if (minShare < GOOD_SHARE) add(
                        "Room light was up to %.0f%% of the card. Brighter light or a darker room gives a cleaner result."
                            .format((1 - minShare) * 100)
                    )
                    val unsettled = settle.filterValues { !it.settled }.keys
                    if (unsettled.isNotEmpty()) add(
                        "Readings at ${unsettled.joinToString { formatKelvin(it) }} were still changing after " +
                            "${SETTLE_TIMEOUT_MS / 1000} s. Keep the phone and card still, or check the light."
                    )
                }
                _state.update { it.copy(calReview = CalReview(cal, warnings, settle)) }
                finish(true, "Done, ${node.name} restored. Review the new calibration to save it")
            } catch (e: IllegalArgumentException) {
                finish(false, e.message ?: "Calibration readings were inconsistent")
            }
        }
    }

    /** Diagnostics: the light's own tint (ambient subtracted) at G/M 0 and both extremes. */
    fun startGmTest() {
        val start = _state.value.light ?: return
        val node = _state.value.selectedNode ?: return
        val probe = Probe(im150 = _state.value.meter == ColourMeter.IM150)
        runTask("G/M response of ${node.name} at ${start.cct} K") {
            val ambient = lockAndReadAmbient(node.unicast, LightSetting(start.cct, 0), listOf(probe))?.single()
                ?: return@runTask
            for (g in listOf(0, node.model.limits.gmMin, node.model.limits.gmMax)) {
                rig.apply(node.unicast, LightSetting(start.cct, g))
                val m = readLight(probe, ambient) ?: return@runTask
                line("G/M %+4d → %s  %s".format(g, fmt(m.c), fmtSettle(m.settle)))
            }
            rig.set(node.unicast, start)
            finish(true, "Done — ${node.name} restored")
        }
    }

    fun stopTask() {
        taskJob?.cancel()
    }

    fun dismissTask() = _state.update { it.copy(task = null) }

    private fun runTask(title: String, allowed: Boolean = _state.value.canRunLightTask, block: suspend () -> Unit) {
        if (!allowed) return
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

    /** A linear sample of the light: the camera's RGB of the card, or the IM150's XYZ (Y in lux). */
    private class Tri(val v: DoubleArray, val level: Double, val clipped: Boolean)

    /** What a light task reads the light with: the camera on the card, or the IM150. */
    private inner class Probe(val im150: Boolean) {
        val label: String get() = if (im150) "IM150" else "camera"

        /** Camera: exposure is settled on the lit card, then locked so samples can be subtracted. */
        suspend fun prepare(): Boolean {
            if (im150) return true
            if (meter.measure(4).warning == ExposureWarning.CLIPPED) {
                finish(false, "Card is overexposed even at minimum exposure — lower brightness or move back")
                return false
            }
            meter.setExposureLock(true)
            return true
        }

        suspend fun sample(): Tri {
            if (!im150) return meter.sample(4).let { Tri(it.rgb, it.level, it.warning == ExposureWarning.CLIPPED) }
            val s = try {
                this@MeterViewModel.im150.next()
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("No reading from the IM150")
            }
            val xyz = xyToXyz(s.xy)
            return Tri(DoubleArray(3) { xyz[it] * s.lux }, 0.0, false)
        }

        /** The reading for [light], the light alone, out of the [on] sample. */
        fun reading(light: DoubleArray, on: Tri): Reading {
            if (!im150) return meter.toReading(light, on.level, ExposureWarning.NONE)
            val xy = xyzToXy(light[0], light[1], light[2])
            return Reading(xy, cctDuv(xy), MeterSource.IM150, level = 0.0, warning = ExposureWarning.NONE, lux = light[1])
        }
    }

    /**
     * Sets the light and gets each probe ready (the camera's exposure locked on the lit
     * card), then reads each with the light off. Returns their ambient samples in the
     * same order, or null if it failed.
     */
    private suspend fun lockAndReadAmbient(unicast: Int, setting: LightSetting, probes: List<Probe>): List<Tri>? {
        rig.apply(unicast, setting)
        delay(SETTLE_MS)
        for (p in probes) if (!p.prepare()) return null
        val lit = probes.map { it.sample() }
        rig.lightOff(unicast)
        val ambient = probes.mapIndexed { i, p -> readAmbient(p, lit[i]) }
        rig.restore(unicast)
        line(if (probes.any { !it.im150 }) "Exposure locked; ambient measured with the light off"
            else "Ambient measured with the light off")
        return ambient
    }

    /**
     * Samples with the light off until two samples agree (so the light has finished
     * fading out), or until the timeout, and returns their mean.
     */
    private suspend fun readAmbient(probe: Probe, lit: Tri): Tri {
        val start = SystemClock.elapsedRealtime()
        delay(MIN_SETTLE_MS)
        var prev = probe.sample()
        while (true) {
            val cur = probe.sample()
            val settled = ambientSettled(prev.v, cur.v, lit.v)
            if (settled || SystemClock.elapsedRealtime() - start >= SETTLE_TIMEOUT_MS) {
                if (!settled) line("Ambient (${probe.label}) was still changing after ${SETTLE_TIMEOUT_MS / 1000} s")
                return Tri(DoubleArray(3) { (prev.v[it] + cur.v[it]) / 2 }, maxOf(prev.level, cur.level), cur.clipped)
            }
            prev = cur
        }
    }

    /**
     * The light's own colour (ambient subtracted), read after a light change once two
     * consecutive readings agree within [Spread.SETTLED], or at the timeout. Returns
     * the mean of the last two. A reading that fails (clipped, light too weak) counts
     * as not yet settled, since the light may still be fading in; at the timeout the
     * task fails with that reason.
     */
    private suspend fun readLight(probe: Probe, ambient: Tri, calibrated: Boolean = true): LightRead? {
        val start = SystemClock.elapsedRealtime()
        delay(MIN_SETTLE_MS)
        var prev: Pair<CctDuv, Double>? = null
        while (true) {
            val cur = readLightOnce(probe, ambient, calibrated)
            val ms = SystemClock.elapsedRealtime() - start
            val ok = cur.getOrNull()
            if (ok == null) {
                if (ms >= SETTLE_TIMEOUT_MS) {
                    finish(false, cur.exceptionOrNull()?.message ?: "Reading failed")
                    return null
                }
                prev = null
                continue
            }
            if (prev != null) {
                val spread = Spread.between(prev.first, ok.first)
                val settled = spread.within(Spread.SETTLED)
                if (settled || ms >= SETTLE_TIMEOUT_MS) {
                    return LightRead(mean(prev.first, ok.first), minOf(prev.second, ok.second), Settle(spread, settled, ms))
                }
            }
            prev = ok
        }
    }

    /** One reading of the light: its colour and its share of the total, or why it failed. */
    private suspend fun readLightOnce(probe: Probe, ambient: Tri, calibrated: Boolean): Result<Pair<CctDuv, Double>> {
        val on = probe.sample()
        if (on.clipped) {
            return Result.failure(IllegalStateException("Card clipped with the light on — move the card or lower brightness"))
        }
        val iso = AmbientSubtraction.isolate(on.v, ambient.v)
        if (iso.lightShare < AmbientSubtraction.MIN_SHARE) {
            return Result.failure(IllegalStateException(
                "The light is only %.0f%% of what the %s sees — move it closer or raise brightness"
                    .format(iso.lightShare * 100, probe.label)
            ))
        }
        val r = probe.reading(iso.lightRgb, on)
        _state.update { it.copy(reading = r) }
        return Result.success((if (calibrated) _state.value.corrected(r.xy, r.source) else r.cctDuv) to iso.lightShare)
    }

    private fun fmtSettle(s: Settle): String =
        if (s.settled) "settled in %.1f s".format(s.ms / 1000.0)
        else "still changing (±%.1f mired, ±%.1f tint)".format(s.spread.mired, s.spread.duv * 1000)

    private fun line(t: String) = _state.update { s -> s.copy(task = s.task?.let { it.copy(lines = it.lines + t) }) }

    private fun finish(success: Boolean, result: String) = _state.update { s ->
        s.copy(task = (s.task ?: TaskUi("")).copy(running = false, success = success, result = result))
    }

    private fun fmt(c: CctDuv): String = "${formatKelvin(c.cct)} ${formatTint(c.duv)}"

    override fun onCleared() {
        meter.release()
    }
}

package app.lightset.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lightset.LightSetApp
import app.lightset.ble.Im150
import app.lightset.exposure.Exposure
import app.lightset.exposure.ExposureParam
import app.lightset.exposure.ExposureSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * The exposure calculator: [settings] are the user's values, [locked] the ones to keep.
 * The light comes from the IM150 live, or is held (or set by hand) when LIGHT is locked.
 */
data class ExposureUiState(
    val settings: ExposureSettings = ExposureSettings(),
    val locked: Set<ExposureParam> = setOf(ExposureParam.SHUTTER),
    val heldLux: Double? = null,
    val liveLux: Double? = null,
    val im150Status: Im150.Status = Im150.Status.OFF,
    val expanded: Boolean = false,
) {
    val lux: Double? get() = if (ExposureParam.LIGHT in locked) heldLux else liveLux

    /** The worked-out settings and how far off they leave the exposure, once there's a light value. */
    val result: Exposure.Result? get() = lux?.takeIf { it > 0 }?.let { Exposure.solve(it, settings, locked) }

    /** What to show for each setting: worked out where unlocked, the user's where locked. */
    val shown: ExposureSettings get() = result?.settings ?: settings
}

class ExposureViewModel(app: Application) : AndroidViewModel(app) {
    private val im150 = (app as LightSetApp).im150
    private val prefs = app.getSharedPreferences("exposure", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<ExposureUiState> = _state

    init {
        viewModelScope.launch { im150.sample.collect { s -> _state.update { it.copy(liveLux = s?.lux) } } }
        viewModelScope.launch { im150.status.collect { st -> _state.update { it.copy(im150Status = st) } } }
    }

    fun setExpanded(open: Boolean) = change { it.copy(expanded = open) }

    /**
     * Steps a setting by a third of a stop (or to the next frame rate or angle) from
     * what's shown, and locks it, since a value picked by hand is one to keep.
     */
    fun step(p: ExposureParam, dir: Int) = change { s ->
        val shown = s.shown
        when (p) {
            ExposureParam.LIGHT -> {
                val from = s.lux ?: Exposure.luxForEv100(7.0)
                s.copy(heldLux = from * 2.0.pow(dir / 3.0), locked = s.locked + p)
            }
            ExposureParam.APERTURE ->
                s.copy(settings = s.settings.copy(fNumber = Exposure.step(Exposure.APERTURES, shown.fNumber, dir)), locked = s.locked + p)
            ExposureParam.ISO ->
                s.copy(settings = s.settings.copy(iso = Exposure.step(Exposure.ISOS, shown.iso, dir)), locked = s.locked + p)
            ExposureParam.SHUTTER ->
                s.copy(settings = s.settings.copy(angle = Exposure.step(Exposure.ANGLES, shown.angle, dir)), locked = s.locked + p)
            ExposureParam.ND ->
                s.copy(settings = s.settings.copy(ndStops = Exposure.step(Exposure.ND_STOPS, shown.ndStops, dir)), locked = s.locked + p)
        }
    }

    /** Locking keeps the value shown now (the reading, for LIGHT); unlocking lets it be worked out again. */
    fun toggleLock(p: ExposureParam) = change { s ->
        if (p in s.locked) return@change s.copy(locked = s.locked - p)
        val shown = s.shown
        when (p) {
            ExposureParam.LIGHT -> s.copy(heldLux = s.lux, locked = if (s.lux != null) s.locked + p else s.locked)
            else -> s.copy(settings = shown.copy(bias = s.settings.bias, fps = s.settings.fps), locked = s.locked + p)
        }
    }

    fun stepFps(dir: Int) = change { s -> s.copy(settings = s.settings.copy(fps = Exposure.step(Exposure.FRAME_RATES, s.settings.fps, dir))) }

    fun stepBias(dir: Int) = change { s -> s.copy(settings = s.settings.copy(bias = Exposure.step(Exposure.BIASES, s.settings.bias, dir))) }

    private fun change(f: (ExposureUiState) -> ExposureUiState) {
        _state.update(f)
        save(_state.value)
    }

    private fun load(): ExposureUiState {
        // Stored as floats, so snap back onto the exact list values.
        val d = ExposureSettings()
        fun read(key: String, list: List<Double>, default: Double) =
            Exposure.step(list, prefs.getFloat(key, default.toFloat()).toDouble(), 0)
        val settings = ExposureSettings(
            fNumber = read("f", Exposure.APERTURES, d.fNumber),
            iso = read("iso", Exposure.ISOS, d.iso),
            angle = read("angle", Exposure.ANGLES, d.angle),
            fps = read("fps", Exposure.FRAME_RATES, d.fps),
            ndStops = read("nd", Exposure.ND_STOPS, d.ndStops),
            bias = read("bias", Exposure.BIASES, d.bias),
        )
        val locked = prefs.getStringSet("locked", null)
            ?.mapNotNull { runCatching { ExposureParam.valueOf(it) }.getOrNull() }?.toSet()
            ?: ExposureUiState().locked
        val held = prefs.getFloat("lux", -1f).takeIf { it > 0 }?.toDouble()
        return ExposureUiState(settings = settings, locked = locked, heldLux = held, expanded = prefs.getBoolean("open", false))
    }

    private fun save(s: ExposureUiState) {
        prefs.edit()
            .putFloat("f", s.settings.fNumber.toFloat())
            .putFloat("iso", s.settings.iso.toFloat())
            .putFloat("angle", s.settings.angle.toFloat())
            .putFloat("fps", s.settings.fps.toFloat())
            .putFloat("nd", s.settings.ndStops.toFloat())
            .putFloat("bias", s.settings.bias.toFloat())
            .putStringSet("locked", s.locked.map { it.name }.toSet())
            .putFloat("lux", (s.heldLux ?: -1.0).toFloat())
            .putBoolean("open", s.expanded)
            .apply()
    }
}

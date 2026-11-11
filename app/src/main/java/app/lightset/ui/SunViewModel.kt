package app.lightset.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lightset.sun.Forecast
import app.lightset.sun.HourRow
import app.lightset.sun.OpenMeteo
import app.lightset.sun.Place
import app.lightset.sun.PlaceSource
import app.lightset.sun.Sun
import app.lightset.sun.SunDay
import app.lightset.sun.SunHours
import app.lightset.sun.SunPos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

enum class SunMode { CAMERA, DAY, MAP }

data class SunUiState(
    val mode: SunMode = SunMode.CAMERA,
    val hasPermission: Boolean = false,
    /** Where the phone is. */
    val place: Place? = null,
    val locating: Boolean = false,
    val locationError: String? = null,
    /** A place picked on the map, which Day and Map show instead of the phone's. */
    val pin: Place? = null,
    val date: LocalDate = LocalDate.now(),
    val day: SunDay? = null,
    val hours: List<HourRow> = emptyList(),
    /** Where the sun is every [CURVE_STEP_MIN] minutes from midnight: the camera's and map's path. */
    val track: List<SunPos> = emptyList(),
    /** The sun's height from [track], for the day graph. */
    val curve: List<Double> = emptyList(),
    /** The forecast for the phone's place (kept between runs) and for the pin. */
    val forecast: Forecast? = null,
    val pinForecast: Forecast? = null,
    val fetching: Boolean = false,
    val pinFetching: Boolean = false,
    val forecastError: String? = null,
    val pinForecastError: String? = null,
    val searching: Boolean = false,
    val searchError: String? = null,
    val now: ZonedDateTime = ZonedDateTime.now(),
    /**
     * Degrees to add to the compass, from lining up on the sun. Kept until the app closes:
     * compass error changes with what's nearby, so an old one is as likely wrong as right.
     */
    val headingCorrection: Double = 0.0,
    val aligned: Boolean = false,
) {
    /** The camera always shows where the phone is; Day and Map show the pin if there is one. */
    val usingPin: Boolean get() = pin != null && mode != SunMode.CAMERA
    val shownPlace: Place? get() = if (usingPin) pin else place
    val shownForecast: Forecast? get() = if (usingPin) pinForecast else forecast
    val shownFetching: Boolean get() = if (usingPin) pinFetching else fetching
    val shownForecastError: String? get() = if (usingPin) pinForecastError else forecastError

    /** Times are shown in the shown place's time zone (the pin's comes with its forecast). */
    val zone: ZoneId get() = (if (usingPin) pinForecast?.zone else null) ?: ZoneId.systemDefault()

    val today: LocalDate get() = now.withZoneSameInstant(zone).toLocalDate()

    /** Whether the forecast reaches [date] at all. */
    val dateInForecast: Boolean
        get() {
            val f = shownForecast ?: return false
            val start = f.start ?: return false
            val end = f.end ?: return false
            val dayStart = date.atStartOfDay(zone).toInstant()
            return dayStart.plus(Duration.ofDays(1)).isAfter(start) && !dayStart.isAfter(end)
        }

    companion object {
        const val CURVE_STEP_MIN = 10L
    }
}

/**
 * The Sun tab: sun times and an hourly forecast for where the phone is, or for a place
 * picked on the map. The phone's location comes first from what was saved last time, so
 * the tab fills in at once (and offline), then from a fresh fix. Forecasts are fetched
 * again each time the tab is opened or the app comes back, unless they're under
 * [FRESH] old (so switching tabs doesn't refetch), and when the phone moves more than 5 km.
 */
class SunViewModel(app: Application) : AndroidViewModel(app) {
    private val source = PlaceSource(app)
    private val _state = MutableStateFlow(
        SunUiState(
            hasPermission = source.hasPermission(),
            place = source.saved(),
            forecast = source.savedForecast(),
        ),
    )
    val state: StateFlow<SunUiState> = _state

    private var computeJob: Job? = null
    private var locateJob: Job? = null
    private var fetchJob: Job? = null
    private var pinJob: Job? = null

    init {
        recompute()
        // Keeps "now" (the graph's marker and the highlighted hour) current.
        viewModelScope.launch {
            while (true) {
                delay(60_000 - System.currentTimeMillis() % 60_000)
                _state.update { it.copy(now = ZonedDateTime.now()) }
            }
        }
    }

    /** The tab was opened: find the phone and fetch forecasts if they're out of date. */
    fun onShown() {
        val granted = source.hasPermission()
        _state.update { it.copy(hasPermission = granted, now = ZonedDateTime.now()) }
        if (_state.value.pin != null) fetchPin()
        if (!granted) return
        val place = _state.value.place
        if (place == null || System.currentTimeMillis() - place.time > 15 * 60_000) locate() else fetchIfStale()
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(hasPermission = granted) }
        if (granted) locate()
    }

    /** Fetches the shown place's forecast again (finding the phone again first, if it's the phone's). */
    fun refresh() {
        if (_state.value.usingPin) fetchPin(force = true)
        else if (source.hasPermission()) locate(force = true)
    }

    fun setDate(date: LocalDate) {
        _state.update { it.copy(date = date) }
        recompute()
    }

    fun shiftDate(days: Long) = setDate(_state.value.date.plusDays(days))

    fun setMode(mode: SunMode) {
        val before = _state.value.shownPlace
        _state.update { it.copy(mode = mode) }
        if (_state.value.shownPlace != before) recompute()
    }

    /** Picks a place on the map; its name comes from the geocoder unless one's given. */
    fun setPin(lat: Double, lon: Double, name: String? = null) {
        val pin = Place(lat, lon, name, System.currentTimeMillis())
        _state.update { s ->
            // Keep the pin's forecast if it's for nearly the same spot.
            val keep = s.pinForecast?.takeIf { pin.distanceKm(it.lat, it.lon) <= 5.0 }
            s.copy(pin = pin, pinForecast = keep, pinForecastError = null, searchError = null)
        }
        recompute()
        fetchPin()
        if (name == null) {
            viewModelScope.launch {
                val found = source.placeName(lat, lon) ?: return@launch
                _state.update { if (it.pin == pin) it.copy(pin = pin.copy(name = found)) else it }
            }
        }
    }

    /** Back to the phone's own place. */
    fun clearPin() {
        pinJob?.cancel()
        _state.update { it.copy(pin = null, pinForecast = null, pinFetching = false, pinForecastError = null) }
        recompute()
    }

    /** Finds a place by name and drops the pin there. */
    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true, searchError = null) }
            val found = source.search(query.trim())
            _state.update { it.copy(searching = false, searchError = if (found == null) "Couldn't find \"${query.trim()}\"" else null) }
            found?.let { setPin(it.lat, it.lon, it.name) }
        }
    }

    /**
     * The sun was tapped at [tappedAzimuth] (true, as the compass sees it) while it was really
     * at [sunAzimuth]: corrects the compass by the difference from now on.
     */
    fun alignOnSun(tappedAzimuth: Double, sunAzimuth: Double) {
        val diff = ((sunAzimuth - tappedAzimuth + 540.0) % 360.0) - 180.0
        _state.update { it.copy(headingCorrection = it.headingCorrection + diff, aligned = true) }
    }

    fun clearAlignment() = _state.update { it.copy(headingCorrection = 0.0, aligned = false) }

    private fun locate(force: Boolean = false) {
        if (locateJob?.isActive == true) return
        locateJob = viewModelScope.launch {
            if (!source.locationOn()) {
                _state.update { it.copy(locationError = "Location is off on the phone") }
                fetchIfStale(force)
                return@launch
            }
            _state.update { it.copy(locating = true, locationError = null) }
            val place = source.locate()
            _state.update {
                it.copy(
                    locating = false,
                    place = place ?: it.place,
                    locationError = if (place == null) "Couldn't find the phone's location" else null,
                )
            }
            recompute()
            fetchIfStale(force)
        }
    }

    private fun fetchIfStale(force: Boolean = false) {
        val s = _state.value
        val place = s.place ?: return
        val f = s.forecast
        val stale = f == null ||
            Duration.between(f.fetchedAt, Instant.now()) > FRESH ||
            place.distanceKm(f.lat, f.lon) > 5.0
        if (!force && !stale) return
        if (fetchJob?.isActive == true) return
        fetchJob = viewModelScope.launch {
            _state.update { it.copy(fetching = true, forecastError = null) }
            val result = runCatching { OpenMeteo.fetch(place.lat, place.lon) }
            result.onSuccess { forecast ->
                source.saveForecast(forecast)
                _state.update { it.copy(fetching = false, forecast = forecast) }
                recompute()
            }.onFailure { e ->
                _state.update { it.copy(fetching = false, forecastError = forecastFailed(e)) }
            }
        }
    }

    /** The pin's forecast, which also gives its time zone. Not saved: pins are for scouting. */
    private fun fetchPin(force: Boolean = false) {
        val s = _state.value
        val pin = s.pin ?: return
        val f = s.pinForecast
        val stale = f == null || Duration.between(f.fetchedAt, Instant.now()) > FRESH
        if (!force && !stale) return
        pinJob?.cancel()
        pinJob = viewModelScope.launch {
            _state.update { it.copy(pinFetching = true, pinForecastError = null) }
            val result = runCatching { OpenMeteo.fetch(pin.lat, pin.lon) }
            if (_state.value.pin?.let { it.lat == pin.lat && it.lon == pin.lon } != true) return@launch
            result.onSuccess { forecast ->
                _state.update { it.copy(pinFetching = false, pinForecast = forecast) }
                recompute()
            }.onFailure { e ->
                _state.update { it.copy(pinFetching = false, pinForecastError = forecastFailed(e)) }
            }
        }
    }

    private fun forecastFailed(e: Throwable) = "Forecast unavailable" + (e.message?.let { ": $it" } ?: "")

    /** Works out the day's sun times and hours for the shown place, date and forecast. */
    private fun recompute() {
        val s = _state.value
        val place = s.shownPlace ?: return
        computeJob?.cancel()
        computeJob = viewModelScope.launch {
            // A forecast for somewhere else (more than 5 km away) isn't shown against this place.
            val forecast = s.shownForecast?.takeIf { place.distanceKm(it.lat, it.lon) <= 5.0 }
            val zone = s.zone
            val (day, hours, track) = withContext(Dispatchers.Default) {
                Triple(
                    Sun.day(place.lat, place.lon, s.date, zone),
                    SunHours.hours(place.lat, place.lon, s.date, zone, forecast),
                    Sun.track(place.lat, place.lon, s.date, zone, SunUiState.CURVE_STEP_MIN),
                )
            }
            _state.update {
                val same = it.shownPlace?.let { p -> p.lat == place.lat && p.lon == place.lon } == true
                if (it.date == s.date && same && it.zone == zone) {
                    it.copy(day = day, hours = hours, track = track, curve = track.map { p -> p.altitude })
                } else it
            }
        }
    }

    private companion object {
        /** A forecast this new isn't fetched again on opening the tab. */
        val FRESH: Duration = Duration.ofMinutes(10)
    }
}

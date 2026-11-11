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

data class SunUiState(
    val hasPermission: Boolean = false,
    val place: Place? = null,
    val locating: Boolean = false,
    val locationError: String? = null,
    val date: LocalDate = LocalDate.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val day: SunDay? = null,
    val hours: List<HourRow> = emptyList(),
    /** The sun's height every [CURVE_STEP_MIN] minutes from midnight, for the day graph. */
    val curve: List<Double> = emptyList(),
    val forecast: Forecast? = null,
    val fetching: Boolean = false,
    val forecastError: String? = null,
    val now: ZonedDateTime = ZonedDateTime.now(),
) {
    val today: LocalDate get() = now.toLocalDate()

    /** Whether the forecast reaches [date] at all. */
    val dateInForecast: Boolean
        get() {
            val f = forecast ?: return false
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
 * The Sun tab: sun times and an hourly forecast for where the phone is. Location comes
 * first from what was saved last time, so the tab fills in at once (and offline), then
 * from a fresh fix; the forecast is fetched again once it's an hour old or the phone has
 * moved more than 5 km.
 */
class SunViewModel(app: Application) : AndroidViewModel(app) {
    private val source = PlaceSource(app)
    private val _state = MutableStateFlow(
        SunUiState(hasPermission = source.hasPermission(), place = source.saved(), forecast = source.savedForecast()),
    )
    val state: StateFlow<SunUiState> = _state

    private var computeJob: Job? = null
    private var locateJob: Job? = null
    private var fetchJob: Job? = null

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

    /** The tab was opened: find the phone and fetch the forecast if they're out of date. */
    fun onShown() {
        val granted = source.hasPermission()
        _state.update { it.copy(hasPermission = granted, now = ZonedDateTime.now()) }
        if (!granted) return
        val place = _state.value.place
        if (place == null || System.currentTimeMillis() - place.time > 15 * 60_000) locate() else fetchIfStale()
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(hasPermission = granted) }
        if (granted) locate()
    }

    /** Finds the phone again and fetches a new forecast. */
    fun refresh() {
        if (!source.hasPermission()) return
        locate(force = true)
    }

    fun setDate(date: LocalDate) {
        _state.update { it.copy(date = date) }
        recompute()
    }

    fun shiftDate(days: Long) = setDate(_state.value.date.plusDays(days))

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
            Duration.between(f.fetchedAt, Instant.now()) > Duration.ofHours(1) ||
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
                _state.update {
                    it.copy(fetching = false, forecastError = "Forecast unavailable" + (e.message?.let { m -> ": $m" } ?: ""))
                }
            }
        }
    }

    /** Works out the day's sun times and hours for the current place, date and forecast. */
    private fun recompute() {
        val s = _state.value
        val place = s.place ?: return
        computeJob?.cancel()
        computeJob = viewModelScope.launch {
            // A forecast for somewhere else (more than 5 km away) isn't shown against this place.
            val forecast = s.forecast?.takeIf { place.distanceKm(it.lat, it.lon) <= 5.0 }
            val (day, hours, curve) = withContext(Dispatchers.Default) {
                Triple(
                    Sun.day(place.lat, place.lon, s.date, s.zone),
                    SunHours.hours(place.lat, place.lon, s.date, s.zone, forecast),
                    Sun.altitudes(place.lat, place.lon, s.date, s.zone, SunUiState.CURVE_STEP_MIN),
                )
            }
            _state.update { if (it.date == s.date && it.place == place) it.copy(day = day, hours = hours, curve = curve) else it }
        }
    }
}

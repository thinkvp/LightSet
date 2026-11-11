package app.lightset.sun

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.pow

/** What the sky is doing in an hour, as far as light goes. */
enum class Sky(val label: String) {
    /** Hard, direct sun. */
    SUN("Sun"),
    /** Direct sun, but filtered: softer shadows, and it may come and go. */
    HAZY("Hazy sun"),
    /** Little or no direct sun. */
    OVERCAST("Overcast"),
    // With the sun down or too low to judge by its light:
    CLEAR("Clear"),
    PARTLY("Part cloud"),
    CLOUDY("Cloudy"),
    // From the forecast's weather code:
    FOG("Fog"),
    DRIZZLE("Drizzle"),
    RAIN("Rain"),
    SNOW("Snow"),
    STORM("Thunder"),
}

/** One hour of the day: where the sun is, and the forecast if there is one. */
data class HourRow(
    val time: ZonedDateTime,
    val sun: SunPos,
    val phase: Phase,
    val weather: HourWeather?,
    /** Direct sunlight centred on [time] (W/m²), and what a clear sky would give then. */
    val dni: Double?,
    val clearSkyDni: Double,
    val sky: Sky?,
)

object SunHours {
    /** Below this a clear sky's direct sunlight is too weak (sun too low) to judge cloud by. */
    private const val MIN_CLEAR_DNI = 50.0

    /**
     * Direct sunlight under a clear sky with the sun at [altitude]°: Meinel's model with
     * Kasten and Young's air mass. About 950 W/m² overhead and 430 at 10°.
     */
    fun clearSkyDni(altitude: Double): Double {
        if (altitude <= 0) return 0.0
        val zenith = 90.0 - altitude
        val airMass = 1.0 / (cos(Math.toRadians(zenith)) + 0.50572 * (96.07995 - zenith).pow(-1.6364))
        return 1353.0 * 0.7.pow(airMass.pow(0.678))
    }

    fun skyFromCode(code: Int?): Sky? = when (code) {
        null -> null
        95, 96, 99 -> Sky.STORM
        in 71..77, 85, 86 -> Sky.SNOW
        in 61..67, in 80..82 -> Sky.RAIN
        in 51..57 -> Sky.DRIZZLE
        45, 48 -> Sky.FOG
        else -> null
    }

    /**
     * The sky for an hour. Rain, snow and fog come from the weather code. Otherwise, with
     * the sun up, it's judged by how much of a clear sky's direct sunlight gets through:
     * thin high cloud can read as 80% cover and still cast hard shadows. With the sun down
     * or very low, it falls back to cloud cover.
     */
    fun sky(weather: HourWeather?, dni: Double?, clearSkyDni: Double): Sky? {
        weather ?: return null
        skyFromCode(weather.code)?.let { return it }
        if (dni != null && clearSkyDni >= MIN_CLEAR_DNI) {
            val ratio = dni / clearSkyDni
            return when {
                ratio >= 0.6 -> Sky.SUN
                ratio >= 0.2 -> Sky.HAZY
                else -> Sky.OVERCAST
            }
        }
        val cloud = weather.cloudCover ?: return null
        val sunUp = clearSkyDni >= MIN_CLEAR_DNI
        return when {
            cloud < 25 -> if (sunUp) Sky.SUN else Sky.CLEAR
            cloud < 75 -> if (sunUp) Sky.HAZY else Sky.PARTLY
            else -> if (sunUp) Sky.OVERCAST else Sky.CLOUDY
        }
    }

    /** Each hour of [date] in [zone] (23 or 25 on a clock-change day). */
    fun hours(lat: Double, lon: Double, date: LocalDate, zone: ZoneId, forecast: Forecast?): List<HourRow> {
        val start = date.atStartOfDay(zone)
        return generateSequence(start) { it.plusHours(1) }
            .takeWhile { it.toLocalDate() == date }
            .map { t -> row(lat, lon, t, forecast) }
            .toList()
    }

    private fun row(lat: Double, lon: Double, t: ZonedDateTime, forecast: Forecast?): HourRow {
        val sun = Sun.position(lat, lon, t)
        val weather = forecast?.let { lookup(it, t.toInstant()) }
        // Each DNI value is the mean of the hour before its time, so this hour's and the
        // next's together cover the two hours centred on t. The clear-sky value is averaged
        // over the same window, so sunrise and sunset hours compare like with like.
        val next = forecast?.let { lookup(it, t.toInstant().plus(Duration.ofHours(1))) }
        val dni = listOfNotNull(weather?.dni, next?.dni).takeIf { it.isNotEmpty() }?.average()
        val clear = (-6..6).map { clearSkyDni(Sun.position(lat, lon, t.plusMinutes(it * 10L)).altitude) }.average()
        return HourRow(t, sun, Sun.phase(sun.altitude), weather, dni, clear, sky(weather, dni, clear))
    }

    /** The forecast hour at [time], allowing for forecasts on UTC hours in half-hour time zones. */
    private fun lookup(forecast: Forecast, time: Instant): HourWeather? =
        forecast.at(time) ?: forecast.at(time.truncatedTo(ChronoUnit.HOURS))
}

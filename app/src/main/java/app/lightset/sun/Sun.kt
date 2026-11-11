package app.lightset.sun

import org.shredzone.commons.suncalc.SunPosition
import org.shredzone.commons.suncalc.SunTimes
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Where the sun is: [azimuth] in degrees clockwise from true north, [altitude] above the horizon. */
data class SunPos(val azimuth: Double, val altitude: Double)

/** A stretch of the day, e.g. golden hour. Either end is null if the sun doesn't reach that angle. */
data class Window(val start: ZonedDateTime?, val end: ZonedDateTime?) {
    val isEmpty: Boolean get() = start == null && end == null
}

/** The sun's times for one day at one place. */
data class SunDay(
    val date: LocalDate,
    val zone: ZoneId,
    val sunrise: ZonedDateTime?,
    val sunset: ZonedDateTime?,
    val noon: ZonedDateTime?,
    val noonAltitude: Double,
    val sunriseAzimuth: Double?,
    val sunsetAzimuth: Double?,
    val alwaysUp: Boolean,
    val alwaysDown: Boolean,
    val goldenMorning: Window,
    val goldenEvening: Window,
    val blueMorning: Window,
    val blueEvening: Window,
) {
    val dayLength: Duration?
        get() = when {
            alwaysUp -> Duration.ofHours(24)
            alwaysDown -> Duration.ZERO
            sunrise != null && sunset != null && sunset.isAfter(sunrise) -> Duration.between(sunrise, sunset)
            else -> null
        }
}

/**
 * Which light the hour is in, by the sun's height: golden hour is +6° down to −4°, and
 * blue hour −4° to −8° (the photographers' definitions commons-suncalc uses).
 */
enum class Phase { DAY, GOLDEN, BLUE, NIGHT }

object Sun {
    const val GOLDEN_TOP = 6.0
    const val BLUE_TOP = -4.0
    const val BLUE_BOTTOM = -8.0

    fun position(lat: Double, lon: Double, at: ZonedDateTime): SunPos {
        val p = SunPosition.compute().on(at).at(lat, lon).execute()
        return SunPos(p.azimuth, p.altitude)
    }

    fun phase(altitude: Double): Phase = when {
        altitude >= GOLDEN_TOP -> Phase.DAY
        altitude >= BLUE_TOP -> Phase.GOLDEN
        altitude >= BLUE_BOTTOM -> Phase.BLUE
        else -> Phase.NIGHT
    }

    fun day(lat: Double, lon: Double, date: LocalDate, zone: ZoneId): SunDay {
        fun times(twilight: SunTimes.Twilight): SunTimes =
            SunTimes.compute().on(date).timezone(zone).at(lat, lon).twilight(twilight).oneDay().execute()

        val horizon = times(SunTimes.Twilight.VISUAL)
        val golden = times(SunTimes.Twilight.GOLDEN_HOUR)
        val blue = times(SunTimes.Twilight.BLUE_HOUR)
        val night = times(SunTimes.Twilight.NIGHT_HOUR)
        val noon = horizon.noon
        return SunDay(
            date = date,
            zone = zone,
            sunrise = horizon.rise,
            sunset = horizon.set,
            noon = noon,
            noonAltitude = noon?.let { position(lat, lon, it).altitude } ?: 0.0,
            sunriseAzimuth = horizon.rise?.let { position(lat, lon, it).azimuth },
            sunsetAzimuth = horizon.set?.let { position(lat, lon, it).azimuth },
            alwaysUp = horizon.isAlwaysUp,
            alwaysDown = horizon.isAlwaysDown,
            goldenMorning = Window(blue.rise, golden.rise),
            goldenEvening = Window(golden.set, blue.set),
            blueMorning = Window(night.rise, blue.rise),
            blueEvening = Window(blue.set, night.set),
        )
    }

    /** The sun's height through the day, every [stepMinutes] from midnight to midnight. */
    fun altitudes(lat: Double, lon: Double, date: LocalDate, zone: ZoneId, stepMinutes: Long = 10): List<Double> {
        val start = date.atStartOfDay(zone)
        return (0..(24 * 60 / stepMinutes)).map { position(lat, lon, start.plusMinutes(it * stepMinutes)).altitude }
    }

    private val POINTS = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    /** The nearest of the 16 compass points, e.g. 224° → "SW". */
    fun compassPoint(azimuth: Double): String = POINTS[((azimuth.mod(360.0) / 22.5).roundToInt()) % 16]
}

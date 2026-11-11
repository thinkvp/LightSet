package app.lightset.sun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SunHoursTest {
    private val london = ZoneId.of("Europe/London")

    private fun weather(cloud: Int? = 50, dni: Double? = null, code: Int? = 3) = HourWeather(
        time = Instant.EPOCH, temperature = 12.0, cloudCover = cloud, cloudLow = 10, cloudMid = 20, cloudHigh = 30,
        precipProbability = 0, dni = dni, code = code,
    )

    @Test
    fun clearSkyDirectSunlight() {
        assertEquals(947.0, SunHours.clearSkyDni(90.0), 2.0)
        assertEquals(431.0, SunHours.clearSkyDni(10.0), 10.0)
        assertEquals(0.0, SunHours.clearSkyDni(-1.0), 0.0)
    }

    @Test
    fun skyFromHowMuchSunGetsThrough() {
        // Thin high cloud: lots of cover, but most of the direct sun still arrives.
        assertEquals(Sky.SUN, SunHours.sky(weather(cloud = 85, dni = 700.0), 700.0, 850.0))
        assertEquals(Sky.HAZY, SunHours.sky(weather(dni = 300.0), 300.0, 850.0))
        assertEquals(Sky.OVERCAST, SunHours.sky(weather(cloud = 100, dni = 20.0), 20.0, 850.0))
    }

    @Test
    fun weatherCodesWin() {
        assertEquals(Sky.RAIN, SunHours.sky(weather(code = 63), 800.0, 850.0))
        assertEquals(Sky.STORM, SunHours.sky(weather(code = 95), 0.0, 850.0))
        assertEquals(Sky.SNOW, SunHours.sky(weather(code = 73), 0.0, 0.0))
        assertEquals(Sky.FOG, SunHours.sky(weather(code = 45), 0.0, 850.0))
        assertEquals(Sky.DRIZZLE, SunHours.sky(weather(code = 51), 0.0, 850.0))
    }

    @Test
    fun sunDownFallsBackToCloudCover() {
        assertEquals(Sky.CLEAR, SunHours.sky(weather(cloud = 10), 0.0, 0.0))
        assertEquals(Sky.PARTLY, SunHours.sky(weather(cloud = 50), 0.0, 0.0))
        assertEquals(Sky.CLOUDY, SunHours.sky(weather(cloud = 90), 0.0, 0.0))
        assertNull(SunHours.sky(null, null, 800.0))
    }

    @Test
    fun hoursFollowClockChanges() {
        assertEquals(24, SunHours.hours(51.5, -0.13, LocalDate.of(2026, 11, 11), london, null).size)
        assertEquals(23, SunHours.hours(51.5, -0.13, LocalDate.of(2026, 3, 29), london, null).size)
        assertEquals(25, SunHours.hours(51.5, -0.13, LocalDate.of(2026, 10, 25), london, null).size)
    }

    @Test
    fun hoursPickUpTheForecastAndCentreDirectSun() {
        val date = LocalDate.of(2026, 6, 21)
        val noon = date.atTime(12, 0).atZone(london).toInstant()
        val json = forecastJson(
            startEpoch = date.atStartOfDay(london).toEpochSecond(),
            dni = List(24) { h -> if (h == 12) 600.0 else if (h == 13) 800.0 else 0.0 },
        )
        val forecast = OpenMeteo.parse(json, Instant.now())
        val row = SunHours.hours(51.5, -0.13, date, london, forecast).first { it.time.toInstant() == noon }
        assertEquals(12, row.weather!!.temperature!!.toInt())
        // The 12:00 and 13:00 values cover 11:00–13:00, centred on noon.
        assertEquals(700.0, row.dni!!, 0.01)
        assertEquals(Sky.SUN, row.sky)
        assertEquals(Phase.DAY, row.phase)
    }
}

/** A day of Open-Meteo hourly data in its unixtime format. */
fun forecastJson(startEpoch: Long, dni: List<Double?>): String {
    val n = dni.size
    fun arr(f: (Int) -> String) = (0 until n).joinToString(",", "[", "]") { f(it) }
    return """
        {"latitude":51.5,"longitude":-0.12,"timezone":"Europe/London","utc_offset_seconds":3600,
         "hourly":{
           "time":${arr { (startEpoch + it * 3600L).toString() }},
           "temperature_2m":${arr { "12.4" }},
           "cloud_cover":${arr { "40" }},
           "cloud_cover_low":${arr { "5" }},
           "cloud_cover_mid":${arr { "null" }},
           "cloud_cover_high":${arr { "35" }},
           "precipitation_probability":${arr { "0" }},
           "direct_normal_irradiance":${arr { dni[it]?.toString() ?: "null" }},
           "weather_code":${arr { "1" }}
         }}
    """.trimIndent()
}

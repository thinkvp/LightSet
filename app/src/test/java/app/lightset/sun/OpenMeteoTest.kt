package app.lightset.sun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class OpenMeteoTest {
    @Test
    fun parsesHoursAndNulls() {
        val start = 1_782_000_000L - 1_782_000_000L % 3600
        val f = OpenMeteo.parse(forecastJson(start, listOf(0.0, 512.6, null)), Instant.EPOCH)
        assertEquals(3, f.hours.size)
        assertEquals(ZoneId.of("Europe/London"), f.zone)
        assertEquals(51.5, f.lat, 0.0)
        val h = f.at(Instant.ofEpochSecond(start + 3600))!!
        assertEquals(512.6, h.dni!!, 0.001)
        assertEquals(12.4, h.temperature!!, 0.001)
        assertEquals(40, h.cloudCover)
        assertEquals(5, h.cloudLow)
        assertNull(h.cloudMid)
        assertEquals(1, h.code)
        assertEquals(14.2, h.windSpeed!!, 0.001)
        assertEquals(31.0, h.windGusts!!, 0.001)
        assertEquals(225.0, h.windDirection!!, 0.001)
        assertNull(f.at(Instant.ofEpochSecond(start + 2 * 3600))!!.dni)
        assertNull(f.at(Instant.ofEpochSecond(start + 30)))
    }

    @Test
    fun requestRoundsTheLocationAndAsksForUnixTimes() {
        val url = OpenMeteo.url(51.507412, -0.127812)
        assertTrue(url, url.contains("latitude=51.51&longitude=-0.13"))
        assertTrue(url.contains("timeformat=unixtime"))
        assertTrue(url.contains("direct_normal_irradiance"))
        assertTrue(url.contains("forecast_days=16"))
        assertTrue(url.contains("wind_gusts_10m"))
    }
}

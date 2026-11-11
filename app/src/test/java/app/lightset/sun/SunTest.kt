package app.lightset.sun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SunTest {
    private val london = ZoneId.of("Europe/London")
    private val lat = 51.5074
    private val lon = -0.1278

    private fun assertNear(expected: LocalTime, actual: LocalTime?, minutes: Long = 2) {
        assertNotNull(actual)
        val diff = Duration.between(expected, actual).abs()
        assertTrue("expected $expected, got $actual", diff <= Duration.ofMinutes(minutes))
    }

    @Test
    fun londonMidsummerMatchesTheAlmanac() {
        val day = Sun.day(lat, lon, LocalDate.of(2026, 6, 21), london)
        assertNear(LocalTime.of(4, 43), day.sunrise?.toLocalTime())
        assertNear(LocalTime.of(21, 21), day.sunset?.toLocalTime())
        assertNear(LocalTime.of(13, 2), day.noon?.toLocalTime())
        assertEquals(62.0, day.noonAltitude, 0.5)
        // Rises in the north-east and sets in the north-west in June.
        assertEquals(49.0, day.sunriseAzimuth!!, 2.0)
        assertEquals(311.0, day.sunsetAzimuth!!, 2.0)
        assertFalse(day.alwaysUp || day.alwaysDown)
    }

    @Test
    fun goldenAndBlueHourBracketSunset() {
        val day = Sun.day(lat, lon, LocalDate.of(2026, 11, 11), london)
        val sunset = day.sunset!!
        // Golden hour runs from +6° to −4°, so it straddles sunset; blue hour follows it.
        assertTrue(day.goldenEvening.start!!.isBefore(sunset))
        assertTrue(day.goldenEvening.end!!.isAfter(sunset))
        assertEquals(day.goldenEvening.end, day.blueEvening.start)
        assertTrue(day.blueEvening.end!!.isAfter(day.blueEvening.start))
        // And mirrored in the morning.
        assertTrue(day.goldenMorning.end!!.isAfter(day.sunrise))
        assertEquals(day.blueMorning.end, day.goldenMorning.start)
    }

    @Test
    fun polarDaysAndNights() {
        val tromso = ZoneId.of("Europe/Oslo")
        val summer = Sun.day(69.65, 18.96, LocalDate.of(2026, 6, 21), tromso)
        assertTrue(summer.alwaysUp)
        assertNull(summer.sunrise)
        assertEquals(Duration.ofHours(24), summer.dayLength)
        val winter = Sun.day(69.65, 18.96, LocalDate.of(2026, 12, 21), tromso)
        assertTrue(winter.alwaysDown)
        assertEquals(Duration.ZERO, winter.dayLength)
    }

    @Test
    fun southernHemisphereNoonSunIsInTheNorth() {
        val sydney = ZoneId.of("Australia/Sydney")
        val day = Sun.day(-33.87, 151.21, LocalDate.of(2026, 12, 21), sydney)
        val pos = Sun.position(-33.87, 151.21, day.noon!!)
        assertTrue(pos.azimuth < 5 || pos.azimuth > 355)
        assertEquals(79.5, pos.altitude, 1.0)
    }

    @Test
    fun compassPoints() {
        assertEquals("N", Sun.compassPoint(0.0))
        assertEquals("N", Sun.compassPoint(359.0))
        assertEquals("E", Sun.compassPoint(90.0))
        assertEquals("SW", Sun.compassPoint(224.0))
        assertEquals("WNW", Sun.compassPoint(292.5))
        assertEquals("NNW", Sun.compassPoint(-20.0))
    }

    @Test
    fun phasesByAltitude() {
        assertEquals(Phase.DAY, Sun.phase(30.0))
        assertEquals(Phase.GOLDEN, Sun.phase(5.9))
        assertEquals(Phase.GOLDEN, Sun.phase(-3.9))
        assertEquals(Phase.BLUE, Sun.phase(-6.0))
        assertEquals(Phase.NIGHT, Sun.phase(-8.1))
    }

    @Test
    fun altitudeCurveCoversTheDay() {
        val curve = Sun.track(lat, lon, LocalDate.of(2026, 6, 21), london, 10).map { it.altitude }
        assertEquals(145, curve.size)
        assertEquals(62.0, curve.max(), 0.5)
        assertTrue(curve.first() < 0)
    }
}

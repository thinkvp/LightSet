package app.lightset.sun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WeatherUnitsTest {
    @Test
    fun usIsFahrenheitAndMph() {
        val u = WeatherUnits.forLocale(Locale.US)
        assertTrue(u.fahrenheit && u.mph)
        assertEquals("68°", u.temperature(20.0))
        assertEquals("25 mph", u.speed(40.0))
        assertEquals(31, u.speedNumber(50.0))
    }

    @Test
    fun ukIsCelsiusButMph() {
        val u = WeatherUnits.forLocale(Locale.UK)
        assertFalse(u.fahrenheit)
        assertTrue(u.mph)
        assertEquals("20°", u.temperature(20.0))
    }

    @Test
    fun elsewhereIsMetric() {
        for (l in listOf(Locale.GERMANY, Locale("en", "AU"), Locale.CANADA, Locale("pl", "PL"))) {
            assertEquals(l.toString(), WeatherUnits.METRIC, WeatherUnits.forLocale(l))
        }
        assertEquals("40 km/h", WeatherUnits.METRIC.speed(40.0))
        assertEquals("-3°", WeatherUnits.METRIC.temperature(-3.4))
    }

    @Test
    fun regionalPreferenceOverridesTemperature() {
        val usCelsius = WeatherUnits.forLocale(Locale.forLanguageTag("en-US-u-mu-celsius"))
        assertFalse(usCelsius.fahrenheit)
        assertTrue(usCelsius.mph)
        val deFahrenheit = WeatherUnits.forLocale(Locale.forLanguageTag("de-DE-u-mu-fahrenhe"))
        assertTrue(deFahrenheit.fahrenheit)
        assertFalse(deFahrenheit.mph)
    }
}

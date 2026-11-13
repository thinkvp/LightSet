package app.lightset.sun

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The units the phone's region reads weather in: °F in the US and a few others, mph in the
 * US and UK. Taken from the phone's region rather than where it is, so someone from the US
 * shooting in Paris still sees °F. Android 14's Regional preferences (Settings › System ›
 * Languages › Regional preferences) can override the temperature unit, which arrives as the
 * locale's `mu` extension.
 *
 * The forecast stays in °C and km/h; these only change what's shown.
 */
data class WeatherUnits(val fahrenheit: Boolean, val mph: Boolean) {

    /** A temperature from °C, rounded, e.g. "12°". No letter: the region's unit goes without saying. */
    fun temperature(celsius: Double): String =
        "${(if (fahrenheit) celsius * 9 / 5 + 32 else celsius).roundToInt()}°"

    /** A speed from km/h as a bare number, for gusts beside the speed. */
    fun speedNumber(kmh: Double): Int = (if (mph) kmh / KM_PER_MILE else kmh).roundToInt()

    /** A speed from km/h with its unit, e.g. "25 km/h" or "16 mph". */
    fun speed(kmh: Double): String = "${speedNumber(kmh)} $speedUnit"

    val speedUnit: String get() = if (mph) "mph" else "km/h"

    companion object {
        private const val KM_PER_MILE = 1.609344

        val METRIC = WeatherUnits(fahrenheit = false, mph = false)

        // The US and its territories, and the few others that still forecast in °F.
        private val FAHRENHEIT = setOf("US", "PR", "GU", "VI", "AS", "MP", "UM", "BS", "BZ", "KY", "PW", "FM", "MH", "LR")

        // Where road signs and forecasts give wind in mph: the US and the UK, with their
        // territories and dependencies, and a few others.
        private val MPH = setOf(
            "US", "PR", "GU", "VI", "AS", "MP", "UM",
            "GB", "IM", "JE", "GG", "FK", "GI",
            "LR", "BS", "BZ", "KY", "VG", "AI", "MS", "TC",
        )

        fun forLocale(locale: Locale): WeatherUnits {
            val region = locale.country.uppercase(Locale.ROOT)
            val fahrenheit = when (locale.getUnicodeLocaleType("mu")) {
                "fahrenhe" -> true
                "celsius", "kelvin" -> false
                else -> region in FAHRENHEIT
            }
            return WeatherUnits(fahrenheit, mph = region in MPH)
        }
    }
}

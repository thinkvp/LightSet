package app.lightset.sun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * One hour of forecast. Cloud and rain chance are percentages. [dni] is direct sunlight
 * on a surface facing the sun (W/m²), averaged over the hour *before* [time]. Wind is at
 * 10 m in km/h; [windDirection] is where it blows *from*, in degrees from north.
 */
data class HourWeather(
    val time: Instant,
    val temperature: Double?,
    val cloudCover: Int?,
    val cloudLow: Int?,
    val cloudMid: Int?,
    val cloudHigh: Int?,
    val precipProbability: Int?,
    val dni: Double?,
    val code: Int?,
    val windSpeed: Double? = null,
    val windGusts: Double? = null,
    val windDirection: Double? = null,
)

/** An hourly forecast for one place, as fetched at [fetchedAt]. [json] is kept so it can be cached. */
class Forecast(
    val lat: Double,
    val lon: Double,
    val zone: ZoneId?,
    val fetchedAt: Instant,
    val hours: List<HourWeather>,
    val json: String,
) {
    private val byTime = hours.associateBy { it.time }

    /** The forecast for the hour starting at [time] (which must be on the hour), if there is one. */
    fun at(time: Instant): HourWeather? = byTime[time]

    val start: Instant? get() = hours.firstOrNull()?.time
    val end: Instant? get() = hours.lastOrNull()?.time
}

/**
 * The Open-Meteo forecast API: free and keyless for non-commercial use, with cloud cover by
 * height and direct sunlight, which say more about "hard sun or overcast" than cloud alone.
 * Data is CC BY 4.0, credited on the Sun tab.
 */
object OpenMeteo {
    const val DAYS = 16
    private const val FIELDS = "temperature_2m,cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high," +
        "precipitation_probability,direct_normal_irradiance,weather_code," +
        "wind_speed_10m,wind_gusts_10m,wind_direction_10m"

    /** The request for a place, rounded to about 1 km, which is all a forecast needs. */
    fun url(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&hourly=%s&forecast_days=%d&timezone=auto&timeformat=unixtime"
            .format(Locale.US, lat, lon, FIELDS, DAYS)

    /**
     * The same forecast from Open-Meteo's archive server, which runs on separate hosting
     * and also holds the latest forecast runs into the future. Used when the main server
     * doesn't answer, which happens: it's routed by region, and one region's server can be
     * unreachable while the rest are fine. From yesterday (so every time zone's today is
     * covered) to [DAYS] days on.
     */
    fun fallbackUrl(lat: Double, lon: Double, today: LocalDate): String =
        ("https://historical-forecast-api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&hourly=%s" +
            "&start_date=%s&end_date=%s&timezone=auto&timeformat=unixtime")
            .format(Locale.US, lat, lon, FIELDS, today.minusDays(1), today.plusDays(DAYS - 1L))

    /** The forecast from the main server, or the archive server if the main one fails. */
    suspend fun fetch(lat: Double, lon: Double): Forecast = withContext(Dispatchers.IO) {
        try {
            get(url(lat, lon), connectMs = 6_000)
        } catch (main: Exception) {
            try {
                get(fallbackUrl(lat, lon, LocalDate.now()), connectMs = 10_000)
            } catch (e: Exception) {
                // Say what went wrong with the main server, in plain words where it's a timeout.
                throw if (main is SocketTimeoutException || main is UnknownHostException) {
                    IOException("forecast server not responding")
                } else main
            }
        }
    }

    private fun get(address: String, connectMs: Int): Forecast {
        val conn = URL(address).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectMs
            conn.readTimeout = 15_000
            if (conn.responseCode != 200) {
                val reason = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.let { runCatching { JSONObject(it).optString("reason") }.getOrNull() }
                throw IllegalStateException(reason?.takeIf { it.isNotBlank() } ?: "HTTP ${conn.responseCode}")
            }
            return parse(conn.inputStream.bufferedReader().use { it.readText() }, Instant.now())
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String, fetchedAt: Instant): Forecast {
        val root = JSONObject(json)
        val hourly = root.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        fun ints(name: String): JSONArray? = hourly.optJSONArray(name)
        val temp = ints("temperature_2m")
        val cloud = ints("cloud_cover")
        val low = ints("cloud_cover_low")
        val mid = ints("cloud_cover_mid")
        val high = ints("cloud_cover_high")
        val precip = ints("precipitation_probability")
        val dni = ints("direct_normal_irradiance")
        val code = ints("weather_code")
        val wind = ints("wind_speed_10m")
        val gusts = ints("wind_gusts_10m")
        val windDir = ints("wind_direction_10m")
        val hours = (0 until times.length()).map { i ->
            HourWeather(
                time = Instant.ofEpochSecond(times.getLong(i)),
                temperature = temp.double(i),
                cloudCover = cloud.int(i),
                cloudLow = low.int(i),
                cloudMid = mid.int(i),
                cloudHigh = high.int(i),
                precipProbability = precip.int(i),
                dni = dni.double(i),
                code = code.int(i),
                windSpeed = wind.double(i),
                windGusts = gusts.double(i),
                windDirection = windDir.double(i),
            )
        }.filter { it.cloudCover != null || it.dni != null || it.code != null }
        // The last day or so of the 16 often comes back empty; those hours count as no forecast.
        val zone = root.optString("timezone").takeIf { it.isNotBlank() }
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        return Forecast(root.getDouble("latitude"), root.getDouble("longitude"), zone, fetchedAt, hours, json)
    }

    private fun JSONArray?.double(i: Int): Double? =
        if (this == null || i >= length() || isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }

    private fun JSONArray?.int(i: Int): Int? = double(i)?.let { Math.round(it).toInt() }
}

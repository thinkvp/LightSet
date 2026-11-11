package app.lightset.sun

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.resume

/** A place to work out the sun for, with a [name] from the geocoder if it had one. */
data class Place(val lat: Double, val lon: Double, val name: String?, val time: Long) {
    val label: String
        get() = name ?: "%.3f°%s %.3f°%s".format(
            Locale.US, kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W",
        )

    fun distanceKm(lat2: Double, lon2: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, lat2, lon2, out)
        return out[0] / 1000.0
    }
}

/**
 * The phone's approximate location, which is plenty for sun times and a forecast. The
 * last place and forecast are kept, so the tab still works with no signal on location.
 */
class PlaceSource(private val context: Context) {
    private val prefs = context.getSharedPreferences("sun", Context.MODE_PRIVATE)
    private val forecastFile = File(context.filesDir, "sun_forecast.json")
    private val lm = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun locationOn(): Boolean = lm != null && LocationManagerCompat.isLocationEnabled(lm)

    fun saved(): Place? {
        if (!prefs.contains(KEY_LAT)) return null
        return Place(
            lat = prefs.getFloat(KEY_LAT, 0f).toDouble(),
            lon = prefs.getFloat(KEY_LON, 0f).toDouble(),
            name = prefs.getString(KEY_NAME, null),
            time = prefs.getLong(KEY_TIME, 0),
        )
    }

    private fun save(p: Place) {
        prefs.edit().putFloat(KEY_LAT, p.lat.toFloat()).putFloat(KEY_LON, p.lon.toFloat())
            .putString(KEY_NAME, p.name).putLong(KEY_TIME, p.time).apply()
    }

    /**
     * Where the phone is now: a recent fix if there is one, or a fresh one. Keeps the saved
     * name when it hasn't moved far, so the geocoder is only asked when it has. Null if
     * there's no permission or no fix.
     */
    suspend fun locate(): Place? {
        if (!hasPermission() || lm == null) return null
        val loc = recent() ?: fresh() ?: return null
        val old = saved()
        val name = if (old?.name != null && old.distanceKm(loc.latitude, loc.longitude) < 1.0) old.name
        else placeName(loc.latitude, loc.longitude)
        return Place(loc.latitude, loc.longitude, name, System.currentTimeMillis()).also(::save)
    }

    /** The newest last-known fix from any provider, if it's under 10 minutes old. */
    private fun recent(): Location? = try {
        lm!!.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000 }
    } catch (e: SecurityException) {
        null
    }

    private suspend fun fresh(): Location? {
        val enabled = try { lm!!.getProviders(true) } catch (e: SecurityException) { return null }
        val provider = listOf(LocationManager.NETWORK_PROVIDER, FUSED, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in enabled } ?: return null
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(lm!!, provider, cancel, ContextCompat.getMainExecutor(context)) {
                        if (cont.isActive) cont.resume(it)
                    }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /** The town or suburb, from the platform geocoder (needs a connection). */
    private suspend fun placeName(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context)
        val address: Address? = withTimeoutOrNull(10_000) {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
                }
            }
        }
        return address?.let { it.subLocality ?: it.locality ?: it.subAdminArea ?: it.adminArea }
    }

    fun savedForecast(): Forecast? = runCatching {
        val text = forecastFile.readText()
        val fetched = text.substringBefore('\n').toLong()
        OpenMeteo.parse(text.substringAfter('\n'), Instant.ofEpochMilli(fetched))
    }.getOrNull()

    fun saveForecast(f: Forecast) {
        runCatching { forecastFile.writeText("${f.fetchedAt.toEpochMilli()}\n${f.json}") }
    }

    private companion object {
        const val FUSED = "fused"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_NAME = "name"
        const val KEY_TIME = "time"
    }
}

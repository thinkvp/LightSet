package app.lightset.sun

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.sqrt

/**
 * The phone's orientation: [rotation] takes phone coordinates to (east, magnetic north, up),
 * row-major. [headingErrorDeg] is the sensor's own estimate of its compass error, if it
 * gives one; [unreliable] is set when it says the compass needs calibrating.
 */
class OrientationReading(val rotation: FloatArray, val headingErrorDeg: Float?, val unreliable: Boolean)

/** The rotation vector sensor (gyro, accelerometer and compass fused), lightly smoothed. */
class Orientation(context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    val available: Boolean get() = sensor != null

    fun readings(): Flow<OrientationReading> = callbackFlow {
        val s = sensor ?: run { close(); return@callbackFlow }
        val q = FloatArray(4)
        var haveQ = false
        var status = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values
                val w = if (v.size > 3) v[3] else sqrt((1 - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]).coerceAtLeast(0f))
                val next = floatArrayOf(v[0], v[1], v[2], w)
                if (!haveQ) {
                    next.copyInto(q)
                    haveQ = true
                } else {
                    smooth(q, next, SMOOTHING)
                }
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, q)
                val err = if (v.size > 4 && v[4] >= 0) Math.toDegrees(v[4].toDouble()).toFloat() else null
                trySend(
                    OrientationReading(
                        r, err,
                        unreliable = status <= SensorManager.SENSOR_STATUS_ACCURACY_LOW || (err != null && err > 25f),
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                status = accuracy
            }
        }
        sensors!!.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensors.unregisterListener(listener) }
    }.conflate()

    companion object {
        /** How far each new reading moves the smoothed one: steady enough to read, quick to follow. */
        private const val SMOOTHING = 0.25f

        /** Moves quaternion [q] (x, y, z, w) a fraction [t] towards [next], the short way round. */
        fun smooth(q: FloatArray, next: FloatArray, t: Float) {
            val dot = q[0] * next[0] + q[1] * next[1] + q[2] * next[2] + q[3] * next[3]
            val sign = if (dot < 0) -1f else 1f
            var len = 0f
            for (i in 0..3) {
                q[i] += (sign * next[i] - q[i]) * t
                len += q[i] * q[i]
            }
            val n = sqrt(len)
            for (i in 0..3) q[i] /= n
        }

        /** Degrees east of true north that magnetic north lies at [lat]/[lon] now. */
        fun declination(lat: Double, lon: Double): Double =
            GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble()
    }
}

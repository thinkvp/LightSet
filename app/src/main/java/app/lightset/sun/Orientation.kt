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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The phone's orientation: [rotation] takes phone coordinates to (east, magnetic north, up),
 * row-major. [headingErrorDeg] is an estimate of the compass error, if there is one;
 * [unreliable] is set when the compass needs calibrating or something magnetic is near.
 */
class OrientationReading(val rotation: FloatArray, val headingErrorDeg: Float?, val unreliable: Boolean)

/**
 * Where the phone points, in any pose: flat, upright, or aimed up at the sky.
 *
 * The phone's own compass-fused rotation vector loses its way when the camera is raised
 * past upright (some fusion works in tilt angles that break down there), so it isn't used
 * for heading. Instead the gyro and accelerometer (the game rotation vector, which never
 * touches the compass) give the orientation, and north comes from the raw magnetometer:
 * its field is turned into the gyro's frame as a 3D vector and its level part gives the
 * direction of magnetic north there. Nothing in that depends on how the phone is held. The
 * gyro's frame is turned to match, following the magnetometer over [FOLLOW_S] seconds so
 * noise averages out; readings whose strength is far from the Earth's field (steel, magnets,
 * speakers) are ignored. Phones without those sensors get the compass rotation vector.
 */
class Orientation(context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val magnet: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val compass: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    val available: Boolean get() = (gyro != null && magnet != null) || compass != null

    /** The Earth's field strength here in µT, if known, to spot readings pushed around by metal. */
    @Volatile var expectedFieldUt: Float? = null

    fun readings(): Flow<OrientationReading> =
        if (gyro != null && magnet != null) fused(gyro, magnet) else compassOnly()

    private fun fused(g: Sensor, m: Sensor): Flow<OrientationReading> = callbackFlow {
        val q = FloatArray(4)
        var haveQ = false
        val r = FloatArray(9)
        var offset: Double? = null
        var startNs = 0L
        var lastMagNs = 0L
        var spread = 0.0 // running mean square of the magnetometer's scatter about the offset, deg²
        var disturbed = 0.0 // running share of recent magnetometer readings that were ignored
        var magAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor == g) {
                    val next = quaternion(e.values)
                    if (!haveQ) next.copyInto(q) else smooth(q, next, GYRO_SMOOTHING)
                    haveQ = true
                    SensorManager.getRotationMatrixFromVector(r, q)
                    val o = offset ?: return
                    trySend(
                        OrientationReading(
                            turned(r, o),
                            headingErrorDeg = sqrt(spread).toFloat(),
                            unreliable = magAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW || disturbed > 0.5,
                        ),
                    )
                    return
                }
                // Magnetometer: where magnetic north lies in the gyro's frame.
                if (!haveQ) return
                val dt = if (lastMagNs == 0L) 0.0 else ((e.timestamp - lastMagNs) / 1e9).coerceIn(0.0, 0.5)
                lastMagNs = e.timestamp
                if (startNs == 0L) startNs = e.timestamp
                val field = e.values
                val north = magneticNorth(r, field, expectedFieldUt)
                disturbed += ((if (north == null) 1.0 else 0.0) - disturbed) * (1 - exp(-dt / SPREAD_S))
                if (north == null) return
                val target = wrap(-north)
                val o = offset
                if (o == null) {
                    offset = target
                    return
                }
                val miss = wrap(target - o)
                spread += (miss * miss - spread) * (1 - exp(-dt / SPREAD_S))
                // Quicker for the first moments, to settle on opening.
                val follow = if (e.timestamp - startNs < 1_500_000_000L) SETTLE_S else FOLLOW_S
                offset = wrap(o + miss * (1 - exp(-dt / follow)))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor == m) magAccuracy = accuracy
            }
        }
        sensors!!.registerListener(listener, g, SensorManager.SENSOR_DELAY_GAME)
        sensors.registerListener(listener, m, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensors.unregisterListener(listener) }
    }.conflate()

    /** The compass-fused rotation vector, lightly smoothed: for phones without a gyro. */
    private fun compassOnly(): Flow<OrientationReading> = callbackFlow {
        val s = compass ?: run { close(); return@callbackFlow }
        val q = FloatArray(4)
        var haveQ = false
        var status = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values
                val next = quaternion(v)
                if (!haveQ) next.copyInto(q) else smooth(q, next, COMPASS_SMOOTHING)
                haveQ = true
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
        /** How long the magnetometer takes to move north: long enough to average out its noise. */
        const val FOLLOW_S = 2.0
        private const val SETTLE_S = 0.3
        private const val SPREAD_S = 2.0
        private const val GYRO_SMOOTHING = 0.5f
        private const val COMPASS_SMOOTHING = 0.25f

        /** How far from the expected strength a reading may be before it's taken as disturbed. */
        private const val FIELD_TOLERANCE = 0.25f

        /** A rotation vector's (x, y, z, w), working out w when the sensor leaves it out. */
        fun quaternion(v: FloatArray): FloatArray {
            val w = if (v.size > 3) v[3] else sqrt((1 - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]).coerceAtLeast(0f))
            return floatArrayOf(v[0], v[1], v[2], w)
        }

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

        /**
         * The heading (degrees clockwise from the frame's north) of magnetic north, from a
         * magnetometer [field] in phone coordinates and the phone's orientation [r] in that
         * frame. Null when the reading can't be trusted: its strength is more than a quarter
         * off [expectedUt], or the field is too steep to have a usable level part.
         */
        fun magneticNorth(r: FloatArray, field: FloatArray, expectedUt: Float?): Double? {
            val (x, y, z) = Triple(field[0], field[1], field[2])
            val strength = sqrt(x * x + y * y + z * z)
            if (strength < 1f) return null
            if (expectedUt != null && abs(strength - expectedUt) > expectedUt * FIELD_TOLERANCE) return null
            val e = r[0] * x + r[1] * y + r[2] * z
            val n = r[3] * x + r[4] * y + r[5] * z
            if (sqrt(e * e + n * n) < strength * 0.1f) return null
            return Math.toDegrees(atan2(e.toDouble(), n.toDouble()))
        }

        /** Angle in (−180, 180]. */
        fun wrap(deg: Double): Double = ((deg + 540.0) % 360.0) - 180.0

        /** [r] turned about the vertical so every heading grows by [deg]. */
        fun turned(r: FloatArray, deg: Double): FloatArray {
            val a = Math.toRadians(deg)
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            // Rows of the turn: e' = e·cos + n·sin, n' = −e·sin + n·cos, u' = u.
            return floatArrayOf(
                c * r[0] + s * r[3], c * r[1] + s * r[4], c * r[2] + s * r[5],
                -s * r[0] + c * r[3], -s * r[1] + c * r[4], -s * r[2] + c * r[5],
                r[6], r[7], r[8],
            )
        }

        /** Degrees east of true north that magnetic north lies at [lat]/[lon] now. */
        fun declination(lat: Double, lon: Double): Double = field(lat, lon).declination.toDouble()

        /** The Earth's field strength at [lat]/[lon], in µT. */
        fun fieldStrengthUt(lat: Double, lon: Double): Float = field(lat, lon).fieldStrength / 1000f

        private fun field(lat: Double, lon: Double) =
            GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis())
    }
}

package app.lightset.sun

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on screen; [inFront] is false for directions behind the camera (x/y then meaningless). */
data class ScreenPoint(val x: Float, val y: Float, val inFront: Boolean)

/**
 * Maps sky directions to screen pixels for the back camera, held in portrait.
 *
 * [rotation] is the phone's orientation from the rotation vector sensor: a row-major 3×3
 * matrix taking phone coordinates (x right, y up the screen, z out of it) to the world's
 * (east, north, up), where north is *magnetic* north. [headingShift] turns true azimuths
 * into that frame: the magnetic declination plus any correction from lining up on the sun.
 * [focalPx] is the lens's focal length in screen pixels, centred on ([cx], [cy]).
 */
class SkyProjection(
    private val rotation: FloatArray,
    private val headingShift: Double,
    private val focalPx: Float,
    private val cx: Float,
    private val cy: Float,
) {
    /** The phone's (x, y, z) for a direction given by true [azimuth] and [altitude], in degrees. */
    private fun toPhone(azimuth: Double, altitude: Double): DoubleArray {
        val az = Math.toRadians(azimuth - headingShift)
        val alt = Math.toRadians(altitude)
        val e = cos(alt) * sin(az)
        val n = cos(alt) * cos(az)
        val u = sin(alt)
        val r = rotation
        // The transpose takes world back to phone coordinates.
        return doubleArrayOf(
            r[0] * e + r[3] * n + r[6] * u,
            r[1] * e + r[4] * n + r[7] * u,
            r[2] * e + r[5] * n + r[8] * u,
        )
    }

    fun project(azimuth: Double, altitude: Double): ScreenPoint {
        val (x, y, z) = toPhone(azimuth, altitude)
        val forward = -z // the back camera looks down the phone's −z
        if (forward < MIN_FORWARD) return ScreenPoint(0f, 0f, false)
        return ScreenPoint((cx + focalPx * x / forward).toFloat(), (cy - focalPx * y / forward).toFloat(), true)
    }

    /**
     * Which way a direction lies from the screen centre, as a unit (dx, dy) in screen axes
     * (y down): for an arrow at the edge pointing at something off screen or behind.
     */
    fun screenDirection(azimuth: Double, altitude: Double): Pair<Float, Float> {
        val (x, y, _) = toPhone(azimuth, altitude)
        val len = sqrt(x * x + y * y)
        if (len < 1e-6) return 0f to 1f // straight behind: point down
        return (x / len).toFloat() to (-y / len).toFloat()
    }

    /** The true azimuth and altitude (degrees) of the sky at screen pixel ([x], [y]). */
    fun unproject(x: Float, y: Float): Pair<Double, Double> {
        val px = (x - cx) / focalPx.toDouble()
        val py = -(y - cy) / focalPx.toDouble()
        val pz = -1.0
        val len = sqrt(px * px + py * py + pz * pz)
        val (dx, dy, dz) = Triple(px / len, py / len, pz / len)
        val r = rotation
        val e = r[0] * dx + r[1] * dy + r[2] * dz
        val n = r[3] * dx + r[4] * dy + r[5] * dz
        val u = r[6] * dx + r[7] * dy + r[8] * dz
        val az = (Math.toDegrees(atan2(e, n)) + headingShift).mod(360.0)
        return az to Math.toDegrees(asin(u.coerceIn(-1.0, 1.0)))
    }

    companion object {
        /** Points within about 85° of straight ahead; past that they'd fly off to infinity. */
        private const val MIN_FORWARD = 0.08

        /**
         * The lens's focal length in screen pixels, for a preview stream of
         * [streamLong]×[streamShort] scaled to fill a portrait view of [viewW]×[viewH]
         * (PreviewView's FILL_CENTER). The stream covers the sensor's long side if it's at
         * least as wide as the sensor, else its short side.
         */
        fun focalPx(
            focalMm: Float,
            sensorLongMm: Float,
            sensorShortMm: Float,
            streamLong: Int,
            streamShort: Int,
            viewW: Int,
            viewH: Int,
        ): Float {
            val streamAspect = streamLong.toFloat() / streamShort
            val sensorAspect = sensorLongMm / sensorShortMm
            val fStream = if (streamAspect >= sensorAspect) focalMm * streamLong / sensorLongMm
            else focalMm * streamShort / sensorShortMm
            // In portrait the stream's long side runs up the screen.
            val scale = max(viewW.toFloat() / streamShort, viewH.toFloat() / streamLong)
            return fStream * scale
        }
    }
}

package app.lightset.sun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class OrientationTest {
    /** A phone held in portrait with its camera at [heading] and [elevation] (past 90° tips over backwards). */
    private fun pose(heading: Double, elevation: Double): FloatArray {
        val h = Math.toRadians(heading)
        val el = Math.toRadians(elevation)
        val cam = doubleArrayOf(cos(el) * sin(h), cos(el) * cos(h), sin(el))
        val x = doubleArrayOf(cos(h), -sin(h), 0.0) // right of the phone: level, 90° clockwise of the heading
        val z = DoubleArray(3) { -cam[it] }
        val y = doubleArrayOf(z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0])
        // Row-major, columns are the phone's axes in (east, north, up).
        return floatArrayOf(
            x[0].toFloat(), y[0].toFloat(), z[0].toFloat(),
            x[1].toFloat(), y[1].toFloat(), z[1].toFloat(),
            x[2].toFloat(), y[2].toFloat(), z[2].toFloat(),
        )
    }

    /** What the magnetometer reads for a world field (east, north, up) with the phone at [r]. */
    private fun reading(r: FloatArray, e: Float, n: Float, u: Float) = floatArrayOf(
        r[0] * e + r[3] * n + r[6] * u,
        r[1] * e + r[4] * n + r[7] * u,
        r[2] * e + r[5] * n + r[8] * u,
    )

    @Test
    fun northIsTheSameHoweverThePhoneIsHeld() {
        // A southern-hemisphere field: 24 µT level, 52 µT up, magnetic north 30° east of the frame's.
        val bh = 24f
        val e = bh * sin(Math.toRadians(30.0)).toFloat()
        val n = bh * cos(Math.toRadians(30.0)).toFloat()
        for (heading in listOf(0.0, 75.0, 200.0, 315.0)) {
            for (elevation in listOf(-60.0, 0.0, 45.0, 80.0, 89.9, 90.0, 100.0, 135.0)) {
                val r = pose(heading, elevation)
                val north = Orientation.magneticNorth(r, reading(r, e, n, 52f), expectedUt = 57f)!!
                assertEquals("heading $heading elevation $elevation", 30.0, north, 0.01)
            }
        }
    }

    @Test
    fun ignoresReadingsPushedAroundByMetal() {
        val r = pose(0.0, 60.0)
        assertNull(Orientation.magneticNorth(r, reading(r, 0f, 60f, 52f), expectedUt = 57f))
        assertNull(Orientation.magneticNorth(r, reading(r, 0f, 10f, 20f), expectedUt = 57f))
    }

    @Test
    fun turningAddsToEveryHeading() {
        val r = Orientation.turned(pose(10.0, 70.0), 25.0)
        val camE = -r[2]
        val camN = -r[5]
        assertEquals(35.0, Math.toDegrees(kotlin.math.atan2(camE, camN).toDouble()), 0.01)
        assertEquals(sin(Math.toRadians(70.0)), -r[8].toDouble(), 1e-6)
    }

    @Test
    fun wrapsIntoAHalfTurnEitherWay() {
        assertEquals(-170.0, Orientation.wrap(190.0), 1e-9)
        assertEquals(10.0, Orientation.wrap(-350.0), 1e-9)
    }
}

package app.lightset.sun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SkyProjectionTest {
    /** Phone upright in portrait, back camera facing magnetic north: x east, y up, z south. */
    private val facingNorth = floatArrayOf(
        1f, 0f, 0f,
        0f, 0f, -1f,
        0f, 1f, 0f,
    )

    /** The same, turned to face [heading]° clockwise from north. */
    private fun facing(heading: Double): FloatArray {
        val h = Math.toRadians(heading)
        val c = cos(h).toFloat()
        val s = sin(h).toFloat()
        // Columns: phone x (right) = (cos h, −sin h, 0), y (up) = (0, 0, 1), z (towards you) = (−sin h, −cos h, 0).
        return floatArrayOf(
            c, 0f, -s,
            -s, 0f, -c,
            0f, 1f, 0f,
        )
    }

    private val f = 1000f
    private val cx = 500f
    private val cy = 900f

    @Test
    fun straightAheadIsTheCentre() {
        val p = SkyProjection(facingNorth, 0.0, f, cx, cy).project(0.0, 0.0)
        assertTrue(p.inFront)
        assertEquals(cx, p.x, 0.01f)
        assertEquals(cy, p.y, 0.01f)
    }

    @Test
    fun eastIsRightAndUpIsUp() {
        val proj = SkyProjection(facingNorth, 0.0, f, cx, cy)
        val right = proj.project(10.0, 0.0)
        assertEquals(cx + f * Math.tan(Math.toRadians(10.0)).toFloat(), right.x, 0.5f)
        assertEquals(cy, right.y, 0.5f)
        val up = proj.project(0.0, 10.0)
        assertEquals(cx, up.x, 0.5f)
        assertEquals(cy - f * Math.tan(Math.toRadians(10.0)).toFloat(), up.y, 0.5f)
    }

    @Test
    fun behindIsNotInFront() {
        val proj = SkyProjection(facingNorth, 0.0, f, cx, cy)
        assertFalse(proj.project(180.0, 0.0).inFront)
        // Off to the right and behind: the arrow points right.
        val (dx, dy) = proj.screenDirection(120.0, 0.0)
        assertTrue(dx > 0.99f && kotlin.math.abs(dy) < 0.01f)
    }

    @Test
    fun declinationShiftsTrueBearings() {
        // With 10° east declination, magnetic north is 10° true, so true 10° is dead ahead.
        val p = SkyProjection(facingNorth, 10.0, f, cx, cy).project(10.0, 0.0)
        assertEquals(cx, p.x, 0.01f)
    }

    @Test
    fun turningThePhoneMovesTheSky() {
        val p = SkyProjection(facing(90.0), 0.0, f, cx, cy).project(90.0, 20.0)
        assertTrue(p.inFront)
        assertEquals(cx, p.x, 0.5f)
        assertTrue(p.y < cy)
    }

    @Test
    fun unprojectInvertsProject() {
        val proj = SkyProjection(facing(37.0), 4.5, f, cx, cy)
        val p = proj.project(50.0, 25.0)
        val (az, alt) = proj.unproject(p.x, p.y)
        assertEquals(50.0, az, 1e-3)
        assertEquals(25.0, alt, 1e-3)
    }

    @Test
    fun focalLengthForAFilledPortraitView() {
        // 6 mm lens on a 9.6×7.2 mm sensor, 4:3 stream filling a 1080×1920 view: height-limited.
        val fp = SkyProjection.focalPx(6f, 9.6f, 7.2f, 1440, 1080, 1080, 1920)
        assertEquals(1200f, fp, 0.5f)
        // A 16:9 stream covers the sensor's long side.
        val wide = SkyProjection.focalPx(6f, 9.6f, 7.2f, 1920, 1080, 1080, 1920)
        assertEquals(1200f, wide, 0.5f)
    }
}

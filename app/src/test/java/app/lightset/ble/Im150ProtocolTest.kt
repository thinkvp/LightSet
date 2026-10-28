package app.lightset.ble

import app.lightset.color.cctDuv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Im150ProtocolTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun parsesCapturedReading() {
        // 60C at 5600 K, G/M +50; the Illuminati app showed 5540 K, +1/4 green.
        val s = checkNotNull(Im150Protocol.parse(hex("c9 12 41 45 64 c4 a9 3e 97 c1 b4 3e c1 54 22 00")))
        assertEquals(3089.17, s.lux, 0.01)
        assertEquals(0.3316, s.xy.x, 0.0001)
        assertEquals(0.3530, s.xy.y, 0.0001)
        val c = cctDuv(s.xy)
        assertEquals(5540.0, c.cct, 15.0)
        assertEquals(0.0065, c.duv, 0.0003)
    }

    @Test
    fun nanBeforeFirstMeasurementIsNoReading() {
        assertNull(Im150Protocol.parse(hex("00 00 00 00 00 00 c0 7f 00 00 c0 7f c6 09 00 00")))
        assertNull(Im150Protocol.parse(hex("00 00 80 3f")))
    }
}

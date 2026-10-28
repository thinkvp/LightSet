package app.lightset.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalibrationTest {
    // Shaped like the Moto Edge 60 Pro: tungsten reads far too cool, daylight a little warm.
    private val cal = Calibration(listOf(
        CalPoint(2700.0, CctDuv(3500.0, 0.0030)),
        CalPoint(3200.0, CctDuv(4050.0, 0.0019)),
        CalPoint(4300.0, CctDuv(4700.0, 0.0005)),
        CalPoint(5600.0, CctDuv(5280.0, 0.0000)),
        CalPoint(6500.0, CctDuv(6150.0, -0.0010)),
    ))

    @Test
    fun calibrationPointsMapExactly() {
        for (p in cal.points) {
            val r = cal.apply(p.measured)
            assertEquals(p.targetKelvin, r.cct, 0.01)
            assertEquals(0.0, r.duv, 1e-12)
        }
    }

    @Test
    fun tintAwayFromLocusIsPreserved() {
        val r = cal.apply(CctDuv(4050.0, 0.0019 + 0.004))
        assertEquals(3200.0, r.cct, 0.01)
        assertEquals(0.004, r.duv, 1e-12)
    }

    @Test
    fun interpolatesInMired() {
        val mid = 1e6 / ((1e6 / 4050.0 + 1e6 / 4700.0) / 2)
        val r = cal.apply(CctDuv(mid, 0.0))
        assertEquals((1e6 / 3200.0 + 1e6 / 4300.0) / 2, r.mired, 1e-9)
    }

    @Test
    fun extrapolatesBeyondSpan() {
        val warm = cal.apply(CctDuv(3000.0, 0.0)).cct
        val cool = cal.apply(CctDuv(9000.0, 0.0)).cct
        assert(warm < 2700) { "warm $warm" }
        assert(cool > 6500) { "cool $cool" }
    }

    @Test
    fun rejectsOutOfOrderReadings() {
        assertThrows(IllegalArgumentException::class.java) {
            Calibration(listOf(
                CalPoint(3200.0, CctDuv(5000.0, 0.0)),
                CalPoint(5600.0, CctDuv(4000.0, 0.0)),
            ))
        }
    }

    @Test
    fun jsonRoundTrip() {
        val back = Calibration.fromJson(cal.toJson())
        val raw = CctDuv(4400.0, 0.002)
        assertEquals(cal.apply(raw).cct, back.apply(raw).cct, 1e-9)
        assertEquals(cal.apply(raw).duv, back.apply(raw).duv, 1e-12)
        assertEquals(cal.createdAt, back.createdAt)
    }

    @Test
    fun readsOldArrayFormat() {
        val old = """[{"target":2700,"cct":3500,"duv":0.003},{"target":6500,"cct":6150,"duv":-0.001}]"""
        val c = Calibration.fromJson(old)
        assertEquals(2, c.points.size)
        assertEquals(null, c.source)
    }

    @Test
    fun worstErrorAgainstOwnPointsIsZero() {
        assertEquals(0.0, cal.worstError(cal.points), 1e-9)
    }

    @Test
    fun referenceMeterTargetsCorrectTint() {
        val cal = Calibration(listOf(
            CalPoint(3050.0, CctDuv(2800.0, 0.002), targetDuv = -0.004, setKelvin = 3200.0),
            CalPoint(5330.0, CctDuv(5000.0, 0.001), targetDuv = -0.005, setKelvin = 5600.0),
        ))
        val c = cal.apply(CctDuv(5000.0, 0.001))
        assertEquals(5330.0, c.cct, 0.5)
        assertEquals(-0.005, c.duv, 1e-9)
        assertEquals(cal.points, Calibration.fromJson(cal.toJson()).points)
    }
}

package app.lightset.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureTest {
    private val all = ExposureParam.entries.toSet() - ExposureParam.LIGHT

    @Test
    fun evMatchesTheUsualLuxTable() {
        assertEquals(0.0, Exposure.ev100(2.5), 1e-9)
        assertEquals(10.0, Exposure.ev100(2560.0), 1e-9)
        assertEquals(2560.0, Exposure.luxForEv100(10.0), 1e-9)
    }

    @Test
    fun sunny16IsCorrect() {
        // EV100 15 = 81,920 lux: f/16 at 1/128 s, ISO 100 (log2(16² × 128) = 15).
        val s = ExposureSettings(fNumber = 16.0, iso = 100.0, angle = 360.0, fps = 128.0)
        assertEquals(0.0, Exposure.error(Exposure.luxForEv100(15.0), s), 0.02)
    }

    @Test
    fun allLockedReportsHowMuchLightIsMissing() {
        val s = ExposureSettings(fNumber = 2.8, iso = 800.0, angle = 180.0, fps = 24.0)
        val ok = Exposure.luxForEv100(log2Exposure(s))
        val r = Exposure.solve(ok / 4, s, all)
        assertEquals(-2.0, r.stopsOff, 1e-6)
        assertEquals(s, r.settings)
    }

    @Test
    fun ndTakesUpExcessLightFirst() {
        val s = ExposureSettings(fNumber = 2.8, iso = 800.0, angle = 180.0, fps = 24.0)
        val bright = Exposure.luxForEv100(log2Exposure(s)) * 8
        val r = Exposure.solve(bright, s, setOf(ExposureParam.APERTURE))
        assertEquals(3.0, r.settings.ndStops, 1e-9)
        assertEquals(2.8, r.settings.fNumber, 0.0)
        assertTrue(r.ok)
    }

    @Test
    fun darkSceneOpensApertureThenRaisesIso() {
        val s = ExposureSettings(fNumber = 2.8, iso = 800.0, angle = 180.0, fps = 24.0, ndStops = 2.0)
        val dark = Exposure.luxForEv100(log2Exposure(s.copy(ndStops = 0.0))) / 16
        val r = Exposure.solve(dark, s, setOf(ExposureParam.SHUTTER))
        assertEquals(0.0, r.settings.ndStops, 0.0)
        assertEquals(1.0, r.settings.fNumber, 0.0)
        assertTrue("ISO raised", r.settings.iso > 800.0)
        assertTrue(r.ok)
    }

    @Test
    fun stepMovesAlongTheList() {
        assertEquals(4.0, Exposure.step(Exposure.APERTURES, 3.5, 1), 0.0)
        assertEquals(1.0, Exposure.step(Exposure.APERTURES, 1.0, -1), 0.0)
        assertEquals(172.8, Exposure.step(Exposure.ANGLES, 180.0, -1), 0.0)
    }

    /** EV100 at which [s] is correctly exposed. */
    private fun log2Exposure(s: ExposureSettings): Double =
        Exposure.ev100(1.0) - Exposure.error(1.0, s)
}

package app.lightset.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {
    @Test
    fun d65() {
        val r = cctDuv(Xy(0.31271, 0.32902))
        assertEquals(6504.0, r.cct, 10.0)
        assertEquals(0.0032, r.duv, 0.0003)
    }

    @Test
    fun illuminantA() {
        val r = cctDuv(Xy(0.44757, 0.40745))
        assertEquals(2856.0, r.cct, 5.0)
        assertEquals(0.0, r.duv, 0.0003)
    }

    @Test
    fun roundTripAcrossRange() {
        for (k in listOf(1800.0, 2700.0, 3200.0, 4300.0, 5600.0, 6500.0, 7500.0, 12000.0)) {
            for (duv in listOf(-0.02, -0.005, 0.0, 0.004, 0.02)) {
                val r = cctDuv(xyFromCctDuv(k, duv))
                assertEquals("cct at $k/$duv", k, r.cct, k * 1e-4)
                assertEquals("duv at $k/$duv", duv, r.duv, 1e-6)
            }
        }
    }

    @Test
    fun greenIsPositive() {
        val onLocus = xyFromCctDuv(5600.0, 0.0)
        val greener = Xy(onLocus.x, onLocus.y + 0.005)
        assertTrue(cctDuv(greener).duv > 0)
    }

    @Test
    fun deltaUsesMired() {
        val d = delta(CctDuv(5800.0, 0.002), CctDuv(5600.0, 0.0))
        assertEquals(200.0, d.kelvin, 1e-9)
        assertEquals(1e6 / 5800 - 1e6 / 5600, d.mired, 1e-9)
        assertEquals(0.002, d.duv, 1e-12)
    }

    @Test
    fun matrixInverse() {
        val a = Matrix3(doubleArrayOf(2.0, 1.0, 0.5, 0.3, 1.5, 0.2, 0.1, 0.4, 1.8))
        val p = a * a.inverse()
        for (i in 0 until 9) assertEquals(Matrix3.IDENTITY.m[i], p.m[i], 1e-12)
    }
}

class CameraColorModelTest {
    // Plausible XYZ→camera matrices; values loosely based on a typical phone sensor.
    private val cmA = Matrix3(doubleArrayOf(
        1.10, -0.35, -0.05,
        -0.40, 1.20, 0.25,
        -0.05, 0.15, 0.65,
    ))
    private val cmD65 = Matrix3(doubleArrayOf(
        0.95, -0.30, -0.10,
        -0.45, 1.30, 0.15,
        -0.10, 0.20, 0.75,
    ))

    private fun model() = CameraColorModel(
        cmA, Matrix3.IDENTITY, 2856.0,
        cmD65, Matrix3.IDENTITY, 6504.0,
    )

    @Test
    fun recoversReferenceIlluminants() {
        for ((k, cm) in listOf(2856.0 to cmA, 6504.0 to cmD65)) {
            val xy = xyFromCctDuv(k, 0.001)
            val cam = cm * xyToXyz(xy)
            val r = cctDuv(model().toXy(cam))
            assertEquals(k, r.cct, 1.0)
            assertEquals(0.001, r.duv, 1e-5)
        }
    }

    @Test
    fun convergesBetweenIlluminants() {
        // Build camera RGB with the same interpolation the model uses, at 4300 K.
        val k = 4300.0
        val w = (1 / k - 1 / 6504.0) / (1 / 2856.0 - 1 / 6504.0)
        val cm = cmA * w + cmD65 * (1 - w)
        val xy = xyFromCctDuv(k, -0.004)
        val r = cctDuv(model().toXy(cm * xyToXyz(xy)))
        assertEquals(k, r.cct, 2.0)
        assertEquals(-0.004, r.duv, 2e-5)
    }

    @Test
    fun scaleInvariant() {
        val cam = doubleArrayOf(0.2, 0.35, 0.18)
        val a = model().toXy(cam)
        val b = model().toXy(DoubleArray(3) { cam[it] * 7.5 })
        assertEquals(a.x, b.x, 1e-9)
        assertEquals(a.y, b.y, 1e-9)
    }

    @Test
    fun processedDaylightPresetNeutralIsPresetKelvin() {
        val r = cctDuv(ProcessedColorModel.toXy(doubleArrayOf(0.18, 0.18, 0.18)))
        assertEquals(ProcessedColorModel.DAYLIGHT_PRESET_KELVIN, r.cct, 30.0)
    }
}

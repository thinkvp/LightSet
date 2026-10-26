package app.lightset.color

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real sensor matrices from a Motorola Edge 60 Pro main camera (IMX896, BGGR).
 * With these, fixed-point iteration over the matrix blend oscillates and returned
 * 3200 K light as ~7200 K.
 */
class MotoEdge60ProTest {
    private fun m(vararg v: Int) = Matrix3(DoubleArray(9) { v[it] / 65536.0 })

    private val cmD65 = m(43699, -10413, -5619, -37614, 91081, 9373, -9036, 17377, 39559)
    private val cmA = m(100366, -30776, -14094, -31210, 94721, 439, -4702, 15645, 15267)

    private val model = CameraColorModel(cmD65, Matrix3.IDENTITY, 6504.0, cmA, Matrix3.IDENTITY, 2856.0)

    /** Camera RGB this sensor's own (blended) matrices predict for a light. */
    private fun cameraRgbFor(kelvin: Double, duv: Double): DoubleArray {
        val wA = (1 / kelvin - 1 / 6504.0) / (1 / 2856.0 - 1 / 6504.0)
        val cm = cmA * wA + cmD65 * (1 - wA)
        return cm * xyToXyz(xyFromCctDuv(kelvin, duv))
    }

    @Test
    fun recoversLightsAcrossTheBlendRange() {
        for (k in listOf(3000.0, 3200.0, 4300.0, 5600.0, 6200.0)) {
            for (duv in listOf(-0.005, 0.0, 0.005)) {
                val r = cctDuv(model.toXy(cameraRgbFor(k, duv)))
                assertEquals("cct $k/$duv", k, r.cct, 2.0)
                assertEquals("duv $k/$duv", duv, r.duv, 5e-5)
            }
        }
    }

    @Test
    fun warmerLightReadsWarmer() {
        val warm = cctDuv(model.toXy(cameraRgbFor(3200.0, 0.0))).cct
        val cool = cctDuv(model.toXy(cameraRgbFor(5600.0, 0.0))).cct
        assert(warm < cool) { "3200 K read $warm, 5600 K read $cool" }
    }

    @Test
    fun printsOnDeviceSample() {
        // Logged from the phone: wall under room light, and the camera's AWB neutral.
        for ((name, cam) in mapOf(
            "scene" to doubleArrayOf(0.1044, 0.2029, 0.1437),
            "awbNeutral" to doubleArrayOf(512 / 1015.0, 1.0, 128 / 181.0),
        )) println("$name -> ${cctDuv(model.toXy(cam))}")
    }
}

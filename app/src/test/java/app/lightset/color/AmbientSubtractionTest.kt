package app.lightset.color

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientSubtractionTest {
    // Moto Edge 60 Pro D65 colour matrix (XYZ → camera).
    private val cm = Matrix3(DoubleArray(9) {
        intArrayOf(43699, -10413, -5619, -37614, 91081, 9373, -9036, 17377, 39559)[it] / 65536.0
    })
    private val model = CameraColorModel(cm, Matrix3.IDENTITY, 6504.0, null, null, null)

    private fun cam(k: Double, duv: Double, scale: Double) =
        (cm * xyToXyz(xyFromCctDuv(k, duv))).map { it * scale }.toDoubleArray()

    @Test
    fun recoversLightUnderStrongAmbient() {
        val ambient = cam(3100.0, 0.006, 0.8)      // warm, greenish room light, dominant
        val light = cam(5600.0, -0.002, 0.25)      // the light: under a quarter of the card
        val on = DoubleArray(3) { ambient[it] + light[it] }
        val r = AmbientSubtraction.isolate(on, ambient)
        val got = cctDuv(model.toXy(r.lightRgb))
        assertEquals(5600.0, got.cct, 5.0)
        assertEquals(-0.002, got.duv, 5e-5)
        assert(r.lightShare in 0.2..0.35) { "share ${r.lightShare}" }

        // Without subtraction the mix reads nowhere near the light.
        val mixed = cctDuv(model.toXy(on))
        assert(mixed.cct < 4000) { "mixed ${mixed.cct}" }
    }

    @Test
    fun noLightMeansZeroShare() {
        val ambient = cam(4000.0, 0.0, 0.5)
        assertEquals(0.0, AmbientSubtraction.isolate(ambient, ambient).lightShare, 1e-12)
    }
}

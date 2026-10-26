package app.lightset.color

/**
 * Converts linear camera-native RGB (from RAW) to CIE XYZ chromaticity using the
 * sensor's DNG-style colour matrices, which the camera HAL reports for two
 * reference illuminants.
 *
 * [colorMatrix1]/[colorMatrix2] map XYZ to reference camera space (DNG ColorMatrix);
 * [calibration1]/[calibration2] map reference to this unit's camera space (DNG
 * CameraCalibration). As in the DNG spec, the matrices are interpolated in inverse
 * CCT. The right blend depends on the answer, so we solve for the self-consistent
 * CCT by bisection: plain fixed-point iteration oscillates on some sensors (e.g. the
 * Moto Edge 60 Pro), where a cooler blend makes the same RGB read warmer.
 */
class CameraColorModel(
    private val colorMatrix1: Matrix3,
    private val calibration1: Matrix3,
    private val illuminant1Kelvin: Double,
    private val colorMatrix2: Matrix3?,
    private val calibration2: Matrix3?,
    private val illuminant2Kelvin: Double?,
) {
    fun toXy(cameraRgb: DoubleArray): Xy {
        if (colorMatrix2 == null || illuminant2Kelvin == null) {
            return xyzOf(cameraRgb, calibration1 * colorMatrix1)
        }
        fun xyAt(m: Double) = xyzOf(cameraRgb, xyzToCamera(1e6 / m))
        // g(m) = (mired the blend at m implies) − m; its root is the consistent answer.
        fun g(m: Double) = cctDuv(xyAt(m)).mired - m

        val coolM = mired(maxOf(illuminant1Kelvin, illuminant2Kelvin))
        val warmM = mired(minOf(illuminant1Kelvin, illuminant2Kelvin))
        val gCool = g(coolM)
        val gWarm = g(warmM)
        // Outside the span of the two reference illuminants the matrices are clamped.
        if (gCool <= 0 && gWarm <= 0) return xyAt(coolM)
        if (gCool >= 0 && gWarm >= 0) return xyAt(warmM)

        var lo = coolM
        var hi = warmM
        val loPositive = gCool > 0
        repeat(40) {
            val mid = (lo + hi) / 2
            if ((g(mid) > 0) == loPositive) lo = mid else hi = mid
        }
        return xyAt((lo + hi) / 2)
    }

    private fun xyzToCamera(kelvin: Double): Matrix3 {
        val t1 = illuminant1Kelvin
        val t2 = illuminant2Kelvin!!
        val lowT = minOf(t1, t2)
        val highT = maxOf(t1, t2)
        val t = kelvin.coerceIn(lowT, highT)
        // Weight of the low-CCT matrix, linear in 1/T.
        val wLow = if (highT == lowT) 1.0 else (1 / t - 1 / highT) / (1 / lowT - 1 / highT)
        val w1 = if (t1 <= t2) wLow else 1 - wLow
        val cm = colorMatrix1 * w1 + colorMatrix2!! * (1 - w1)
        val cc = calibration1 * w1 + calibration2!! * (1 - w1)
        return cc * cm
    }

    private fun xyzOf(cameraRgb: DoubleArray, xyzToCamera: Matrix3): Xy {
        val xyz = xyzToCamera.inverse() * cameraRgb
        return xyzToXy(xyz[0], xyz[1], xyz[2])
    }

    companion object {
        /** EXIF LightSource codes used by Camera2's SENSOR_REFERENCE_ILLUMINANT. */
        fun exifIlluminantKelvin(code: Int): Double? = when (code) {
            1 -> 5500.0   // daylight
            2 -> 4200.0   // fluorescent
            3 -> 2850.0   // tungsten
            4 -> 5500.0   // flash
            9 -> 5500.0   // fine weather
            10 -> 6500.0  // cloudy
            11 -> 7500.0  // shade
            12 -> 6430.0  // daylight fluorescent
            13 -> 5000.0  // day white fluorescent
            14 -> 4150.0  // cool white fluorescent
            15 -> 3525.0  // white fluorescent
            17 -> 2856.0  // standard A
            18 -> 4874.0  // standard B
            19 -> 6774.0  // standard C
            20 -> 5503.0  // D55
            21 -> 6504.0  // D65
            22 -> 7504.0  // D75
            23 -> 5003.0  // D50
            24 -> 3200.0  // ISO studio tungsten
            else -> null
        }
    }
}

/**
 * Fallback for cameras without RAW: the image was white-balanced with the fixed
 * daylight preset, so treat linearised sRGB as daylight adapted to D65 and undo
 * that adaptation (Bradford). Tone curves make this approximate; it is still
 * consistent between readings, which is what matching needs.
 */
object ProcessedColorModel {
    private val SRGB_TO_XYZ = Matrix3(doubleArrayOf(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041,
    ))
    private val BRADFORD = Matrix3(doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    ))
    private val D65 = Xy(0.31271, 0.32902)

    /** Kelvin the device's daylight AWB preset is assumed to neutralise. */
    const val DAYLIGHT_PRESET_KELVIN = 5500.0

    fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    fun toXy(linearSrgb: DoubleArray): Xy {
        val adapted = SRGB_TO_XYZ * linearSrgb
        val src = BRADFORD * xyToXyz(D65)
        val dst = BRADFORD * xyToXyz(daylightXy(DAYLIGHT_PRESET_KELVIN))
        val scale = Matrix3.diagonal(dst[0] / src[0], dst[1] / src[1], dst[2] / src[2])
        val xyz = BRADFORD.inverse() * scale * BRADFORD * adapted
        return xyzToXy(xyz[0], xyz[1], xyz[2])
    }
}

package app.lightset.camera

import app.lightset.color.CctDuv
import app.lightset.color.Xy

enum class MeterSource {
    /** Linear sensor data run through the sensor's own colour matrices. */
    RAW,

    /** Processed image with fixed daylight white balance — approximate. */
    PROCESSED,

    /** Illuminati IM150 incident meter; the phone's calibration doesn't apply. */
    IM150,
}

enum class ExposureWarning { NONE, TOO_DARK, CLIPPED }

data class Reading(
    val xy: Xy,
    val cctDuv: CctDuv,
    val source: MeterSource,
    /** Brightest channel of the sampled patch as a fraction of full scale. */
    val level: Double,
    val warning: ExposureWarning,
    val timestampMs: Long = System.currentTimeMillis(),
    /** Illuminance, from a meter that reports it. */
    val lux: Double? = null,
)

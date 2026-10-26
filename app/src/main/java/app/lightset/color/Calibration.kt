package app.lightset.color

import org.json.JSONArray
import org.json.JSONObject

/** One calibration step: what a trusted light was set to, and what the sensor model read. */
data class CalPoint(val targetKelvin: Double, val measured: CctDuv)

/**
 * Corrects the sensor model's readings using a trusted light (e.g. a Forza 60C at
 * G/M 0) read at several colour temps. Phone colour matrices can be well off at the
 * tungsten end, but they are smooth and monotonic, so a piecewise-linear correction
 * in mired space fixes CCT, and an offset per mired fixes tint (G/M 0 → Duv 0).
 * Outside the calibrated span the end segments are extrapolated.
 */
class Calibration(points: List<CalPoint>) {
    val points: List<CalPoint> = points.sortedBy { it.measured.mired }

    private val measuredM = this.points.map { it.measured.mired }
    private val targetM = this.points.map { mired(it.targetKelvin) }
    private val duvOffset = this.points.map { it.measured.duv }

    init {
        require(this.points.size >= 2) { "Need at least two calibration readings" }
        for (i in 1 until this.points.size) {
            require(measuredM[i] - measuredM[i - 1] > 1.0 && targetM[i] > targetM[i - 1]) {
                "Readings are out of order — check the light was set correctly at each step"
            }
        }
    }

    fun apply(raw: CctDuv): CctDuv {
        val m = interpolate(raw.mired, measuredM, targetM)
        return CctDuv(1e6 / m, raw.duv - interpolate(raw.mired, measuredM, duvOffset))
    }

    fun toJson(): String = JSONArray().apply {
        points.forEach {
            put(JSONObject().put("target", it.targetKelvin).put("cct", it.measured.cct).put("duv", it.measured.duv))
        }
    }.toString()

    companion object {
        fun fromJson(s: String): Calibration {
            val arr = JSONArray(s)
            return Calibration((0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                CalPoint(o.getDouble("target"), CctDuv(o.getDouble("cct"), o.getDouble("duv")))
            })
        }

        /** Linear interpolation over ascending [xs], extrapolating from the end segments. */
        internal fun interpolate(x: Double, xs: List<Double>, ys: List<Double>): Double {
            val i = when {
                x <= xs.first() -> 0
                x >= xs.last() -> xs.size - 2
                else -> xs.indexOfFirst { it > x } - 1
            }
            val t = (x - xs[i]) / (xs[i + 1] - xs[i])
            return ys[i] + t * (ys[i + 1] - ys[i])
        }
    }
}

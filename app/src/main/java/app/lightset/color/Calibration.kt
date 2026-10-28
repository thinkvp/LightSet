package app.lightset.color

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * One calibration step: the true colour of the light ([targetKelvin], [targetDuv]) and
 * what the sensor model read. The truth is either what a trusted light was set to
 * (G/M 0 → Duv 0) or a reference meter's reading. [setKelvin] is what the light was set to.
 */
data class CalPoint(
    val targetKelvin: Double,
    val measured: CctDuv,
    val targetDuv: Double = 0.0,
    val setKelvin: Double = targetKelvin,
)

/**
 * Corrects the sensor model's readings using a trusted light (e.g. a Forza 60C at
 * G/M 0) read at several colour temps. Phone colour matrices can be well off at the
 * tungsten end, but they are smooth and monotonic, so a piecewise-linear correction
 * in mired space fixes CCT, and an offset per mired fixes tint.
 * Outside the calibrated span the end segments are extrapolated.
 *
 * [createdAt] and [source] (the light it was made with, or "manual") are shown so it's
 * clear which calibration is in use.
 */
class Calibration(
    points: List<CalPoint>,
    val createdAt: Long = System.currentTimeMillis(),
    val source: String? = null,
) {
    val points: List<CalPoint> = points.sortedBy { it.measured.mired }

    private val measuredM = this.points.map { it.measured.mired }
    private val targetM = this.points.map { mired(it.targetKelvin) }
    private val duvOffset = this.points.map { it.measured.duv - it.targetDuv }

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

    /** Largest mired error this calibration makes on another set of readings. */
    fun worstError(readings: List<CalPoint>): Double =
        readings.maxOf { abs(apply(it.measured).mired - mired(it.targetKelvin)) }

    fun toJson(): String = JSONObject()
        .put("points", JSONArray().apply {
            points.forEach {
                put(JSONObject().put("target", it.targetKelvin).put("cct", it.measured.cct).put("duv", it.measured.duv)
                    .put("targetDuv", it.targetDuv).put("set", it.setKelvin))
            }
        })
        .put("createdAt", createdAt)
        .put("source", source)
        .toString()

    companion object {
        fun fromJson(s: String): Calibration {
            // Older versions stored just the array of points.
            val o = if (s.trimStart().startsWith("[")) JSONObject().put("points", JSONArray(s)) else JSONObject(s)
            val arr = o.getJSONArray("points")
            return Calibration(
                (0 until arr.length()).map {
                    val p = arr.getJSONObject(it)
                    val target = p.getDouble("target")
                    CalPoint(target, CctDuv(p.getDouble("cct"), p.getDouble("duv")),
                        targetDuv = p.optDouble("targetDuv", 0.0), setKelvin = p.optDouble("set", target))
                },
                createdAt = o.optLong("createdAt", 0L),
                source = if (o.has("source")) o.getString("source") else null,
            )
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

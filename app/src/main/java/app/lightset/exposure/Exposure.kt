package app.lightset.exposure

import kotlin.math.abs
import kotlin.math.log2

/** Camera settings that set exposure. [bias] is how many stops over a mid-grey exposure to aim. */
data class ExposureSettings(
    val fNumber: Double = 2.8,
    val iso: Double = 800.0,
    val angle: Double = 180.0,
    val fps: Double = 24.0,
    val ndStops: Double = 0.0,
    val bias: Double = 0.0,
) {
    /** Shutter time in seconds. */
    val shutter: Double get() = angle / 360.0 / fps
}

/** The settings a user can lock; the rest are worked out. [LIGHT] locked holds the reading. */
enum class ExposureParam { LIGHT, APERTURE, ISO, SHUTTER, ND }

/**
 * Incident-light exposure: N²/t = E·S/C, with E in lux and C = 250, the usual
 * calibration constant for incident meters reading lux (so EV100 = log2(E / 2.5)).
 */
object Exposure {
    const val C = 250.0

    /** Within this many stops counts as correctly exposed. */
    const val TOLERANCE = 1.0 / 6

    val APERTURES = listOf(
        1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1,
        8.0, 9.0, 10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0,
    )
    val ISOS = listOf(
        80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 320.0, 400.0, 500.0, 640.0, 800.0, 1000.0, 1250.0, 1600.0,
        2000.0, 2500.0, 3200.0, 4000.0, 5000.0, 6400.0, 8000.0, 10000.0, 12800.0, 16000.0, 20000.0,
        25600.0, 32000.0, 40000.0, 51200.0, 64000.0, 80000.0, 102400.0,
    )
    val ANGLES = listOf(11.25, 15.0, 22.5, 30.0, 45.0, 60.0, 72.0, 90.0, 120.0, 144.0, 150.0, 172.8, 180.0, 216.0, 240.0, 270.0, 300.0, 360.0)
    val FRAME_RATES = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 48.0, 50.0, 59.94, 60.0, 100.0, 119.88, 120.0)

    /** ND in third stops, 0–10 stops (ND 0.1 per third). */
    val ND_STOPS = (0..30).map { it / 3.0 }
    val BIASES = (-9..9).map { it / 3.0 }

    fun ev100(lux: Double): Double = log2(lux * 100 / C)

    fun luxForEv100(ev: Double): Double = Math.pow(2.0, ev) * C / 100

    /** Stops over (+) or under (−) the aimed exposure that [s] gives in [lux]. */
    fun error(lux: Double, s: ExposureSettings): Double =
        log2(lux * s.shutter * s.iso / (s.fNumber * s.fNumber * C)) - s.ndStops - s.bias

    class Result(val settings: ExposureSettings, val stopsOff: Double) {
        val ok: Boolean get() = abs(stopsOff) <= TOLERANCE
    }

    /**
     * Keeps [locked] settings and picks the rest to expose [lux] correctly. ND goes in
     * first (from none), then aperture, ISO and shutter angle move from their current
     * values, each to a standard third-stop step. Whatever's left is [Result.stopsOff]:
     * negative means more light is needed.
     */
    fun solve(lux: Double, s: ExposureSettings, locked: Set<ExposureParam>): Result {
        var cur = if (ExposureParam.ND in locked) s else s.copy(ndStops = 0.0)
        for (p in listOf(ExposureParam.ND, ExposureParam.APERTURE, ExposureParam.ISO, ExposureParam.SHUTTER)) {
            if (p in locked) continue
            val e = error(lux, cur)
            if (abs(e) <= TOLERANCE) break
            // Each setting's own exposure in stops, and the value that removes the error.
            cur = when (p) {
                ExposureParam.ND -> cur.copy(ndStops = nearest(ND_STOPS, cur.ndStops + e) { it })
                ExposureParam.APERTURE -> cur.copy(fNumber = nearest(APERTURES, 2 * log2(cur.fNumber) + e) { 2 * log2(it) })
                ExposureParam.ISO -> cur.copy(iso = nearest(ISOS, log2(cur.iso) - e) { log2(it) })
                ExposureParam.SHUTTER -> cur.copy(angle = nearest(ANGLES, log2(cur.angle) - e) { log2(it) })
                ExposureParam.LIGHT -> cur
            }
        }
        return Result(cur, error(lux, cur))
    }

    /** The list entry after or before [value] ([dir] = ±1), staying in range. */
    fun step(list: List<Double>, value: Double, dir: Int): Double {
        val i = list.indexOf(nearest(list, value) { it })
        return list[(i + dir).coerceIn(0, list.size - 1)]
    }

    private fun nearest(list: List<Double>, target: Double, stops: (Double) -> Double): Double =
        list.minBy { abs(stops(it) - target) }
}

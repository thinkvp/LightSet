package app.lightset.color

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** CIE 1931 chromaticity. */
data class Xy(val x: Double, val y: Double)

/** CIE 1960 UCS chromaticity — the space CCT and Duv are defined in. */
data class Uv(val u: Double, val v: Double)

/**
 * Correlated colour temperature and distance from the Planckian locus.
 * Positive [duv] sits above the locus (green), negative below it (magenta).
 */
data class CctDuv(val cct: Double, val duv: Double) {
    val mired: Double get() = mired(cct)
}

fun mired(kelvin: Double): Double = 1e6 / kelvin

fun xyzToXy(x: Double, y: Double, z: Double): Xy {
    val sum = x + y + z
    require(sum > 0) { "XYZ sum must be positive" }
    return Xy(x / sum, y / sum)
}

fun xyToUv(c: Xy): Uv {
    val d = -2 * c.x + 12 * c.y + 3
    return Uv(4 * c.x / d, 6 * c.y / d)
}

fun uvToXy(c: Uv): Xy {
    val d = 2 * c.u - 8 * c.v + 4
    return Xy(3 * c.u / d, 2 * c.v / d)
}

/** XYZ with Y = 1 for a chromaticity. */
fun xyToXyz(c: Xy): DoubleArray = doubleArrayOf(c.x / c.y, 1.0, (1 - c.x - c.y) / c.y)

/**
 * Planckian locus in CIE 1960 uv, Krystek (1985) rational approximation.
 * Accurate to |Δuv| < 8e-5 over 1000–15000 K; extrapolates smoothly beyond.
 */
fun planckUv(kelvin: Double): Uv {
    val t = kelvin
    val u = (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) /
        (1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t)
    val v = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) /
        (1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t)
    return Uv(u, v)
}

/** Unit normal to the locus at [kelvin], pointing towards green (+v side). */
private fun locusNormal(kelvin: Double): Uv {
    val h = kelvin * 1e-4
    val a = planckUv(kelvin - h)
    val b = planckUv(kelvin + h)
    val tu = b.u - a.u
    val tv = b.v - a.v
    val len = hypot(tu, tv)
    // Rotate the tangent 90°, then orient so the normal points to +v.
    var nu = -tv / len
    var nv = tu / len
    if (nv < 0) {
        nu = -nu
        nv = -nv
    }
    return Uv(nu, nv)
}

private const val MIN_MIRED = 1e6 / 25000.0
private const val MAX_MIRED = 1e6 / 1000.0

/** CCT and Duv by nearest point on the locus, searched in mired space. */
fun cctDuv(c: Xy): CctDuv {
    val p = xyToUv(c)
    fun dist2(m: Double): Double {
        val l = planckUv(1e6 / m)
        return (p.u - l.u) * (p.u - l.u) + (p.v - l.v) * (p.v - l.v)
    }

    // Coarse scan to bracket the minimum, then golden-section refine.
    val steps = 200
    var best = MIN_MIRED
    var bestD = Double.MAX_VALUE
    for (i in 0..steps) {
        val m = MIN_MIRED + (MAX_MIRED - MIN_MIRED) * i / steps
        val d = dist2(m)
        if (d < bestD) {
            bestD = d
            best = m
        }
    }
    val span = (MAX_MIRED - MIN_MIRED) / steps
    var lo = (best - span).coerceAtLeast(MIN_MIRED)
    var hi = (best + span).coerceAtMost(MAX_MIRED)
    val g = 0.6180339887498949
    var m1 = hi - g * (hi - lo)
    var m2 = lo + g * (hi - lo)
    var d1 = dist2(m1)
    var d2 = dist2(m2)
    repeat(80) {
        if (d1 < d2) {
            hi = m2; m2 = m1; d2 = d1
            m1 = hi - g * (hi - lo); d1 = dist2(m1)
        } else {
            lo = m1; m1 = m2; d1 = d2
            m2 = lo + g * (hi - lo); d2 = dist2(m2)
        }
    }
    val kelvin = 1e6 / ((lo + hi) / 2)
    val l = planckUv(kelvin)
    val n = locusNormal(kelvin)
    val offU = p.u - l.u
    val offV = p.v - l.v
    val s = sign(offU * n.u + offV * n.v)
    return CctDuv(kelvin, s * hypot(offU, offV))
}

/** Chromaticity for a CCT/Duv pair — the inverse of [cctDuv]. */
fun xyFromCctDuv(kelvin: Double, duv: Double): Xy {
    val l = planckUv(kelvin)
    val n = locusNormal(kelvin)
    return uvToXy(Uv(l.u + duv * n.u, l.v + duv * n.v))
}

/** CIE daylight locus chromaticity, valid 4000–25000 K. */
fun daylightXy(kelvin: Double): Xy {
    val t = kelvin
    val x = if (t <= 7000) {
        -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
    } else {
        -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040
    }
    val y = -3.000 * x * x + 2.870 * x - 0.275
    return Xy(x, y)
}

/** Difference between a measured light and the reference it should match. */
data class Delta(val kelvin: Double, val mired: Double, val duv: Double) {
    fun withinTolerance(maxMired: Double, maxDuv: Double): Boolean =
        abs(mired) <= maxMired && abs(duv) <= maxDuv
}

fun delta(measured: CctDuv, reference: CctDuv): Delta = Delta(
    kelvin = measured.cct - reference.cct,
    mired = measured.mired - reference.mired,
    duv = measured.duv - reference.duv,
)

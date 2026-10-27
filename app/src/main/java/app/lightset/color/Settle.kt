package app.lightset.color

import kotlin.math.abs

/** How far apart two back-to-back readings of the same light are. */
data class Spread(val mired: Double, val duv: Double) {
    fun within(limit: Spread): Boolean = mired <= limit.mired && duv <= limit.duv

    companion object {
        /** Readings this close mean the light has finished changing. */
        val SETTLED = Spread(mired = 2.0, duv = 0.0005)

        fun between(a: CctDuv, b: CctDuv) = Spread(abs(a.mired - b.mired), abs(a.duv - b.duv))
    }
}

/** Mean of two readings, with CCT averaged in mired. */
fun mean(a: CctDuv, b: CctDuv) = CctDuv(2e6 / (a.mired + b.mired), (a.duv + b.duv) / 2)

/**
 * True when two ambient samples differ in every channel by less than [fraction] of
 * the lit card's brightest channel, i.e. any light still fading out no longer matters.
 */
fun ambientSettled(a: DoubleArray, b: DoubleArray, lit: DoubleArray, fraction: Double = 0.01): Boolean {
    val tol = fraction * lit.max()
    return (0..2).all { abs(a[it] - b[it]) <= tol }
}

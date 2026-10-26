package app.lightset.color

import kotlin.math.max

/**
 * Isolates one light's contribution to the card. Linear sensor RGB adds, so at a
 * fixed exposure (light on) − (light off) is the light alone, however much ambient
 * there is, as long as the light's share is large enough to rise above the noise.
 */
object AmbientSubtraction {
    class Result(val lightRgb: DoubleArray, val lightShare: Double)

    fun isolate(on: DoubleArray, ambient: DoubleArray): Result {
        val light = DoubleArray(3) { max(0.0, on[it] - ambient[it]) }
        val total = on.sum()
        return Result(light, if (total > 0) light.sum() / total else 0.0)
    }

    /** Below this share of the card's signal, the light's colour can't be read reliably. */
    const val MIN_SHARE = 0.15
}

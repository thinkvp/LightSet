package app.lightset.color

import kotlin.math.abs
import kotlin.math.roundToInt

/** What a light can be set to. [gmMin] = [gmMax] = 0 for lights without G/M. */
data class LightLimits(val cctMin: Int, val cctMax: Int, val gmMin: Int, val gmMax: Int) {
    val hasGm: Boolean get() = gmMin != gmMax

    companion object {
        val FORZA_60C = LightLimits(1800, 20000, -100, 100)
    }
}

/** A light's CCT (kelvin) and G/M (Nanlite units, positive = green). */
data class LightSetting(val cct: Int, val gm: Int) {
    val mired: Double get() = mired(cct.toDouble())
}

/**
 * Closed-loop matching of one light to a target reading. Each step takes what the
 * camera measured on the card and returns the next setting to send. CCT is adjusted
 * in mired (how far apart colour temps look), G/M in Duv. The gains, i.e. how much
 * a reading moves per unit of setting, start from rough priors and are re-estimated
 * from each step, so lights that drift or don't follow their dial still converge.
 */
class DialIn(
    private val target: CctDuv,
    private val limits: LightLimits,
    private val tolMired: Double = 5.0,
    private val tolDuv: Double = 0.0015,
) {
    sealed interface Step {
        data class Send(val setting: LightSetting) : Step
        data class Matched(val setting: LightSetting) : Step
        /** CCT matched, but the light can't reach the target tint (no G/M, or out of green/magenta). */
        data class Partial(val setting: LightSetting, val reason: String) : Step
        /** Can't get closer: the light is at a limit of its range. */
        data class OutOfRange(val setting: LightSetting, val reason: String) : Step
    }

    /** Measured mired change per mired of setting. */
    var miredGain = 1.0
        private set

    /** Measured Duv change per G/M unit (≈ −9 units for −0.0022 Duv on a Forza 60C). */
    var duvPerGm = 2.4e-4
        private set

    /** Consecutive sizeable moves that didn't change the reading. */
    private var cctNoEffect = 0
    private var gmNoEffect = 0

    /**
     * Set once G/M moves stop changing the reading. Many lights can add little green at
     * warm CCTs (a Forza 60C manages ~2 G at 3100 K but ~20 G at 4200 K); from then on
     * G/M is held at the best value seen and only CCT is adjusted.
     */
    private var gmHeld: Int? = null
    private var bestGm: Pair<Int, Double>? = null

    private var current: LightSetting? = null
    private var previous: Pair<LightSetting, CctDuv>? = null

    /** First guess: the target's CCT, and G/M from its tint. */
    fun initial(): LightSetting = clamp(target.mired, target.duv / duvPerGm).also { current = it }

    fun next(measured: CctDuv): Step {
        val set = checkNotNull(current) { "call initial() first" }
        val d = delta(measured, target)
        if (bestGm.let { it == null || abs(d.duv) < it.second }) bestGm = set.gm to abs(d.duv)
        val cctOk = abs(d.mired) <= tolMired
        val tintOk = abs(d.duv) <= tolDuv
        // Already at the end of the G/M range in the direction the tint needs.
        val gmAtLimit = limits.hasGm && !tintOk &&
            ((d.duv < 0 && set.gm >= limits.gmMax) || (d.duv > 0 && set.gm <= limits.gmMin))
        val gmDone = tintOk || !limits.hasGm || gmHeld != null || gmAtLimit
        if (cctOk && tintOk) return Step.Matched(set)
        if (cctOk && gmDone) return Step.Partial(set, gelAdvice(d.duv, held = limits.hasGm))

        learn(set, measured)
        previous = set to measured
        if (gmNoEffect >= 2 && gmHeld == null) gmHeld = bestGm?.first ?: set.gm
        if (cctNoEffect >= 2) {
            return Step.OutOfRange(set, "Changing CCT isn't changing the light's output — check the light is on the card")
        }

        // Once CCT is within tolerance, leave it: chasing noise only costs readings.
        val wantMired = if (cctOk) set.mired else set.mired - d.mired / miredGain
        val wantGm = gmHeld?.toDouble() ?: (set.gm - d.duv / duvPerGm)
        var next = clamp(wantMired, wantGm)
        // Rounding can leave the setting unchanged; nudge by the smallest step instead.
        if (next == set) {
            next = clamp(
                if (cctOk) set.mired else set.mired - 3.0 * kotlin.math.sign(d.mired),
                if (gmDone) set.gm.toDouble() else set.gm - kotlin.math.sign(d.duv),
            )
        }
        if (next == set) {
            // Only the tint is left and G/M can't move further: as close as this light gets.
            if (cctOk) return Step.Partial(set, gelAdvice(d.duv, held = limits.hasGm))
            return Step.OutOfRange(set, "Colour temp is at the light's ${if (d.mired > 0) "coolest" else "warmest"} setting")
        }
        current = next
        return Step.Send(next)
    }

    /** [duvError] is measured − target. */
    private fun gelAdvice(duvError: Double, held: Boolean): String {
        val needGreen = duvError < 0
        val short = abs(duvError) * 1000
        val why = when {
            !limits.hasGm -> "This light has no G/M"
            held -> "The light can't go any ${if (needGreen) "greener" else "more magenta"} at this colour temp"
            else -> "Tint is off"
        }
        return "CCT matched. $why: tint is %.1f short. Add a %s gel.".format(
            short, if (needGreen) "plus-green" else "minus-green (magenta)",
        )
    }

    /** Secant update of the gains from the last move, damped and clamped. */
    private fun learn(set: LightSetting, measured: CctDuv) {
        val (prevSet, prevMeasured) = previous ?: return
        val dSetM = set.mired - prevSet.mired
        if (abs(dSetM) >= 3) {
            val g = (measured.mired - prevMeasured.mired) / dSetM
            if (g > 0) miredGain = (0.5 * miredGain + 0.5 * g).coerceIn(0.5, 2.0)
        }
        // A move that should be clearly visible but barely shifts the reading.
        if (abs(dSetM) >= 10) {
            cctNoEffect = if (abs(measured.mired - prevMeasured.mired) < 0.2 * abs(dSetM)) cctNoEffect + 1 else 0
        }
        val dGm = set.gm - prevSet.gm
        if (abs(dGm) >= 3) {
            val g = (measured.duv - prevMeasured.duv) / dGm
            if (g > 0) duvPerGm = (0.5 * duvPerGm + 0.5 * g).coerceIn(0.8e-4, 8e-4)
        }
        if (abs(dGm) >= 10) {
            gmNoEffect = if (abs(measured.duv - prevMeasured.duv) < 0.2 * abs(dGm) * 0.8e-4) gmNoEffect + 1 else 0
        }
    }

    private fun clamp(mired: Double, gm: Double): LightSetting {
        val k = (1e6 / mired / 10).roundToInt() * 10
        val g = if (limits.hasGm) gm.roundToInt() else 0
        return LightSetting(k.coerceIn(limits.cctMin, limits.cctMax), g.coerceIn(limits.gmMin, limits.gmMax))
    }
}

package app.lightset.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class DialInTest {
    /**
     * A light that doesn't do what its dial says: the camera sees an offset and a
     * different slope in both CCT and tint, plus a little measurement noise.
     */
    private class SimLight(
        val miredOffset: Double, val miredSlope: Double,
        val duvOffset: Double, val duvPerGm: Double,
        val noise: Double = 0.0, seed: Int = 1,
    ) {
        private val rnd = Random(seed)
        fun measure(s: LightSetting): CctDuv {
            val m = miredOffset + miredSlope * s.mired + noise * rnd.nextDouble(-1.0, 1.0)
            val duv = duvOffset + duvPerGm * s.gm + noise * 1e-4 * rnd.nextDouble(-1.0, 1.0)
            return CctDuv(1e6 / m, duv)
        }
    }

    private fun run(target: CctDuv, light: SimLight, limits: LightLimits = LightLimits.FORZA_60C, max: Int = 10):
        Pair<DialIn.Step, Int> {
        val d = DialIn(target, limits)
        var s = d.initial()
        repeat(max) { i ->
            when (val step = d.next(light.measure(s))) {
                is DialIn.Step.Send -> s = step.setting
                else -> return step to i + 1
            }
        }
        error("didn't finish in $max steps")
    }

    @Test
    fun perfectLightMatchesFirstTime() {
        val (step, n) = run(CctDuv(5530.0, -0.0022), SimLight(0.0, 1.0, 0.0, 2.4e-4))
        assertTrue(step is DialIn.Step.Matched)
        assertEquals(1, n)
    }

    @Test
    fun convergesDespiteOffsetsAndSlope() {
        val light = SimLight(miredOffset = 12.0, miredSlope = 0.85, duvOffset = 0.003, duvPerGm = 3.5e-4, noise = 1.0)
        for (target in listOf(CctDuv(3200.0, 0.0), CctDuv(5530.0, -0.0022), CctDuv(4300.0, 0.004))) {
            val (step, n) = run(target, light)
            assertTrue("$target: $step", step is DialIn.Step.Matched)
            assertTrue("$target took $n steps", n <= 6)
        }
    }

    @Test
    fun reportsOutOfRange() {
        val warmOnly = LightLimits(2700, 6500, -100, 100)
        val (step, _) = run(CctDuv(2400.0, 0.0), SimLight(0.0, 1.0, 0.0, 2.4e-4), warmOnly)
        assertTrue(step is DialIn.Step.OutOfRange)
    }

    @Test
    fun noGmLightMatchesCctAndFlagsTint() {
        val bicolour = LightLimits(2700, 6500, 0, 0)
        val (step, _) = run(CctDuv(4000.0, -0.004), SimLight(0.0, 1.0, 0.0, 2.4e-4), bicolour)
        // CCT matches; tint can't be fixed, so the loop finishes with gel advice.
        assertTrue("$step", step is DialIn.Step.Partial)
        assertTrue("$step", (step as DialIn.Step.Partial).reason.contains("magenta"))
    }

    @Test
    fun holdsGmAndMatchesCctWhenGreenRunsOut() {
        // The light ignores G/M; the target needs a big tint change and a CCT change.
        val light = SimLight(miredOffset = 15.0, miredSlope = 1.0, duvOffset = 0.0, duvPerGm = 0.0)
        val (step, n) = run(CctDuv(4300.0, 0.006), light)
        assertTrue("$step", step is DialIn.Step.Partial)
        assertTrue("$step", (step as DialIn.Step.Partial).reason.contains("plus-green"))
        val m = light.measure(step.setting)
        assertTrue("cct ${m.cct}", abs(delta(m, CctDuv(4300.0, 0.006)).mired) <= 5.0)
        assertTrue("took $n steps", n <= 7)
    }

    @Test
    fun greenSaturatesButMagentaWorks() {
        // Like a Forza 60C at 3100 K: magenta at full gain, green at a tenth of it.
        val light = object {
            fun measure(s: LightSetting): CctDuv {
                val gain = if (s.gm > 0) 2.4e-5 else 2.4e-4
                return CctDuv(3100.0, 0.0007 + gain * s.gm)
            }
        }
        val d = DialIn(CctDuv(3100.0, 0.006), LightLimits.FORZA_60C)
        var s = d.initial()
        var last: DialIn.Step? = null
        for (i in 1..10) {
            val step = d.next(light.measure(s))
            last = step
            if (step is DialIn.Step.Send) s = step.setting else break
        }
        assertTrue("$last", last is DialIn.Step.Partial)
    }
}

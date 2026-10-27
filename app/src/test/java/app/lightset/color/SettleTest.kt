package app.lightset.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettleTest {
    @Test
    fun spreadIsInMiredAndDuv() {
        val s = Spread.between(CctDuv(5000.0, 0.001), CctDuv(4950.0, 0.0012))
        assertEquals(2.02, s.mired, 0.01)
        assertEquals(0.0002, s.duv, 1e-9)
        assertFalse(s.within(Spread.SETTLED))
        assertTrue(Spread.between(CctDuv(5000.0, 0.0), CctDuv(4980.0, 0.0004)).within(Spread.SETTLED))
    }

    @Test
    fun meanAveragesMired() {
        val m = mean(CctDuv(2500.0, 0.002), CctDuv(5000.0, 0.0))
        assertEquals(300.0, m.mired, 1e-9)
        assertEquals(0.001, m.duv, 1e-12)
    }

    @Test
    fun ambientSettlesRelativeToLitCard() {
        val lit = doubleArrayOf(0.4, 0.5, 0.3)
        assertTrue(ambientSettled(doubleArrayOf(0.02, 0.03, 0.01), doubleArrayOf(0.024, 0.033, 0.012), lit))
        assertFalse(ambientSettled(doubleArrayOf(0.02, 0.03, 0.01), doubleArrayOf(0.04, 0.03, 0.01), lit))
    }
}

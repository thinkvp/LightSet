package app.lightset.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun comparesNumbersNotText() {
        assertTrue(UpdateChecker.isNewer("0.1.10", "0.1.9"))
        assertFalse(UpdateChecker.isNewer("0.1.9", "0.1.10"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.1.12", "0.1.12"))
    }

    @Test
    fun missingPartsCountAsZero() {
        assertTrue(UpdateChecker.isNewer("0.1.1", "0.1"))
        assertFalse(UpdateChecker.isNewer("0.1", "0.1.0"))
    }
}

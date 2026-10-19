package dk.frem.app.hearing

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class NoiseProcessorTest {
    private fun tone(frequency: Double, amplitude: Double = .1): ShortArray =
        ShortArray(48_000) {
            (sin(2 * PI * frequency * it / 48_000) * amplitude * 32767).roundToInt().toShort()
        }

    private fun level(frequency: Double): Double {
        val processor = NoiseProcessor()
        processor.process(tone(frequency))
        return processor.process(tone(frequency)).dbfs!!
    }

    @Test
    fun oneKilohertzHasUnityGainAndLowFrequenciesAreAWeighted() {
        val reference = level(1000.0)
        assertEquals(-23.0103, reference, .02)
        assertEquals(-19.1, level(100.0) - reference, .3)
        assertEquals(1.2, level(2000.0) - reference, .3)
    }

    @Test
    fun warmupSilencedZeroAndClippedInputDoNotBecomeExposure() {
        val processor = NoiseProcessor()
        assertFalse(processor.process(tone(1000.0)).valid)
        assertFalse(processor.process(tone(1000.0), true).valid)
        assertFalse(processor.process(ShortArray(48_000)).valid)
        assertFalse(processor.process(ShortArray(48_000) { Short.MAX_VALUE }).valid)
        assertTrue(processor.process(tone(1000.0)).valid)
    }

    @Test
    fun equivalentLevelsUseEnergyAveragesAndNeedCalibrationForDose() {
        val session =
            NoiseSession("noise", 0, measuredMillis = 2000, energyMillis = 1000 * (.0001 + .01))
        assertEquals(-22.967, session.rawLeq!!, .01)
        assertNull(session.leqDbA)
        assertNull(session.dosePercent)
        assertEquals(87.033, session.copy(calibrationOffsetDb = 110.0).leqDbA!!, .01)
    }

    @Test
    fun doseUsesMeasuredDurationAndThreeDecibelExchange() {
        assertEquals(1.0, noiseDoseFraction(85.0, 28_800_000), 1e-10)
        assertEquals(1.0, noiseDoseFraction(88.0, 14_400_000), 1e-10)
        val session =
            NoiseSession(
                "noise",
                0,
                calibrationOffsetDb = 100.0,
                measuredMillis = 1000,
                unmeasuredMillis = 86_400_000,
                doseFraction = .25,
            )
        assertEquals(25.0, session.dosePercent!!, 0.0)
    }
}

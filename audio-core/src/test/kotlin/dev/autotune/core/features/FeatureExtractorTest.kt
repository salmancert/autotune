package dev.autotune.core.features

import dev.autotune.core.TestSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureExtractorTest {

    private val format = AnalysisFormat.DEFAULT

    private fun analyse(signal: FloatArray): FloatArray {
        val extractor = FeatureExtractor(format)
        var last = FloatArray(FeatureVector.SIZE)
        extractor.process(signal, 0, signal.size) { _, features -> last = features.copyOf() }
        return last
    }

    @Test
    fun `emits one snapshot per hop`() {
        val extractor = FeatureExtractor(format)
        val signal = TestSignals.sine(format.frameSize + format.hopSize * 10, format.sampleRate, 440.0, -20.0)
        var frames = 0
        extractor.process(signal, 0, signal.size) { _, _ -> frames++ }
        assertEquals(11, frames)
    }

    @Test
    fun `syllabic speech shows gaps and 4 Hz modulation that a held note does not`() {
        val seconds = 4
        val speech = analyse(TestSignals.syllabic(format.sampleRate * seconds, format.sampleRate, -20.0))
        val tone = analyse(TestSignals.sine(format.sampleRate * seconds, format.sampleRate, 220.0, -20.0))

        assertTrue(
            "lowEnergyRatio speech=${speech[FeatureVector.LOW_ENERGY_RATIO]} tone=${tone[FeatureVector.LOW_ENERGY_RATIO]}",
            speech[FeatureVector.LOW_ENERGY_RATIO] > tone[FeatureVector.LOW_ENERGY_RATIO] + 0.1f,
        )
        assertTrue(
            "modulation speech=${speech[FeatureVector.MODULATION_4HZ]} tone=${tone[FeatureVector.MODULATION_4HZ]}",
            speech[FeatureVector.MODULATION_4HZ] > tone[FeatureVector.MODULATION_4HZ],
        )
        assertTrue(
            "level variance speech=${speech[FeatureVector.LEVEL_STD]}",
            speech[FeatureVector.LEVEL_STD] > tone[FeatureVector.LEVEL_STD] + 5f,
        )
    }

    @Test
    fun `a periodic tone is measured as harmonic and noise is not`() {
        val tone = analyse(TestSignals.sine(format.sampleRate * 3, format.sampleRate, 200.0, -20.0))
        val noise = analyse(TestSignals.noise(format.sampleRate * 3, -20.0))

        assertTrue("tone harmonicity ${tone[FeatureVector.HARMONICITY_MEAN]}", tone[FeatureVector.HARMONICITY_MEAN] > 0.7f)
        assertTrue("noise harmonicity ${noise[FeatureVector.HARMONICITY_MEAN]}", noise[FeatureVector.HARMONICITY_MEAN] < 0.4f)
        assertTrue("tone voiced ${tone[FeatureVector.VOICED_RATIO]}", tone[FeatureVector.VOICED_RATIO] > 0.9f)
        // Flatness is log(geometric/arithmetic): near zero for noise, very
        // negative for a single partial.
        assertTrue(
            "flatness tone=${tone[FeatureVector.FLATNESS_MEAN]} noise=${noise[FeatureVector.FLATNESS_MEAN]}",
            tone[FeatureVector.FLATNESS_MEAN] < noise[FeatureVector.FLATNESS_MEAN] - 1f,
        )
    }

    @Test
    fun `band energy lands in the band the signal occupies`() {
        val low = analyse(TestSignals.sine(format.sampleRate * 2, format.sampleRate, 120.0, -20.0))
        val speechBand = analyse(TestSignals.sine(format.sampleRate * 2, format.sampleRate, 1500.0, -20.0))
        val high = analyse(TestSignals.sine(format.sampleRate * 2, format.sampleRate, 7000.0, -20.0))

        assertTrue(low[FeatureVector.BAND_LOW_MEAN] > 0.8f)
        assertTrue(speechBand[FeatureVector.BAND_DIALOGUE_MEAN] > 0.8f)
        assertTrue(high[FeatureVector.BAND_HIGH_MEAN] > 0.8f)
    }

    @Test
    fun `resetting clears the context`() {
        val extractor = FeatureExtractor(format)
        val signal = TestSignals.sine(format.sampleRate * 2, format.sampleRate, 440.0, -20.0)
        extractor.process(signal, 0, signal.size) { _, _ -> }
        assertTrue(extractor.isPrimed)
        extractor.reset()
        assertTrue(!extractor.isPrimed)
    }
}

package dev.autotune.core.engine

import dev.autotune.core.TestSignals
import dev.autotune.core.features.AnalysisFormat
import dev.autotune.core.ml.AudioClassifier
import dev.autotune.core.ml.ClassScores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advert-break case: content that is loud and has had every dynamic
 * squeezed out of it, for far longer than a dramatic moment would last.
 */
class SustainedTrimTest {

    private val format = AnalysisFormat.DEFAULT
    private val sampleRate = format.sampleRate

    private class FixedClassifier(private val speech: Float) : AudioClassifier {
        override fun classify(features: FloatArray, out: ClassScores) {
            out.set(floatArrayOf(speech, 1f - speech, 0f))
        }
    }

    private fun engine(config: StabilizerConfig = StabilizerConfig(), speech: Float = 0f) =
        StabilizerEngine(config, format, FixedClassifier(speech))

    /** A steady tone has no level variation at all, which is the signature. */
    private fun StabilizerEngine.feedFlat(seconds: Double, dbfs: Double): StabilizerState {
        val samples = (sampleRate * seconds).toInt()
        return process(TestSignals.sine(samples, sampleRate, 900.0, dbfs), samples)
    }

    @Test
    fun `loud flat content keeps losing ground the longer it runs`() {
        val engine = engine()
        val early = engine.feedFlat(seconds = 5.0, dbfs = -12.0)
        val later = engine.feedFlat(seconds = 20.0, dbfs = -12.0)

        assertTrue("no trim accumulated: ${later.sustainedTrimDb}", later.sustainedTrimDb < -3f)
        assertTrue(
            "trim did not grow: ${early.sustainedTrimDb} then ${later.sustainedTrimDb}",
            later.sustainedTrimDb < early.sustainedTrimDb - 1f,
        )
        assertTrue("the trim should show up in the gain", later.gainDb < early.gainDb)
    }

    @Test
    fun `the trim releases once the content stops being loud`() {
        val engine = engine()
        engine.feedFlat(seconds = 25.0, dbfs = -12.0)
        val loud = engine.state.sustainedTrimDb
        assertTrue("expected a trim, got $loud", loud < -3f)

        val quiet = engine.feedFlat(seconds = 6.0, dbfs = -30.0)
        assertEquals(0.0, quiet.sustainedTrimDb.toDouble(), 0.5)
    }

    @Test
    fun `dialogue at the target never accumulates a trim`() {
        // Speech has natural gaps, which is exactly what the trim looks for the
        // absence of. It must not punish an ordinary conversation.
        val engine = engine(speech = 1f)
        val samples = (sampleRate * 25.0).toInt()
        val state = engine.process(
            TestSignals.syllabic(samples, sampleRate, -20.0),
            samples,
        )
        assertEquals(0.0, state.sustainedTrimDb.toDouble(), 0.5)
    }

    @Test
    fun `a short loud moment is left alone`() {
        // A three-second climax is drama, not an advert; the onset delay exists
        // so it is not treated as one.
        val engine = engine()
        val state = engine.feedFlat(seconds = 3.0, dbfs = -12.0)
        assertEquals(0.0, state.sustainedTrimDb.toDouble(), 0.2)
    }

    @Test
    fun `sport pulls adverts down harder than films do`() {
        val films = engine(StabilizerConfig(preset = ListeningPreset.MOVIES)).feedFlat(25.0, -12.0)
        val sport = engine(StabilizerConfig(preset = ListeningPreset.SPORTS)).feedFlat(25.0, -12.0)
        assertTrue(
            "films ${films.sustainedTrimDb} sport ${sport.sustainedTrimDb}",
            sport.sustainedTrimDb < films.sustainedTrimDb - 1f,
        )
    }

    @Test
    fun `the trim can be turned off entirely`() {
        val engine = engine(StabilizerConfig(sustainedTrimDb = 0f))
        val state = engine.feedFlat(seconds = 25.0, dbfs = -12.0)
        assertEquals(0.0, state.sustainedTrimDb.toDouble(), 1e-6)
    }
}

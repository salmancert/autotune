package dev.autotune.core.engine

import dev.autotune.core.TestSignals
import dev.autotune.core.features.AnalysisFormat
import dev.autotune.core.ml.AudioClass
import dev.autotune.core.ml.AudioClassifier
import dev.autotune.core.ml.ClassScores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Behaviour of the control law.
 *
 * The classifier is stubbed here on purpose: these tests are about what the
 * engine does *given* a belief about the content, including when that belief is
 * wrong. How well the real model forms that belief is measured separately, in
 * :model-training.
 */
class StabilizerEngineTest {

    private val format = AnalysisFormat.DEFAULT
    private val sampleRate = format.sampleRate

    private class FixedClassifier(
        private val speech: Float,
        private val music: Float,
        private val effects: Float,
    ) : AudioClassifier {
        override fun classify(features: FloatArray, out: ClassScores) {
            out.set(floatArrayOf(speech, music, effects))
        }
    }

    private fun speechEngine(config: StabilizerConfig = StabilizerConfig()) =
        StabilizerEngine(config, format, FixedClassifier(1f, 0f, 0f))

    private fun musicEngine(config: StabilizerConfig = StabilizerConfig()) =
        StabilizerEngine(config, format, FixedClassifier(0f, 1f, 0f))

    private fun StabilizerEngine.feed(seconds: Double, dbfs: Double, frequency: Double = 1000.0): StabilizerState {
        val samples = (sampleRate * seconds).toInt()
        return process(TestSignals.sine(samples, sampleRate, frequency, dbfs), samples)
    }

    @Test
    fun `a sudden loud music cue is pulled down within a few hundred milliseconds`() {
        val engine = musicEngine()
        // Quiet music sitting under the ceiling: nothing to do.
        val settled = engine.feed(seconds = 2.0, dbfs = -30.0)
        assertTrue("idle gain ${settled.gainDb}", abs(settled.gainDb) < 1.5f)

        val ducked = engine.feed(seconds = 0.3, dbfs = -8.0)
        assertTrue("gain after the cue ${ducked.gainDb}", ducked.gainDb < -8f)
        assertTrue("still classified as music", ducked.dominantClass == AudioClass.MUSIC)
    }

    @Test
    fun `quiet dialogue is lifted toward the target`() {
        val engine = speechEngine()
        val state = engine.feed(seconds = 5.0, dbfs = -32.0)
        assertTrue("gain ${state.gainDb}", state.gainDb > 8f)
        // Corrected loudness should be close to the -20 LUFS target.
        assertEquals(-20.0, (state.momentaryLufs + state.gainDb).toDouble(), 2.5)
    }

    @Test
    fun `nothing is allowed above the ceiling even when the classifier is wrong`() {
        // The signal is loud music, but the model insists it is dialogue. The
        // backstop has to catch it anyway - that is its whole job.
        val engine = speechEngine()
        val state = engine.feed(seconds = 3.0, dbfs = -6.0)
        val corrected = state.momentaryLufs + state.gainDb
        val config = StabilizerConfig()
        assertTrue(
            "corrected loudness $corrected should stay under ${config.targetDialogueLufs + config.maxAboveTargetDb}",
            corrected <= config.targetDialogueLufs + config.maxAboveTargetDb + 1.0f,
        )
    }

    @Test
    fun `silence freezes the gain instead of amplifying the noise floor`() {
        val engine = speechEngine()
        engine.feed(seconds = 4.0, dbfs = -32.0)

        // The momentary window takes ~400 ms to empty, so settle first, then
        // check that further silence moves nothing at all.
        val settled = engine.process(TestSignals.silence(sampleRate))
        assertTrue("silence should not report signal", !settled.hasSignal)

        val later = engine.process(TestSignals.silence(sampleRate * 3))
        assertEquals(settled.gainDb.toDouble(), later.gainDb.toDouble(), 1e-6)
    }

    @Test
    fun `steady dialogue already at the target is left alone`() {
        val engine = speechEngine()
        engine.feed(seconds = 3.0, dbfs = -20.0)
        var minimum = Float.MAX_VALUE
        var maximum = -Float.MAX_VALUE
        repeat(30) {
            val state = engine.feed(seconds = 0.1, dbfs = -20.0)
            minimum = minOf(minimum, state.gainDb)
            maximum = maxOf(maximum, state.gainDb)
        }
        // Any wobble here would be heard as pumping on a static scene.
        assertTrue("gain moved between $minimum and $maximum", maximum - minimum < 1.0f)
    }

    @Test
    fun `disabled means bypassed`() {
        val engine = musicEngine(StabilizerConfig(enabled = false))
        val state = engine.feed(seconds = 3.0, dbfs = -6.0)
        assertEquals(0.0, state.gainDb.toDouble(), 1e-6)
    }

    @Test
    fun `zero strength is a bypass and full strength is not`() {
        val bypassed = musicEngine(StabilizerConfig(strength = 0f)).feed(seconds = 3.0, dbfs = -8.0)
        assertEquals(0.0, bypassed.gainDb.toDouble(), 0.05)

        val full = musicEngine(StabilizerConfig(strength = 1f)).feed(seconds = 3.0, dbfs = -8.0)
        assertTrue("full strength gain ${full.gainDb}", full.gainDb < -8f)
    }

    @Test
    fun `strength scales the correction proportionally`() {
        val half = musicEngine(StabilizerConfig(strength = 0.5f)).feed(seconds = 3.0, dbfs = -8.0)
        val full = musicEngine(StabilizerConfig(strength = 1f)).feed(seconds = 3.0, dbfs = -8.0)
        assertEquals(full.gainDb.toDouble() / 2.0, half.gainDb.toDouble(), 0.5)
    }

    @Test
    fun `night mode holds music further down`() {
        // Well inside the cut limit, so the difference is the ceiling and not
        // the clamp.
        val normal = musicEngine(StabilizerConfig(nightMode = false)).feed(seconds = 3.0, dbfs = -16.0)
        val night = musicEngine(StabilizerConfig(nightMode = true)).feed(seconds = 3.0, dbfs = -16.0)
        assertTrue("normal ${normal.gainDb} night ${night.gainDb}", night.gainDb < normal.gainDb - 2f)
    }

    @Test
    fun `the local media profile ducks harder than the youtube profile`() {
        val local = musicEngine().apply { profile = AppProfile.LOCAL_MEDIA }.feed(seconds = 3.0, dbfs = -12.0)
        val youtube = musicEngine().apply { profile = AppProfile.VIDEO_SHARING }.feed(seconds = 3.0, dbfs = -12.0)
        assertTrue("local ${local.gainDb} youtube ${youtube.gainDb}", local.gainDb < youtube.gainDb - 1f)
    }

    @Test
    fun `ducking is faster than releasing`() {
        val engine = musicEngine()
        engine.feed(seconds = 2.0, dbfs = -30.0)

        val duckedAfter100ms = engine.feed(seconds = 0.1, dbfs = -8.0).gainDb
        engine.feed(seconds = 1.0, dbfs = -8.0)
        val recoveredAfter100ms = engine.feed(seconds = 0.1, dbfs = -30.0).gainDb
        val floor = engine.state.gainDb

        assertTrue("duck reached $duckedAfter100ms in 100 ms", duckedAfter100ms < -4f)
        // Coming back has to be slow, or the release itself is audible.
        assertTrue("released to $recoveredAfter100ms from $floor", recoveredAfter100ms < -8f)
    }

    @Test
    fun `dialogue clarity is applied only while speech is quiet`() {
        val quiet = speechEngine().feed(seconds = 4.0, dbfs = -30.0)
        val loud = speechEngine().feed(seconds = 4.0, dbfs = -14.0)
        assertTrue("quiet clarity ${quiet.dialogueEqDb}", quiet.dialogueEqDb > 1.5f)
        assertTrue("loud clarity ${loud.dialogueEqDb}", loud.dialogueEqDb < 0.5f)
    }

    @Test
    fun `booming low-frequency effects get a low shelf trim`() {
        val engine = StabilizerEngine(StabilizerConfig(), format, FixedClassifier(0f, 0f, 1f))
        val samples = (sampleRate * 3.0).toInt()
        val state = engine.process(TestSignals.sine(samples, sampleRate, 60.0, -6.0), samples)
        assertTrue("bass trim ${state.bassTrimDb}", state.bassTrimDb < -0.5f)
    }

    @Test
    fun `resetting returns the engine to unity`() {
        val engine = musicEngine()
        engine.feed(seconds = 3.0, dbfs = -6.0)
        engine.reset()
        assertEquals(0.0, engine.state.gainDb.toDouble(), 1e-6)
        assertTrue(!engine.state.hasSignal)
    }
}

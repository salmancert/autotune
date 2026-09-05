package dev.autotune.core.dsp

import dev.autotune.core.TestSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessMeterTest {

    private val sampleRate = 16_000

    @Test
    fun `a 1 kHz sine at -20 dBFS reads about -20 LUFS`() {
        val meter = LoudnessMeter(sampleRate)
        meter.process(TestSignals.sine(sampleRate * 2, sampleRate, 1000.0, -20.0))
        // BS.1770 adds its -0.691 offset and the K-weighting is ~flat at 1 kHz.
        assertEquals(-20.7, meter.momentaryLufs.toDouble(), 1.0)
    }

    @Test
    fun `loudness tracks level one for one`() {
        val quiet = LoudnessMeter(sampleRate)
        quiet.process(TestSignals.sine(sampleRate, sampleRate, 1000.0, -30.0))
        val loud = LoudnessMeter(sampleRate)
        loud.process(TestSignals.sine(sampleRate, sampleRate, 1000.0, -20.0))
        assertEquals(10.0, (loud.momentaryLufs - quiet.momentaryLufs).toDouble(), 0.3)
    }

    @Test
    fun `silence reports the floor`() {
        val meter = LoudnessMeter(sampleRate)
        meter.process(TestSignals.silence(sampleRate))
        assertEquals(LoudnessMeter.SILENCE_LUFS, meter.momentaryLufs, 0.01f)
    }

    @Test
    fun `bass is discounted relative to the speech band`() {
        // K-weighting exists because a 40 Hz rumble is far less loud than a
        // 1 kHz tone of the same energy; the meter has to reflect that.
        val bass = LoudnessMeter(sampleRate)
        bass.process(TestSignals.sine(sampleRate, sampleRate, 40.0, -20.0))
        val speech = LoudnessMeter(sampleRate)
        speech.process(TestSignals.sine(sampleRate, sampleRate, 1000.0, -20.0))
        // The RLB high-pass takes roughly 6 dB off at 40 Hz.
        assertTrue(
            "bass ${bass.momentaryLufs} should read quieter than speech ${speech.momentaryLufs}",
            bass.momentaryLufs < speech.momentaryLufs - 4f,
        )
    }

    @Test
    fun `the momentary window follows level changes faster than the short term one`() {
        val meter = LoudnessMeter(sampleRate)
        meter.process(TestSignals.sine(sampleRate * 3, sampleRate, 1000.0, -35.0))
        meter.process(TestSignals.sine(sampleRate / 2, sampleRate, 1000.0, -10.0))
        // 500 ms into a 25 dB jump the 400 ms window has arrived and the 3 s
        // window is still mostly holding the quiet passage.
        assertTrue(
            "momentary ${meter.momentaryLufs} short-term ${meter.shortTermLufs}",
            meter.momentaryLufs > meter.shortTermLufs + 6f,
        )
    }
}
